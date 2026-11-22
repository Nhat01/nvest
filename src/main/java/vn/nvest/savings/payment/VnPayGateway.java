package vn.nvest.savings.payment;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.nvest.savings.common.ClockConfig;
import vn.nvest.savings.common.GatewayTimeoutException;
import vn.nvest.savings.utils.Extensions;

@Component
@Primary
@ConditionalOnProperty(name = "nvest.payment.provider", havingValue = "vnpay")
@EnableConfigurationProperties(VnPayProperties.class)
public class VnPayGateway implements PayInGateway {

    public static final String PROVIDER = "VNPAY";
    private static final String HMAC = "HmacSHA512";
    private static final String VERSION = "2.1.0";
    /** VNPAY dùng giờ GMT+7 dạng yyyyMMddHHmmss (R39). */
    private static final DateTimeFormatter VNP_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ClockConfig.BUSINESS_ZONE);

    private final VnPayProperties props;
    private final byte[] secret;
    private final RestClient http;
    private final Clock clock;

    public VnPayGateway(VnPayProperties props, Clock clock) {
        if (Extensions.isBlankOrNull(props.tmnCode()) || Extensions.isBlankOrNull(props.hashSecret())
                || Extensions.isBlankOrNull(props.returnUrl())) {
            throw new IllegalStateException(
                    "nvest.payment.vnpay.tmn-code, hash-secret and return-url are required when provider=vnpay");
        }
        this.props = props;
        this.secret = props.hashSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    /** Dựng URL thanh toán đã ký. Không gọi mạng nên an toàn khi đang nằm trong transaction (L4). */
    @Override
    public CheckoutSession createCheckout(String orderCode, long amount, Instant createdAt) {
        Map<String, String> p = new TreeMap<>();
        p.put("vnp_Version", VERSION);
        p.put("vnp_Command", "pay");
        p.put("vnp_TmnCode", props.tmnCode());
        p.put("vnp_Amount", String.valueOf(Math.multiplyExact(amount, 100L)));   // VNPAY: VND x 100
        p.put("vnp_CurrCode", "VND");
        p.put("vnp_TxnRef", orderCode);
        p.put("vnp_OrderInfo", "Nap tien " + orderCode);                          // không dấu, không ký tự đặc biệt
        p.put("vnp_OrderType", "other");
        p.put("vnp_Locale", "vn");
        p.put("vnp_ReturnUrl", props.returnUrl());
        p.put("vnp_IpAddr", props.clientIp());
        p.put("vnp_CreateDate", VNP_TIME.format(createdAt));
        p.put("vnp_ExpireDate", VNP_TIME.format(createdAt.plus(props.paymentTtl())));

        String query = canonical(p);
        String url = props.payUrl() + "?" + query + "&vnp_SecureHash=" + hmac(query);
        return new CheckoutSession(null, url);   // chưa có mã giao dịch phía VNPAY, IPN sẽ mang về
    }

    /** Hỏi lại VNPAY bằng querydr. Kết quả mơ hồ thì ném lỗi để job đối soát thử lại, KHÔNG kết luận FAILED. */
    @Override
    public QueryResult queryStatus(String orderCode, Instant createdAt) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String txDate = VNP_TIME.format(createdAt);
        String now = VNP_TIME.format(clock.instant());
        String orderInfo = "Truy van " + orderCode;
        String hashData = String.join("|", requestId, VERSION, "querydr", props.tmnCode(),
                orderCode, txDate, now, props.clientIp(), orderInfo);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("vnp_RequestId", requestId);
        body.put("vnp_Version", VERSION);
        body.put("vnp_Command", "querydr");
        body.put("vnp_TmnCode", props.tmnCode());
        body.put("vnp_TxnRef", orderCode);
        body.put("vnp_OrderInfo", orderInfo);
        body.put("vnp_TransactionDate", txDate);
        body.put("vnp_CreateDate", now);
        body.put("vnp_IpAddr", props.clientIp());
        body.put("vnp_SecureHash", hmac(hashData));

        JsonNode res;
        try {
            res = http.post().uri(props.apiUrl()).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new GatewayTimeoutException("VNPAY querydr failed: " + e.getMessage());
        }
        if (res == null) {
            throw new GatewayTimeoutException("VNPAY querydr returned an empty body");
        }

        String code = res.path("vnp_ResponseCode").asText();
        if ("91".equals(code)) {   // không tìm thấy giao dịch
            return new QueryResult(GatewayStatus.NOT_FOUND, 0, null);
        }
        if (!"00".equals(code)) {
            throw new GatewayTimeoutException(
                    "VNPAY querydr answered " + code + ": " + res.path("vnp_Message").asText());
        }
        long amount = Long.parseLong(res.path("vnp_Amount").asText("0")) / 100;
        String ref = res.path("vnp_TransactionNo").asText(null);
        GatewayStatus status = switch (res.path("vnp_TransactionStatus").asText()) {
            case "00" -> GatewayStatus.SUCCEEDED;
            case "01" -> GatewayStatus.PENDING;   // chưa hoàn tất
            default -> GatewayStatus.FAILED;      // 02 lỗi, 04 đảo, ...
        };
        return new QueryResult(status, amount, ref);
    }

    /** payload = chuỗi chuẩn hóa (xem canonical), signature = vnp_SecureHash. */
    @Override
    public boolean verifySignature(String payload, String signature) {
        if (payload == null || signature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hmac(payload).getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /** Kiểm tra các tham số IPN/return mà VNPAY gửi về. */
    public boolean verifyParams(Map<String, String> params) {
        Map<String, String> signed = new TreeMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("vnp_") && !k.equals("vnp_SecureHash") && !k.equals("vnp_SecureHashType")) {
                signed.put(k, v);
            }
        });
        return verifySignature(canonical(signed), params.get("vnp_SecureHash"));
    }

    /** Ký các tham số (dùng cho test dựng IPN giả). */
    public String sign(Map<String, String> params) {
        return hmac(canonical(new TreeMap<>(params)));
    }

    public String tmnCode() {
        return props.tmnCode();
    }

    /** Sắp theo tên, bỏ giá trị rỗng, URL-encode (US_ASCII, khoảng trắng thành '+'), nối bằng '&'. */
    static String canonical(Map<String, String> sortedParams) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(sortedParams).entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign payload", e);
        }
    }
}