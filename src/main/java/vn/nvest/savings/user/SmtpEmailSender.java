package vn.nvest.savings.user;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final MailProperties properties;

    public SmtpEmailSender(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    private void send(String to, MailMessages.Content content) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(to);
        message.setSubject(content.subject());
        message.setText(content.body());
        mailSender.send(message);
    }

    @Override
    public void sendVerification(String to, String fullName, String rawToken) {
        send(to, MailMessages.verification(properties, fullName, rawToken));
    }

    @Override
    public void sendPasswordReset(String to, String fullName, String rawToken) {
        send(to, MailMessages.passwordReset(properties, fullName, rawToken));
    }

    @Override
    public void sendMaturityReminder(String to, String fullName, long accountId, String message) {
        send(to, MailMessages.maturityReminder(fullName, accountId, message));
    }
}
