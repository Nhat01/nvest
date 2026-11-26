package vn.nvest.savings.savings;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.interest.InterestCalculator;
import vn.nvest.savings.outbox.EventTypes;
import vn.nvest.savings.outbox.OutboxService;
import vn.nvest.savings.outbox.SavingsMaturityReminderEvent;
import vn.nvest.savings.outbox.Topics;

@Service
public class MaturityReminderService {

    /** Mốc nhắc đầu: còn 2-3 ngày (còn 2 nghĩa là job lỡ một ngày, vẫn bù). */
    public static final int FIRST_REMINDER_DAYS = 3;
    /** Mốc nhắc cuối: còn đúng 1 ngày. */
    public static final int FINAL_REMINDER_DAYS = 1;

    private final SavingsAccountRepository accountRepository;
    private final SavingsProductRepository productRepository;
    private final OutboxService outboxService;

    public MaturityReminderService(SavingsAccountRepository accountRepository,
                                   SavingsProductRepository productRepository,
                                   OutboxService outboxService) {
        this.accountRepository = accountRepository;
        this.productRepository = productRepository;
        this.outboxService = outboxService;
    }

    /**
     * Ghi sự kiện nhắc cho một gói trong MỘT transaction (R83).
     * Khóa dòng gói trước để không nhắc nhầm gói vừa rút sớm hoặc vừa quyết toán (R26).
     *
     * true nếu tạo sự kiện mới; false nếu gói không thuộc diện nhắc hoặc mốc này đã nhắc rồi
     */
    @Transactional
    public boolean remind(long accountId, LocalDate today) {
        SavingsAccount account = accountRepository.findByIdForUpdate(accountId).orElse(null);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE) {
            return false;
        }
        int daysLeft = (int) ChronoUnit.DAYS.between(today, account.getMaturityDate());
        if (daysLeft < FINAL_REMINDER_DAYS || daysLeft > FIRST_REMINDER_DAYS) {
            return false;
        }
        int milestone = daysLeft <= FINAL_REMINDER_DAYS ? FINAL_REMINDER_DAYS : FIRST_REMINDER_DAYS;

        SavingsProduct product = productRepository.findById(account.getProductId())
                .orElseThrow(() -> new IllegalStateException("Product missing for account " + accountId));
        long expectedInterest = InterestCalculator.accruedInterest(    // R8, lãi suất đã chốt (R5)
                account.getPrincipal(), account.getAnnualRate(), account.getTermDays());

        return outboxService.record(Topics.SAVINGS_EVENTS, EventTypes.SAVINGS_MATURITY_REMINDER,
                "maturity-reminder:" + accountId + ":" + milestone, "user:" + account.getUserId(),
                new SavingsMaturityReminderEvent(accountId, account.getUserId(), account.getPrincipal(),
                        expectedInterest, account.getMaturityDate(), daysLeft,
                        account.getMaturityOption().name(), product.isOnSale()));
    }
}