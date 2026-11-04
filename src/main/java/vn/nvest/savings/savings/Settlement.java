package vn.nvest.savings.savings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "settlements")
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", nullable = false)
    private SettlementType settlementType;

    @Enumerated(EnumType.STRING)
    @Column(name = "option_applied", nullable = false)
    private MaturityOption optionApplied;

    @Column(name = "principal", nullable = false)
    private long principal;

    @Column(name = "interest", nullable = false)
    private long interest;

    @Column(name = "new_account_id")
    private Long newAccountId;

    @Column(name = "ledger_transaction_id")
    private Long ledgerTransactionId;

    @Column(name = "fallback_reason")
    private String fallbackReason;

    public Settlement(Long accountId, SettlementType settlementType, MaturityOption optionApplied,
                      long principal, long interest, Long newAccountId,
                      Long ledgerTransactionId, String fallbackReason) {
        this.accountId = accountId;
        this.settlementType = settlementType;
        this.optionApplied = optionApplied;
        this.principal = principal;
        this.interest = interest;
        this.newAccountId = newAccountId;
        this.ledgerTransactionId = ledgerTransactionId;
        this.fallbackReason = fallbackReason;
    }
}