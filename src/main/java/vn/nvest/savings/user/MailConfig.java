package vn.nvest.savings.user;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

// Chỉ tạo đúng một EmailSender thật khi bật mail.
// provider=smtp (mặc định): JavaMail, dùng cho local/Mailpit hoặc server trả tiền.
// provider=brevo: gọi HTTPS 443, dùng được trên Render free (SMTP bị chặn).
@Configuration
@ConditionalOnProperty(name = "nvest.mail.enabled", havingValue = "true")
@EnableConfigurationProperties(BrevoProperties.class)
public class MailConfig {

    @Bean
    @ConditionalOnProperty(name = "nvest.mail.provider", havingValue = "smtp", matchIfMissing = true)
    public EmailSender smtpEmailSender(JavaMailSender mailSender, MailProperties properties) {
        return new SmtpEmailSender(mailSender, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "nvest.mail.provider", havingValue = "brevo")
    public EmailSender brevoEmailSender(RestClient.Builder builder, MailProperties properties,
            BrevoProperties brevo) {
        return new BrevoEmailSender(builder, properties, brevo);
    }
}
