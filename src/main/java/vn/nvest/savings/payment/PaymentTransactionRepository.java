package vn.nvest.savings.payment;

import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentTransaction> findByOrderCode(String orderCode);

    /** Khóa dòng giao dịch cho đến hết transaction: khóa trước, đọc sau (R50). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransaction p where p.orderCode = :orderCode")
    Optional<PaymentTransaction> findByOrderCodeForUpdate(@Param("orderCode") String orderCode);

    /** Khóa advisory theo key cho đến hết transaction (xếp hàng các request cùng idempotency key, R54). */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) AS t", nativeQuery = true)
    int lockByKey(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransaction p where p.id = :id")
    Optional<PaymentTransaction> findByIdForUpdate(@Param("id") Long id);

    /** Id các giao dịch còn dang dở (theo hướng và trạng thái), tạo trước mốc cutoff, cũ nhất trước. */
    @Query("select p.id from PaymentTransaction p where p.direction = :direction "
            + "and p.status in :statuses and p.createdAt < :cutoff order by p.createdAt, p.id")
    List<Long> findOpenIdsCreatedBefore(@Param("direction") PaymentDirection direction,
                                        @Param("statuses") Collection<PaymentStatus> statuses,
                                        @Param("cutoff") Instant cutoff);
}