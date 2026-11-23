package vn.nvest.savings.outbox;

public record DepositSucceededEvent(long paymentId, long userId, long amount, String orderCode) {
}