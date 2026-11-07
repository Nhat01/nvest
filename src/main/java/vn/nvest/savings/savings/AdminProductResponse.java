package vn.nvest.savings.savings;

import java.math.BigDecimal;

/** Khác ProductResponse ở chỗ có trạng thái, vì admin cần thấy cả sản phẩm đã ngừng bán. */
public record AdminProductResponse(String code, String name, int termDays, BigDecimal annualRatePercent,
                                   long minAmount, long maxAmount, ProductStatus status) {

    public static AdminProductResponse from(SavingsProduct product) {
        return new AdminProductResponse(product.getCode(), product.getName(), product.getTermDays(),
                product.getAnnualRate().movePointRight(2), product.getMinAmount(), product.getMaxAmount(),
                product.getStatus());
    }
}