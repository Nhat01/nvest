package vn.nvest.savings.outbox;

import java.time.LocalDate;

/** productOnSale = false và option khác PAYOUT_ALL nghĩa là đến hạn sẽ tự chuyển sang trả hết (R18). */
public record SavingsMaturityReminderEvent(long accountId, long userId, long principal, long expectedInterest,
                                           LocalDate maturityDate, int daysLeft, String maturityOption,
                                           boolean productOnSale) {
}