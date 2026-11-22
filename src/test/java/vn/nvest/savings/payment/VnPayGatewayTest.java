package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VnPayGatewayTest {

    private final VnPayGateway gateway = new VnPayGateway(
            new VnPayProperties("TESTCODE", "test-secret", "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
                    "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction",
                    "http://localhost:3000/payment/result", "127.0.0.1", Duration.ofMinutes(15)),
            Clock.systemUTC());

    private static Map<String, String> parseQuery(String url) {
        Map<String, String> map = new HashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return map;
    }

    @Test
    @DisplayName("URL thanh toán: số tiền nhân 100, giờ GMT+7, chữ ký tự kiểm tra được")
    void checkoutUrlIsSignedAndAmountIsTimes100() {
        Instant createdAt = Instant.parse("2026-11-09T10:00:00Z");   // 17:00:00 giờ Việt Nam

        String url = gateway.createCheckout("DEP-ABC", 500_000, createdAt).checkoutUrl();
        Map<String, String> params = parseQuery(url);

        assertThat(params.get("vnp_Amount")).isEqualTo("50000000");
        assertThat(params.get("vnp_CreateDate")).isEqualTo("20261109170000");
        assertThat(params.get("vnp_ExpireDate")).isEqualTo("20261109171500");
        assertThat(params.get("vnp_TxnRef")).isEqualTo("DEP-ABC");
        assertThat(gateway.verifyParams(params)).isTrue();
    }

    @Test
    @DisplayName("Sửa số tiền hoặc chữ ký thì verify thất bại")
    void tamperingIsDetected() {
        Map<String, String> params = parseQuery(
                gateway.createCheckout("DEP-ABC", 500_000, Instant.now()).checkoutUrl());

        Map<String, String> tampered = new HashMap<>(params);
        tampered.put("vnp_Amount", "90000000");
        Map<String, String> noHash = new HashMap<>(params);
        noHash.remove("vnp_SecureHash");

        assertThat(gateway.verifyParams(tampered)).isFalse();
        assertThat(gateway.verifyParams(noHash)).isFalse();
    }
}