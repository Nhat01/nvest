package vn.nvest.savings.user;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

// Gửi mail qua Brevo HTTP API (port 443) thay vì SMTP.
// Render free chặn outbound port 25/465/587 nên SMTP không dùng được ở đó.
public class BrevoEmailSender implements EmailSender {

    private final RestClient client;
    private final MailProperties properties;

    public BrevoEmailSender(RestClient.Builder builder, MailProperties properties, BrevoProperties brevo) {
        if (brevo.apiKey() == null || brevo.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "NVEST_MAIL_PROVIDER=brevo nhưng BREVO_API_KEY chưa được đặt");
        }
        this.properties = properties;
        this.client = builder
                .baseUrl(brevo.baseUrl())
                .defaultHeader("api-key", brevo.apiKey())
                .build();
    }

    private void send(String to, String fullName, MailMessages.Content content) {
        Payload payload = new Payload(
                new Sender(properties.fromName(), properties.from()),
                List.of(new Recipient(to, fullName)),
                content.subject(),
                content.body());
        // Lỗi 4xx/5xx ném RestClientResponseException để Kafka retry rồi đẩy sang DLT.
        client.post()
                .uri("/v3/smtp/email")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void sendVerification(String to, String fullName, String rawToken) {
        send(to, fullName, MailMessages.verification(properties, fullName, rawToken));
    }

    @Override
    public void sendPasswordReset(String to, String fullName, String rawToken) {
        send(to, fullName, MailMessages.passwordReset(properties, fullName, rawToken));
    }

    @Override
    public void sendMaturityReminder(String to, String fullName, long accountId, String message) {
        send(to, fullName, MailMessages.maturityReminder(fullName, accountId, message));
    }

    private record Sender(String name, String email) {
    }

    private record Recipient(String email, String name) {
    }

    private record Payload(Sender sender, List<Recipient> to, String subject, String textContent) {
    }
}
