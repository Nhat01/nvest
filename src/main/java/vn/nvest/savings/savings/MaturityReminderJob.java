package vn.nvest.savings.savings;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaturityReminderJob {

    private static final Logger log = LoggerFactory.getLogger(MaturityReminderJob.class);

    /** candidates = gói đáo hạn trong 3 ngày tới; sent = sự kiện mới; skipped = đã nhắc rồi hoặc không còn hợp lệ. */
    public record RunResult(int candidates, int sent, int skipped, int failures) {
    }

    private final SavingsAccountRepository accountRepository;
    private final MaturityReminderService reminderService;
    private final Clock clock;

    public MaturityReminderJob(SavingsAccountRepository accountRepository,
                               MaturityReminderService reminderService, Clock clock) {
        this.accountRepository = accountRepository;
        this.reminderService = reminderService;
        this.clock = clock;
    }

    /** R82: 00:10 mỗi ngày (giờ Việt Nam), sau job đáo hạn lúc 00:05. */
    @Scheduled(cron = "${nvest.jobs.maturity-reminder.cron:0 10 0 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void runScheduled() {
        runFor(LocalDate.now(clock));
    }

    /** Gọi thủ công được, thuận tiện cho test. */
    public RunResult runFor(LocalDate today) {
        List<Long> ids = accountRepository.findIdsByStatusMaturingBetween(
                AccountStatus.ACTIVE, today, today.plusDays(MaturityReminderService.FIRST_REMINDER_DAYS));
        int sent = 0;
        int skipped = 0;
        int failures = 0;
        for (Long accountId : ids) {
            try {
                if (reminderService.remind(accountId, today)) {   // mỗi gói một transaction (R20)
                    sent++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                failures++;
                log.error("Maturity reminder failed for account {} (as of {})", accountId, today, e);
            }
        }
        log.info("Maturity reminders as of {}: {} candidates, {} sent, {} skipped, {} failures",
                today, ids.size(), sent, skipped, failures);
        return new RunResult(ids.size(), sent, skipped, failures);
    }
}