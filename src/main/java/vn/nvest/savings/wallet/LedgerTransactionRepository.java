package vn.nvest.savings.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {
    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);

    /** Tổng các dòng ledger của một ngăn số dư, dùng để đối soát với bảng wallets. */
    @Query("select coalesce(sum(e.amount), 0L) from LedgerEntry e "
            + "where e.walletId = :walletId and e.balanceType = :balanceType")
    long sumByWalletAndBalanceType(@Param("walletId") Long walletId,
                                   @Param("balanceType") BalanceType balanceType);
}
