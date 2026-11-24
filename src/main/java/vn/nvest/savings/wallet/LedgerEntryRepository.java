package vn.nvest.savings.wallet;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long>, StatementSearchRepository {

    List<LedgerEntry> findByTransactionId(Long transactionId);

    /** Tổng các dòng ledger của một ngăn số dư, dùng để đối soát với bảng wallets. */
    @Query("select coalesce(sum(e.amount), 0L) from LedgerEntry e "
            + "where e.walletId = :walletId and e.balanceType = :balanceType")
    long sumByWalletAndBalanceType(@Param("walletId") Long walletId,
                                   @Param("balanceType") BalanceType balanceType);
}
