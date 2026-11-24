package vn.nvest.savings.savings;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.interest.InterestCalculator;

@Service
public class SavingsSummaryService {

    private final SavingsAccountRepository accountRepository;
    private final InterestAccrualRepository accrualRepository;

    public SavingsSummaryService(SavingsAccountRepository accountRepository,
                                 InterestAccrualRepository accrualRepository) {
        this.accountRepository = accountRepository;
        this.accrualRepository = accrualRepository;
    }

    /** Hai truy vấn cho mọi số gói, không truy vấn lặp theo từng gói (L8). Lãi dự kiến theo R8, tính bằng công thức. */
    @Transactional(readOnly = true)
    public SavingsSummary summarize(long userId) {
        List<SavingsAccount> active = accountRepository.findByUserIdAndStatus(userId, AccountStatus.ACTIVE);
        if (active.isEmpty()) {
            return new SavingsSummary(0, 0, 0, 0);
        }
        long principal = 0;
        long expected = 0;
        for (SavingsAccount account : active) {
            principal = Math.addExact(principal, account.getPrincipal());
            expected = Math.addExact(expected, InterestCalculator.accruedInterest(
                    account.getPrincipal(), account.getAnnualRate(), account.getTermDays()));
        }
        long accrued = accrualRepository.sumAmountByAccountIds(active.stream().map(SavingsAccount::getId).toList());
        return new SavingsSummary(active.size(), principal, accrued, expected);
    }
}
