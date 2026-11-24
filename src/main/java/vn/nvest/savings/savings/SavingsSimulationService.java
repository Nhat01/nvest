package vn.nvest.savings.savings;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.AccountNotFoundException;
import vn.nvest.savings.common.InvalidStateTransitionException;
import vn.nvest.savings.common.ProductNotAvailableException;
import vn.nvest.savings.common.ProductNotFoundException;
import vn.nvest.savings.interest.InterestCalculator;

/** Tính lãi dự kiến và mô phỏng rút trước hạn. Chỉ đọc, không ghi sổ cái hay đổi trạng thái gói. */
@Service
public class SavingsSimulationService {

    private static final BigDecimal DEMAND_RATE_PERCENT = InterestCalculator.DEMAND_RATE.movePointRight(2);

    private final SavingsProductRepository productRepository;
    private final SavingsAccountRepository accountRepository;
    private final Clock clock;

    public SavingsSimulationService(SavingsProductRepository productRepository,
                                    SavingsAccountRepository accountRepository,
                                    Clock clock) {
        this.productRepository = productRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    /** Mô phỏng một khoản gửi mới theo sản phẩm đang bán. */
    @Transactional(readOnly = true)
    public SavingsSimulationResponse simulateProduct(String productCode, long amount,
                                                     LocalDate startDate, LocalDate asOfDate) {
        SavingsProduct product = productRepository.findByCode(productCode)
                .orElseThrow(() -> new ProductNotFoundException("Unknown product: " + productCode));
        if (!product.isOnSale()) {
            throw new ProductNotAvailableException("Product is not on sale: " + productCode);
        }
        product.validateAmount(amount);

        LocalDate today = LocalDate.now(clock);
        LocalDate start = startDate != null ? startDate : today;
        LocalDate asOf = asOfDate != null ? asOfDate : (start.isAfter(today) ? start : today);
        return calculate(product.getCode(), product.getName(), amount, product.getAnnualRate(),
                product.getTermDays(), start, asOf);
    }

    /** Mô phỏng rút trước hạn cho gói của chính user, dùng lãi suất đã chốt lúc mở gói (R5). */
    @Transactional(readOnly = true)
    public SavingsSimulationResponse simulateAccount(long userId, long accountId, LocalDate asOfDate) {
        SavingsAccount account = accountRepository.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidStateTransitionException("Account " + accountId + " is "
                    + account.getStatus() + ": only ACTIVE accounts can be simulated");
        }

        SavingsProduct product = productRepository.findById(account.getProductId())
                .orElseThrow(() -> new ProductNotFoundException("Unknown product: " + account.getProductId()));
        LocalDate asOf = asOfDate != null ? asOfDate : LocalDate.now(clock);
        return calculate(product.getCode(), product.getName(), account.getPrincipal(),
                account.getAnnualRate(), account.getTermDays(), account.getStartDate(), asOf);
    }

    private static SavingsSimulationResponse calculate(String productCode, String productName,
                                                       long principal, BigDecimal annualRate,
                                                       int termDays, LocalDate startDate,
                                                       LocalDate asOfDate) {
        if (asOfDate.isBefore(startDate)) {
            throw new IllegalArgumentException("asOfDate must not be before startDate");
        }

        LocalDate maturityDate = startDate.plusDays(termDays);
        int daysHeld = (int) Math.min(ChronoUnit.DAYS.between(startDate, asOfDate), termDays);
        int daysRemaining = (int) Math.max(0, ChronoUnit.DAYS.between(asOfDate, maturityDate));

        long maturityInterest = InterestCalculator.accruedInterest(principal, annualRate, termDays);
        long maturityPayout = Math.addExact(principal, maturityInterest);

        boolean earlyAvailable = asOfDate.isBefore(maturityDate);
        Long earlyInterest = null;
        Long earlyPayout = null;
        Long interestLoss = null;
        if (earlyAvailable) {
            // R23, R24: lãi không kỳ hạn cho số ngày đã gửi trọn vẹn
            earlyInterest = InterestCalculator.earlyWithdrawalInterest(principal, daysHeld);
            earlyPayout = Math.addExact(principal, earlyInterest);
            // Sản phẩm có lãi suất thấp hơn lãi không kỳ hạn thì rút sớm không lỗ; chặn số âm.
            interestLoss = Math.max(0L, maturityInterest - earlyInterest);
        }

        return new SavingsSimulationResponse(
                productCode, productName, principal,
                annualRate.movePointRight(2), DEMAND_RATE_PERCENT, termDays,
                startDate, maturityDate, asOfDate,
                daysHeld, daysRemaining,
                maturityInterest, maturityPayout,
                earlyAvailable, earlyInterest, earlyPayout, interestLoss);
    }
}
