package vn.nvest.savings.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nvest.mail")
public record MailProperties(String from, String verificationLink, String resetLink) {

    public MailProperties {
        if (from == null || from.isBlank()) {
            from = "no-reply@nvest.local";
        }
        if (verificationLink == null || verificationLink.isBlank()) {
            verificationLink = "http://localhost:3000/verify-email?token=";
        }
        if (resetLink == null || resetLink.isBlank()) {
            resetLink = "http://localhost:3000/reset-password?token=";
        }
    }
}
