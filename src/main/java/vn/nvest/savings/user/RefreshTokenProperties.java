package vn.nvest.savings.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "nvest.security.refresh-token")
public record RefreshTokenProperties(Duration ttl) {
    public RefreshTokenProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            ttl = Duration.ofDays(30);
        }
    }
}
