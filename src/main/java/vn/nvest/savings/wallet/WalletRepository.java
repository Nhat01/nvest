package vn.nvest.savings.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    @Query("select w.id from Wallet w where w.userId = :userId")
    Optional<Long> findIdByUserId(@Param("userId") Long userId);

    Optional<Wallet> findByUserId(Long userId);

    /** Chỉ dùng cho ví hệ thống (mỗi loại có đúng một ví). */
    @Query("select w.id from Wallet w where w.walletType = :type")
    Optional<Long> findIdByWalletType(@Param("type") WalletType type);

    /** SELECT ... FOR UPDATE: khóa dòng ví cho đến hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("Select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
