package vn.nvest.savings.savings;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "interest_accruals")
public class InterestAccrual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "day_number", nullable = false)
    private int dayNumber;

    @Column(name = "accrual_date", nullable = false)
    private LocalDate accrualDate;

    @Column(name = "amount", nullable = false)
    private long amount;

    public InterestAccrual(Long accountId, int dayNumber, LocalDate accrualDate, long amount) {
        this.accountId = accountId;
        this.dayNumber = dayNumber;
        this.accrualDate = accrualDate;
        this.amount = amount;
    }
}
