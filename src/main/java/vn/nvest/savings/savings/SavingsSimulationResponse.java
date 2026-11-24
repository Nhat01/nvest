package vn.nvest.savings.savings;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Kết quả mô phỏng lãi và rút trước hạn, không thay đổi dữ liệu.
 *
 * Các trường earlyWithdrawal* là null khi asOfDate đã chạm hoặc vượt ngày đáo hạn,
 * vì lúc đó không còn rút trước hạn được nữa (R51).
 */
public record SavingsSimulationResponse(
        String productCode,
        String productName,
        long principal,
        BigDecimal annualRatePercent,
        BigDecimal demandRatePercent,
        int termDays,
        LocalDate startDate,
        LocalDate maturityDate,
        LocalDate asOfDate,
        int daysHeld,
        int daysRemaining,
        long maturityInterest,
        long maturityPayout,
        boolean earlyWithdrawalAvailable,
        Long earlyWithdrawalInterest,
        Long earlyWithdrawalPayout,
        Long interestLoss) {
}
