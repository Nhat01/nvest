package vn.nvest.savings.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByUserId(Long userId);

    /** Chỉ dùng cho ví hệ thống (mỗi loại có đúng một ví). */
    Optional<Wallet> findByWalletType(WalletType walletType);

    /** SELECT ... FOR UPDATE: khóa dòng ví cho đến hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("Select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
