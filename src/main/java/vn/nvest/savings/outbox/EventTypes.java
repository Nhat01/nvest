package vn.nvest.savings.outbox;

public final class EventTypes {
    public static final String DEPOSIT_SUCCEEDED = "DepositSucceeded";
    public static final String WITHDRAWAL_SETTLED = "WithdrawalSettled";
    public static final String SAVINGS_MATURED = "SavingsMatured";

    private EventTypes() {
    }
}