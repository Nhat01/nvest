package vn.nvest.savings.payment;

import java.time.Instant;

/** Cổng thu tiền (nạp). */
public interface PayInGateway {

    String providerName();

    /** createdAt được truyền vào để querydr sau này dùng đúng ngày giao dịch đã gửi cổng. */
    CheckoutSession createCheckout(String orderCode, long amount, Instant createdAt);

    QueryResult queryStatus(String orderCode, Instant createdAt);

    boolean verifySignature(String payload, String signature);
}