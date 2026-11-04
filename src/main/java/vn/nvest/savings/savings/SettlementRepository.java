package vn.nvest.savings.savings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByAccountId(Long accountId);

    long countByAccountId(Long accountId);
}