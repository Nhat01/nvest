package vn.nvest.savings.user;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "nvest.mail.enabled", havingValue = "true")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final MailProperties properties;

    public SmtpEmailSender(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    private void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

    @Override
    public void sendVerification(String to, String fullName, String rawToken) {
        send(to, "Xác thực email của bạn", """
                Xin chào %s,

                Hãy mở link sau để xác thực email:
                %s%s

                Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này.
                """.formatted(fullName, properties.verificationLink(), rawToken));
    }

    @Override
    public void sendPasswordReset(String to, String fullName, String rawToken) {
        send(to, "Đặt lại mật khẩu", """
                Xin chào %s,

                Hãy mở link sau để đặt lại mật khẩu:
                %s%s

                Nếu bạn không yêu cầu, hãy bỏ qua email này. Mật khẩu hiện tại vẫn dùng được.
                """.formatted(fullName, properties.resetLink(), rawToken));
    }

    @Override
    public void sendMaturityReminder(String to, String fullName, long accountId, String message) {
        send(to, "Nhắc đáo hạn gói tiết kiệm #" + accountId, """
                Xin chào %s,

                %s

                Trân trọng,
                Nvest Savings Platform
                """.formatted(fullName, message));
    }
}
