package vn.nvest.savings.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "nvest.mail.enabled", havingValue = "false",
        matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final MailProperties properties;

    public LoggingEmailSender(MailProperties properties) {
        this.properties = properties;
    }

    @Override
    public void sendVerification(String to, String fullName, String rawToken) {
        log.info("Verification email for {} <{}>: {}{}",
                fullName, to, properties.verificationLink(), rawToken);
    }

    @Override
    public void sendPasswordReset(String to, String fullName, String rawToken) {
        log.info("Password reset email for {} <{}>: {}{}",
                fullName, to, properties.resetLink(), rawToken);
    }
}
