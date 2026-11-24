package vn.nvest.savings.user;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Giới hạn số lần đăng nhập sai. Đếm theo cặp (địa chỉ IP, email) để không khóa oan
 * người dùng khác, và tự mở khóa sau window.
 */
@ConfigurationProperties(prefix = "nvest.security.login-rate-limit")
public record LoginRateLimitProperties(boolean enabled, int maxFailures, Duration window) {

    public LoginRateLimitProperties {
        if (maxFailures <= 0) {
            maxFailures = 5;
        }
        if (window == null || window.isZero() || window.isNegative()) {
            window = Duration.ofMinutes(15);
        }
    }
}
