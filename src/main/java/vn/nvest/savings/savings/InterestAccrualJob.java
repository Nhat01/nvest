package vn.nvest.savings.savings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Component
public class InterestAccrualJob {

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualJob.class);

    /** Kết quả một lần chạy: số gói đã xét, số dòng lãi mới, số gói lỗi. */
    public record RunResult(int accounts, int daysCreated, int failures) {
    }

    private final SavingsAccountRepository accountRepository;
    private final InterestAccrualService accrualService;
    private final Clock clock;

    public InterestAccrualJob(SavingsAccountRepository accountRepository,
                              InterestAccrualService accrualService,
                              Clock clock) {
        this.accountRepository = accountRepository;
        this.accrualService = accrualService;
        this.clock = clock;
    }

    /** R40: 00:05 mỗi ngày (giờ Việt Nam), ghi lãi đến hết hôm qua. */
    @Scheduled(cron = "${nvest.jobs.accrual.cron:0 5 0 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void runScheduled() {
        runFor(LocalDate.now(clock).minusDays(1));
    }

    /** Ghi lãi cho mọi gói đang chạy đến hết ngày asOf. Gọi thủ công được, thuận tiện cho test. */
    public RunResult runFor(LocalDate asOf) {
        List<Long> accountIds = accountRepository.findIdsByStatusStartedOnOrBefore(AccountStatus.ACTIVE, asOf);

        int daysCreated = 0;
        int failures = 0;
        for (Long accountId : accountIds) {
            try {
                daysCreated += accrualService.accrueAccount(accountId, asOf);   // mỗi gói một transaction
            } catch (Exception e) {
                failures++;
                log.error("Interest accrual failed for account {} (as of {})", accountId, asOf, e);
            }
        }
        log.info("Interest accrual as of {}: {} accounts, {} new rows, {} failures",
                asOf, accountIds.size(), daysCreated, failures);
        return new RunResult(accountIds.size(), daysCreated, failures);
    }
}
