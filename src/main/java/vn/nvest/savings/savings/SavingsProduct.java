package vn.nvest.savings.savings;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

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

    private static final BigDecimal MAX_ANNUAL_RATE = new BigDecimal("0.2000");   // R36

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
        if (!isAmountAllowed(amount)) {
            throw new IllegalArgumentException(
                    "Amount must be between " + minAmount + " and " + maxAmount + " (R3), got " + amount);
        }
    }

    public boolean isAmountAllowed(long amount) {
        return amount >= minAmount && amount <= maxAmount;
    }

    /** Ngừng bán sản phẩm (admin). */
    public void stopSale() {
        this.status = ProductStatus.STOPPED;
    }

    /** R36: lãi suất dạng thập phân trong khoảng 0 đến 0,2000. Không ảnh hưởng gói đã mở (R5). */
    public void changeAnnualRate(BigDecimal newRate) {
        if (newRate == null || newRate.signum() < 0 || newRate.compareTo(MAX_ANNUAL_RATE) > 0) {
            throw new IllegalArgumentException("Annual rate must be between 0 and 0.2000 (R36)");
        }
        this.annualRate = newRate;
    }
}
