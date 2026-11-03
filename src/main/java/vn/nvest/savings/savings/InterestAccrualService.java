package vn.nvest.savings.savings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.interest.InterestCalculator;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class InterestAccrualService {

    private final SavingsAccountRepository accountRepository;
    private final InterestAccrualRepository accrualRepository;

    public InterestAccrualService(SavingsAccountRepository accountRepository,
                                  InterestAccrualRepository accrualRepository) {
        this.accountRepository = accountRepository;
        this.accrualRepository = accrualRepository;
    }

    /**
     * Ghi lãi cho gói, từ ngày kế tiếp ngày đã ghi gần nhất đến hết ngày asOf (R40, R41).
     * Gọi lặp lại an toàn: không có gì để ghi thì trả 0.
     *
     * @return số dòng lãi mới được tạo
     */
    @Transactional
    public int accrueAccount(long accountId, LocalDate asOf) {
        // Khóa trước, đọc sau: sau khi có khóa mới nhìn thấy trạng thái mới nhất (R26, R42)
        SavingsAccount account = accountRepository.findByIdForUpdate(accountId).orElse(null);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE) {
            return 0;
        }

        // Ngày thứ n ứng với ngày start + n - 1; không bao giờ vượt quá kỳ hạn
        long daysDue = ChronoUnit.DAYS.between(account.getStartDate(), asOf) + 1;
        int lastDay = (int) Math.min(daysDue, account.getTermDays());
        int firstDay = accrualRepository.findMaxDayNumber(accountId).orElse(0) + 1;

        int created = 0;
        for (int day = firstDay; day <= lastDay; day++) {
            long amount = InterestCalculator.dailyInterest(
                    account.getPrincipal(), account.getAnnualRate(), day);   // R10
            accrualRepository.save(new InterestAccrual(
                    accountId, day, account.getStartDate().plusDays(day - 1L), amount));
            created++;
        }
        return created;
    }

    /** Tổng lãi tạm tính đã ghi của một gói (R11), dùng cho màn hình "lãi tạm tính". */
    @Transactional(readOnly = true)
    public long getAccruedInterest(long accountId) {
        return accrualRepository.sumAmountByAccountId(accountId);
    }
}