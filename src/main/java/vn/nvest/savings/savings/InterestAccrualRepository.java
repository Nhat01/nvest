package vn.nvest.savings.savings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, Long> {

    /** Số thứ tự ngày đã ghi lãi gần nhất của một gói (rỗng nếu chưa ghi ngày nào). */
    @Query("select max(a.dayNumber) from InterestAccrual a where a.accountId = :accountId")
    Optional<Integer> findMaxDayNumber(@Param("accountId") Long accountId);

    List<InterestAccrual> findByAccountIdOrderByDayNumber(Long accountId);

    long countByAccountId(Long accountId);

    @Query("select coalesce(sum(a.amount), 0L) from InterestAccrual a where a.accountId = :accountId")
    long sumAmountByAccountId(@Param("accountId") Long accountId);
}
