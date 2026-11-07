package vn.nvest.savings.payment;

import java.time.Instant;

public record PaymentResponse(Long id, String orderCode, PaymentDirection direction, long amount,
                              PaymentStatus status, String checkoutUrl, Instant createdAt) {

    public static PaymentResponse from(PaymentTransaction payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderCode(), payment.getDirection(),
                payment.getAmount(), payment.getStatus(), payment.getCheckoutUrl(), payment.getCreatedAt());
    }
}