package vn.nvest.savings.savings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SavingsProductRepository extends JpaRepository<SavingsProduct, Long> {

    Optional<SavingsProduct> findByCode(String code);
}