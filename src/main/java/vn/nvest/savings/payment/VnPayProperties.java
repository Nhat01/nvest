package vn.nvest.savings.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "nvest.payment.vnpay")
public record VnPayProperties(
        String tmnCode,
        String hashSecret,
        @DefaultValue("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html") String payUrl,
        @DefaultValue("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction") String apiUrl,
        String returnUrl,
        @DefaultValue("127.0.0.1") String clientIp,
        @DefaultValue("PT15M") Duration paymentTtl) {
}