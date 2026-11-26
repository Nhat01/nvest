package vn.nvest.savings.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "nvest.auth.email-token")
public record EmailTokenProperties(Duration verificationTtl, Duration resetTtl, Duration cooldown) {

    public EmailTokenProperties {
        if (verificationTtl == null || verificationTtl.isZero() || verificationTtl.isNegative()) {
            verificationTtl = Duration.ofHours(24);
        }
        if (resetTtl == null || resetTtl.isZero() || resetTtl.isNegative()) {
            resetTtl = Duration.ofMinutes(30);
        }
        if (cooldown == null || cooldown.isNegative()) {
            cooldown = Duration.ofSeconds(60);
        }
    }
}
