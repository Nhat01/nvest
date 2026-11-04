package vn.nvest.savings.savings;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaturityJob {

    private static final Logger log = LoggerFactory.getLogger(MaturityJob.class);
    private static final int MAX_PASSES = 10;

    /** due = số gói đã thử, settled = đã quyết toán, skipped = bỏ qua (đã xử lý/chưa đến hạn), failures = lỗi. */
    public record RunResult(int due, int settled, int skipped, int failures) {
    }

    private final SavingsAccountRepository accountRepository;
    private final MaturityService maturityService;
    private final Clock clock;

    public MaturityJob(SavingsAccountRepository accountRepository,
                       MaturityService maturityService,
                       Clock clock) {
        this.accountRepository = accountRepository;
        this.maturityService = maturityService;
        this.clock = clock;
    }

    /** R19, R49: 00:05 mỗi ngày (giờ Việt Nam), xử lý mọi gói có ngày đáo hạn <= hôm nay. */
    @Scheduled(cron = "${nvest.jobs.maturity.cron:0 5 0 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void runScheduled() {
        runFor(LocalDate.now(clock));
    }

    /** Quyết toán mọi gói đến hạn tính đến ngày asOf. Gọi thủ công được, thuận tiện cho test. */
    public RunResult runFor(LocalDate asOf) {
        Set<Long> attempted = new HashSet<>();
        int settled = 0;
        int skipped = 0;
        int failures = 0;

        for (int pass = 1; pass <= MAX_PASSES; pass++) {
            List<Long> dueIds = accountRepository
                    .findIdsByStatusMaturingOnOrBefore(AccountStatus.ACTIVE, asOf).stream()
                    .filter(id -> !attempted.contains(id))
                    .toList();
            if (dueIds.isEmpty()) {
                break;
            }
            for (Long accountId : dueIds) {
                attempted.add(accountId);
                try {
                    Optional<Settlement> result = maturityService.settle(accountId, asOf);   // mỗi gói một transaction
                    if (result.isPresent()) {
                        settled++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    failures++;
                    log.error("Maturity settlement failed for account {} (as of {})", accountId, asOf, e);
                }
            }
        }
        log.info("Maturity run as of {}: {} accounts tried, {} settled, {} skipped, {} failures",
                asOf, attempted.size(), settled, skipped, failures);
        return new RunResult(attempted.size(), settled, skipped, failures);
    }
}