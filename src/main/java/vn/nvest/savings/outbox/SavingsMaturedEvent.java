package vn.nvest.savings.outbox;

/** fallbackReason khác null nghĩa là hệ thống tự chuyển sang PAYOUT_ALL (R18): đây chính là thứ user cần được thông báo. */
public record SavingsMaturedEvent(long accountId, long userId, long principal, long interest,
                                  String optionApplied, String fallbackReason, Long newAccountId) {
}