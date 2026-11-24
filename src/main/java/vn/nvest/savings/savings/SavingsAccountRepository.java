package vn.nvest.savings.savings;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SavingsAccountRepository extends JpaRepository<SavingsAccount, Long> {

    Optional<SavingsAccount> findByIdempotencyKey(String idempotencyKey);

    List<SavingsAccount> findByUserIdOrderByIdDesc(Long userId);

    List<SavingsAccount> findByUserIdAndStatus(Long userId, AccountStatus status);

    /** Khóa theo idempotency key cho tới hết transaction hiện tại (advisory lock của Postgres). */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) AS t",
            nativeQuery = true)
    int lockByIdempotencyKey(@Param("key") String key);

    /** Khóa dòng gói cho đến hết transaction (chống hai lần chạy chồng nhau, R42). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SavingsAccount a where a.id = :id")
    Optional<SavingsAccount> findByIdForUpdate(@Param("id") Long id);

    /** Id các gói cần xét ghi lãi: đang chạy và đã bắt đầu từ ngày asOf trở về trước. */
    @Query("select a.id from SavingsAccount a where a.status = :status and a.startDate <= :asOf order by a.id")
    List<Long> findIdsByStatusStartedOnOrBefore(@Param("status") AccountStatus status,
                                                @Param("asOf") LocalDate asOf);

    /** Id các gói ACTIVE đã đến hạn (maturity_date <= asOf), cũ nhất trước. Khớp index ix_savings_accounts_due. */
    @Query("select a.id from SavingsAccount a where a.status = :status and a.maturityDate <= :asOf "
            + "order by a.maturityDate, a.id")
    List<Long> findIdsByStatusMaturingOnOrBefore(@Param("status") AccountStatus status,
                                                 @Param("asOf") LocalDate asOf);
}
