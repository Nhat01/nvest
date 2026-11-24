package vn.nvest.savings.savings;

/** Tổng hợp các gói ACTIVE của một user. accruedInterest là lãi đã ghi đến hết hôm qua (R40). */
public record SavingsSummary(int activeCount, long totalPrincipal, long accruedInterest, long expectedInterest) {
}
