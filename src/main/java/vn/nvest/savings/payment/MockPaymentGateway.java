package vn.nvest.savings.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.nvest.savings.common.GatewayTimeoutException;

/** Cổng thanh toán giả, chạy trong bộ nhớ. Bật bằng nvest.payment.provider=mock (mặc định). */
@Component
public class MockPaymentGateway implements PayInGateway, PayoutGateway {

    public static final String PROVIDER = "MOCK";
    private static final String HMAC = "HmacSHA256";

    /** Cách cổng giả xử lý lệnh chi tiền kế tiếp (dùng trong test ở 9B). */
    public enum PayoutBehavior {
        SUCCEED, FAIL, TIMEOUT_NOT_EXECUTED, TIMEOUT_BUT_EXECUTED
    }

    public record SignedWebhook(String payload, String signature) {
    }

    private record Order(long amount, GatewayStatus status, String providerRef) {
    }

    private final byte[] secret;
    private final String checkoutBaseUrl;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Queue<PayoutBehavior> scriptedPayouts = new ConcurrentLinkedQueue<>();

    public MockPaymentGateway(@Value("${nvest.payment.mock.secret:dev-mock-secret}") String secret,
                              @Value("${nvest.payment.mock.checkout-base-url:http://localhost:8080}")
                              String checkoutBaseUrl) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.checkoutBaseUrl = checkoutBaseUrl.endsWith("/")
                ? checkoutBaseUrl.substring(0, checkoutBaseUrl.length() - 1)
                : checkoutBaseUrl;
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public CheckoutSession createCheckout(String orderCode, long amount, Instant createdAt) {
        Order order = orders.computeIfAbsent(orderCode,
                code -> new Order(amount, GatewayStatus.PENDING, nextRef()));
        return new CheckoutSession(order.providerRef(), checkoutBaseUrl + "/mock-gateway/pay/" + orderCode);
    }

    @Override
    public QueryResult queryStatus(String orderCode, Instant createdAt) {
        return queryStatus(orderCode);
    }

    @Override
    public PayoutResult payout(String orderCode, long amount) {
        Order existing = orders.get(orderCode);
        if (existing != null && existing.status() != GatewayStatus.PENDING) {
            // Cùng một đơn gọi lại: trả kết quả cũ, không chi hai lần
            return new PayoutResult(existing.status(), existing.providerRef(), "already processed");
        }
        PayoutBehavior behavior = Optional.ofNullable(scriptedPayouts.poll()).orElse(PayoutBehavior.SUCCEED);
        return switch (behavior) {
            case SUCCEED -> complete(orderCode, amount, GatewayStatus.SUCCEEDED, "paid");
            case FAIL -> complete(orderCode, amount, GatewayStatus.FAILED, "rejected by bank");
            case TIMEOUT_NOT_EXECUTED -> throw new GatewayTimeoutException("timeout, payout NOT executed");
            case TIMEOUT_BUT_EXECUTED -> {
                complete(orderCode, amount, GatewayStatus.SUCCEEDED, "paid");   // cổng đã chi...
                throw new GatewayTimeoutException("timeout, payout WAS executed");   // ...nhưng ta không biết
            }
        };
    }

    @Override
    public QueryResult queryStatus(String orderCode) {
        Order order = orders.get(orderCode);
        if (order == null) {
            return new QueryResult(GatewayStatus.NOT_FOUND, 0, null);
        }
        return new QueryResult(order.status(), order.amount(), order.providerRef());
    }

    @Override
    public boolean verifySignature(String payload, String signature) {
        if (payload == null || signature == null) {
            return false;
        }
        // isEqual so sánh trong thời gian không phụ thuộc vị trí byte lệch
        return MessageDigest.isEqual(
                sign(payload).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC-SHA256 của nội dung, dạng hex. Cổng thật làm đúng việc này ở phía họ. */
    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign payload", e);
        }
    }

    // ---- Các hàm dưới đây chỉ để test "điều khiển" cổng giả ----

    public void scriptNextPayout(PayoutBehavior behavior) {
        scriptedPayouts.add(behavior);
    }

    public void markPaidByCustomer(String orderCode) {
        orders.computeIfPresent(orderCode, (code, o) -> new Order(o.amount(), GatewayStatus.SUCCEEDED, o.providerRef()));
    }

    public void markFailedByCustomer(String orderCode) {
        orders.computeIfPresent(orderCode, (code, o) -> new Order(o.amount(), GatewayStatus.FAILED, o.providerRef()));
    }

    /** Dựng một webhook đúng định dạng, đã ký, như thể cổng gọi về. */
    public SignedWebhook webhookFor(String orderCode, GatewayStatus status, long amount) {
        Order order = orders.get(orderCode);
        return webhookFor(orderCode, order != null ? order.providerRef() : "MOCK-UNKNOWN", status, amount);
    }

    /**
     * Dựng webhook với providerRef do bên gọi cung cấp (thường đọc từ DB).
     * Trang checkout giả dùng bản này để vẫn hoạt động sau restart, khi state trong bộ nhớ đã mất.
     */
    public SignedWebhook webhookFor(String orderCode, String providerRef, GatewayStatus status, long amount) {
        String refJson = providerRef == null ? "null" : "\"" + providerRef + "\"";
        String payload = "{\"orderCode\":\"" + orderCode + "\",\"providerRef\":" + refJson
                + ",\"status\":\"" + status + "\",\"amount\":" + amount + "}";
        return new SignedWebhook(payload, sign(payload));
    }

    public void reset() {
        orders.clear();
        scriptedPayouts.clear();
    }

    private PayoutResult complete(String orderCode, long amount, GatewayStatus status, String message) {
        String ref = nextRef();
        orders.put(orderCode, new Order(amount, status, ref));
        return new PayoutResult(status, ref, message);
    }

    /** Làm như cổng không có dấu vết gì về đơn này. */
    public void forget(String orderCode) {
        orders.remove(orderCode);
    }

    /**
     * providerRef ngẫu nhiên thay vì đếm trong bộ nhớ: sau khi restart, bộ đếm cũ về 0 sẽ sinh lại
     * MOCK-1 và đụng unique index (provider, provider_ref) của các giao dịch đã lưu trong DB.
     */
    private String nextRef() {
        return "MOCK-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}