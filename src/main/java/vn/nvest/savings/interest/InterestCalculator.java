package vn.nvest.savings.interest;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class InterestCalculator {

    /** R6: lãi suất không kỳ hạn 0,5%/năm. */
    public static final BigDecimal DEMAND_RATE = new BigDecimal("0.005");

    /** R8: quy ước chia 365 cho mọi năm. */
    private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);

    private InterestCalculator() {
    }

    /**
     * Lãi lũy kế sau days ngày: floor(gốc × lãi suất × days / 365).
     * Dùng cho cả lãi tổng cuối kỳ (R8) lẫn lãi lũy kế từng ngày (R10).
     */
    public static long accruedInterest(long principal, BigDecimal annualRate, int days) {
        if (principal <= 0) {
            throw new IllegalArgumentException("principal must be positive");
        }

        if (annualRate == null || annualRate.signum() < 0) {
            throw new IllegalArgumentException("annualRate must not be negative");
        }
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative");
        }

        return BigDecimal.valueOf(principal)
                .multiply(annualRate)
                .multiply(BigDecimal.valueOf(days))
                .divide(DAYS_IN_YEAR, 0, RoundingMode.DOWN)
                .longValueExact();
    }

    /**
     * Lãi của riêng ngày thứ n (n bắt đầu từ 1), theo R10:
     * lãi_lũy_kế(n) - lãi_lũy_kế(n-1).
     */
    public static long dailyInterest(long principal, BigDecimal annualRate, int dayNumber) {
        if (dayNumber < 1) {
            throw new IllegalArgumentException("dayNumber must be >= 1");
        }
        return accruedInterest(principal, annualRate, dayNumber)
                - accruedInterest(principal, annualRate, dayNumber - 1);
    }

    /** Lãi khi rút trước hạn (R23): chỉ tính lãi không kỳ hạn cho số ngày đã gửi. */
    public static long earlyWithdrawalInterest(long principal, int daysHeld) {
        return accruedInterest(principal, DEMAND_RATE, daysHeld);
    }
}
