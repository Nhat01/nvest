package vn.nvest.savings.wallet;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Getter
@NoArgsConstructor
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_type", nullable = false)
    private BalanceType balanceType;

    @Column(name = "amount", nullable = false)
    private long amount;

    public LedgerEntry(Long transactionId, Long walletId, BalanceType balanceType, long amount) {
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.balanceType = balanceType;
        this.amount = amount;
    }
}
