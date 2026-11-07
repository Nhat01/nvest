package vn.nvest.savings.wallet;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByTransactionId(Long transactionId);

    /** Tổng các dòng ledger của một ngăn số dư, dùng để đối soát với bảng wallets. */
    @Query("select coalesce(sum(e.amount), 0L) from LedgerEntry e "
            + "where e.walletId = :walletId and e.balanceType = :balanceType")
    long sumByWalletAndBalanceType(@Param("walletId") Long walletId,
                                   @Param("balanceType") BalanceType balanceType);

    @Query("select new vn.nvest.savings.wallet.StatementLine(e.id, t.txType, e.balanceType, e.amount, "
            + "t.referenceType, t.referenceId, t.description, t.createdAt) "
            + "from LedgerEntry e join LedgerTransaction t on t.id = e.transactionId "
            + "where e.walletId = :walletId order by e.id desc")
    List<StatementLine> findStatement(@Param("walletId") Long walletId, Pageable pageable);
}
