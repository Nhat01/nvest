package vn.nvest.savings.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Xoá token xác thực/đặt lại mật khẩu đã hết hạn từ lâu. Token hết hạn vốn không
 * dùng được, nên xoá chỉ để bảng không phình mãi.
 *
 * Transaction nằm ở UserTokenService: @Scheduled gọi run() trong cùng class nên
 * nếu đặt @Transactional ở đây, proxy sẽ bị bỏ qua và câu DELETE không có transaction.
 */
@Component
public class UserTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(UserTokenCleanupJob.class);
    private static final Duration RETENTION = Duration.ofDays(30);

    private final UserTokenService userTokenService;
    private final Clock clock;

    public UserTokenCleanupJob(UserTokenService userTokenService, Clock clock) {
        this.userTokenService = userTokenService;
        this.clock = clock;
    }

    @Scheduled(cron = "${nvest.jobs.user-token-cleanup.cron:0 30 3 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void runScheduled() {
        run();
    }

    public int run() {
        Instant cutoff = clock.instant().minus(RETENTION);
        int deleted = userTokenService.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} user tokens expired before {}", deleted, cutoff);
        }
        return deleted;
    }
}
