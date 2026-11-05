package vn.nvest.savings.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    /** examined = số giao dịch được xét; skipped = đã được xử lý bởi luồng khác; failures = lỗi khi đối soát. */
    public record RunResult(int examined, int resolved, int stillOpen, int skipped, int failures) {
    }

    private final PaymentTransactionRepository paymentRepository;
    private final ReconciliationService reconciliationService;
    private final Clock clock;

    public ReconciliationJob(PaymentTransactionRepository paymentRepository,
                             ReconciliationService reconciliationService,
                             Clock clock) {
        this.paymentRepository = paymentRepository;
        this.reconciliationService = reconciliationService;
        this.clock = clock;
    }

    /** R60: mỗi 5 phút. */
    @Scheduled(cron = "${nvest.jobs.reconciliation.cron:0 */5 * * * *}", zone = "Asia/Ho_Chi_Minh")
    public void runScheduled() {
        run();
    }

    public RunResult run() {
        Instant now = clock.instant();
        List<Long> ids = new ArrayList<>(paymentRepository.findOpenIdsCreatedBefore(
                PaymentDirection.DEPOSIT, List.of(PaymentStatus.PENDING),
                now.minus(ReconciliationService.DEPOSIT_PENDING_TIMEOUT)));
        ids.addAll(paymentRepository.findOpenIdsCreatedBefore(
                PaymentDirection.WITHDRAW, List.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN),
                now.minus(ReconciliationService.WITHDRAWAL_RECHECK_AFTER)));

        int resolved = 0;
        int stillOpen = 0;
        int skipped = 0;
        int failures = 0;
        for (Long paymentId : ids) {
            try {
                switch (reconciliationService.reconcile(paymentId)) {
                    case RESOLVED -> resolved++;
                    case STILL_OPEN -> stillOpen++;
                    case ALREADY_SETTLED -> skipped++;
                }
            } catch (Exception e) {
                failures++;
                log.error("Reconciliation failed for payment {}", paymentId, e);
            }
        }
        log.info("Reconciliation: {} examined, {} resolved, {} still open, {} skipped, {} failures",
                ids.size(), resolved, stillOpen, skipped, failures);
        return new RunResult(ids.size(), resolved, stillOpen, skipped, failures);
    }
}