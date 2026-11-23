package vn.nvest.savings.outbox;

/** status là SUCCEEDED hoặc FAILED. */
public record WithdrawalSettledEvent(long paymentId, long userId, long amount, String orderCode, String status) {
}