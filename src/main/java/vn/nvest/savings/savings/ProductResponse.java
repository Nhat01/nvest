package vn.nvest.savings.savings;

import java.math.BigDecimal;

/** Lãi suất trả ra theo phần trăm/năm: 6.00 nghĩa là 6% (R67). */
public record ProductResponse(String code, String name, int termDays, BigDecimal annualRatePercent,
                              long minAmount, long maxAmount) {

    public static ProductResponse from(SavingsProduct product) {
        return new ProductResponse(product.getCode(), product.getName(), product.getTermDays(),
                product.getAnnualRate().movePointRight(2),   // 0.0600 -> 6.00, chính xác, không làm tròn
                product.getMinAmount(), product.getMaxAmount());
    }
}