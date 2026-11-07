package vn.nvest.savings.wallet;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false)
    private LedgerTxType txType;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "reference_type")
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public LedgerTransaction(LedgerTxType txType, String idempotencyKey,
                             String referenceType, Long referenceId, String description) {
        this.txType = txType;
        this.idempotencyKey = idempotencyKey;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.description = description;
    }
}
