package vn.nvest.savings.savings;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "savings_products")
public class SavingsProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "term_days", nullable = false)
    private int termDays;

    @Column(name = "annual_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal annualRate;   // dạng thập phân: 0.0600 = 6%

    @Column(name = "min_amount", nullable = false)
    private long minAmount;

    @Column(name = "max_amount", nullable = false)
    private long maxAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProductStatus status = ProductStatus.ON_SALE;

    public SavingsProduct(String code, String name, int termDays, BigDecimal annualRate,
                          long minAmount, long maxAmount) {
        this.code = code;
        this.name = name;
        this.termDays = termDays;
        this.annualRate = annualRate;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
    }

    public boolean isOnSale() {
        return status == ProductStatus.ON_SALE;
    }

    /** R3: số tiền gửi phải nằm trong khoảng cho phép của sản phẩm. */
    public void validateAmount(long amount) {
        if (amount < minAmount || amount > maxAmount) {
            throw new IllegalArgumentException(
                    "Amount must be between " + minAmount + " and " + maxAmount + " (R3), got " + amount);
        }
    }
}
