package vn.nvest.savings.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật @Scheduled. Tắt được bằng nvest.scheduling.enabled=false (dùng trong test). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "nvest.scheduling.enable", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
