package vn.nvest.savings.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vn.nvest.savings.common.GatewayTimeoutException;

/** Cổng thanh toán giả, chạy trong bộ nhớ. Bật bằng nvest.payment.provider=mock (mặc định). */
@Component
@ConditionalOnProperty(name = "nvest.payment.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

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
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Queue<PayoutBehavior> scriptedPayouts = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequence = new AtomicLong();

    public MockPaymentGateway(@Value("${nvest.payment.mock.secret:dev-mock-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public CheckoutSession createCheckout(String orderCode, long amount) {
        Order order = orders.computeIfAbsent(orderCode,
                code -> new Order(amount, GatewayStatus.PENDING, nextRef()));
        return new CheckoutSession(order.providerRef(), "http://localhost:8080/mock-gateway/pay/" + orderCode);
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
        String providerRef = order != null ? order.providerRef() : "MOCK-UNKNOWN";
        String payload = "{\"orderCode\":\"" + orderCode + "\",\"providerRef\":\"" + providerRef
                + "\",\"status\":\"" + status + "\",\"amount\":" + amount + "}";
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

    private String nextRef() {
        return "MOCK-" + sequence.incrementAndGet();
    }
}