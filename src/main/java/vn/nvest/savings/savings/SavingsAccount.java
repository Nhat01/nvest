package vn.nvest.savings.savings;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vn.nvest.savings.common.InvalidStateTransitionException;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "savings_accounts")
public class SavingsAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "cycle_no", nullable = false)
    private int cycleNo;

    @Column(name = "principal", nullable = false)
    private long principal;

    @Column(name = "annual_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal annualRate;

    @Column(name = "term_days", nullable = false)
    private int termDays;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "maturity_option", nullable = false)
    private MaturityOption maturityOption;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Version
    private long version;

    /** Mở gói kỳ đầu. Chép lãi suất và kỳ hạn từ sản phẩm tại thời điểm này (R5). */
    public static SavingsAccount open(long userId, SavingsProduct product, long principal,
                                      MaturityOption option, LocalDate startDate, String idempotencyKey) {
        SavingsAccount account = new SavingsAccount();
        account.userId = userId;
        account.productId = product.getId();
        account.cycleNo = 1;
        account.principal = principal;
        account.annualRate = product.getAnnualRate();
        account.termDays = product.getTermDays();
        account.startDate = startDate;
        account.maturityDate = startDate.plusDays(product.getTermDays());   // R9
        account.status = AccountStatus.ACTIVE;
        account.maturityOption = option;
        account.idempotencyKey = idempotencyKey;
        return account;
    }

    /**
     * Tạo gói kỳ tiếp theo khi tái tục (R17, R44). Lãi suất và kỳ hạn lấy theo sản phẩm hiện hành;
     * ngày bắt đầu là ngày đáo hạn của gói cũ; tùy chọn đáo hạn được kế thừa.
     */
    public static SavingsAccount renewFrom(SavingsAccount parent, SavingsProduct product, long principal) {
        SavingsAccount next = new SavingsAccount();
        next.userId = parent.userId;
        next.productId = product.getId();
        next.parentId = parent.id;
        next.cycleNo = parent.cycleNo + 1;
        next.principal = principal;
        next.annualRate = product.getAnnualRate();
        next.termDays = product.getTermDays();
        next.startDate = parent.maturityDate;
        next.maturityDate = next.startDate.plusDays(product.getTermDays());
        next.status = AccountStatus.ACTIVE;
        next.maturityOption = parent.maturityOption;
        next.idempotencyKey = "renew:" + parent.id;
        return next;
    }

    /** Cửa duy nhất để đổi trạng thái (R12). */
    public void transitionTo(AccountStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateTransitionException(
                    "Account " + id + ": cannot go from " + status + " to " + next);
        }
        this.status = next;
    }

    /** R16, R48: chỉ đổi được khi gói còn ACTIVE. Kiểm tra ngày đáo hạn nằm ở SavingsService. */
    public void changeMaturityOption(MaturityOption option) {
        if (status != AccountStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "Account " + id + " is " + status + ": maturity option can only change while ACTIVE");
        }
        this.maturityOption = option;
    }
}
