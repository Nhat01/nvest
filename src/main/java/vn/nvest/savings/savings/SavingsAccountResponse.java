package vn.nvest.savings.savings;

import java.math.BigDecimal;
import java.time.LocalDate;

/** accruedInterest chỉ có ở màn hình chi tiết (null ở danh sách) để tránh truy vấn lặp theo từng gói (L8). */
public record SavingsAccountResponse(Long id, Long productId, String productCode, String productName, int cycleNo, Long parentId, long principal,
                                     BigDecimal annualRatePercent, int termDays, LocalDate startDate,
                                     LocalDate maturityDate, AccountStatus status,
                                     MaturityOption maturityOption, Long accruedInterest) {

    public static SavingsAccountResponse from(SavingsAccount account, SavingsProduct product, Long accruedInterest) {
        return new SavingsAccountResponse(account.getId(), account.getProductId(), product.getCode(), product.getName(), account.getCycleNo(),
                account.getParentId(), account.getPrincipal(), account.getAnnualRate().movePointRight(2),
                account.getTermDays(), account.getStartDate(), account.getMaturityDate(),
                account.getStatus(), account.getMaturityOption(), accruedInterest);
    }
}