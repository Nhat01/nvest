package vn.nvest.savings.wallet;

import java.time.LocalDate;

/**
 * Bộ lọc lịch sử giao dịch. Tất cả trường đều tuỳ chọn; null = không lọc.
 *
 * from/to là ngày nghiệp vụ theo giờ Việt Nam, bao gồm cả hai đầu.
 * minAmount/maxAmount so sánh với giá trị tuyệt đối của số tiền,
 * nên lọc "từ 100k đến 500k" áp dụng cho cả tiền vào lẫn tiền ra.
 * keyword tìm không phân biệt hoa thường trong mô tả giao dịch.
 */
public record TransactionFilter(
        LedgerTxType txType,
        BalanceType balanceType,
        TransactionDirection direction,
        LocalDate from,
        LocalDate to,
        Long minAmount,
        Long maxAmount,
        String keyword) {

    public TransactionFilter {
        keyword = normalizeKeyword(keyword);
        if (minAmount != null && minAmount < 0) {
            throw new IllegalArgumentException("minAmount must not be negative");
        }
        if (maxAmount != null && maxAmount < 0) {
            throw new IllegalArgumentException("maxAmount must not be negative");
        }
        if (minAmount != null && maxAmount != null && minAmount > maxAmount) {
            throw new IllegalArgumentException("minAmount must not be greater than maxAmount");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
    }

    public boolean creditOnly() {
        return direction == TransactionDirection.IN;
    }

    public boolean debitOnly() {
        return direction == TransactionDirection.OUT;
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("q must be at most 100 characters");
        }
        return trimmed;
    }
}
