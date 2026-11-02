package vn.nvest.savings.savings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavingsAccountRepository extends JpaRepository<SavingsAccount, Long> {

    Optional<SavingsAccount> findByIdempotencyKey(String idempotencyKey);

    List<SavingsAccount> findByUserIdOrderByIdDesc(Long userId);
}
