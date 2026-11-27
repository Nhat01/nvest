package vn.nvest.savings.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class BrevoEmailSenderTest {

    private static final MailProperties PROPERTIES = new MailProperties(
            "no-reply@nvest.test",
            "Nvest Savings",
            "http://localhost:3000/verify-email?token=",
            "http://localhost:3000/reset-password?token=");

    private MockRestServiceServer server;
    private BrevoEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new BrevoEmailSender(builder, PROPERTIES,
                new BrevoProperties("test-api-key", "https://api.brevo.com"));
    }

    @Test
    @DisplayName("gửi mail xác thực đúng endpoint, header và nội dung")
    void sendsVerificationEmail() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "test-api-key"))
                .andExpect(jsonPath("$.sender.email").value("no-reply@nvest.test"))
                .andExpect(jsonPath("$.sender.name").value("Nvest Savings"))
                .andExpect(jsonPath("$.to[0].email").value("an@example.com"))
                .andExpect(jsonPath("$.to[0].name").value("Nguyen Van An"))
                .andExpect(jsonPath("$.subject").value("Xác thực email của bạn"))
                .andExpect(jsonPath("$.textContent").value(
                        containsString("http://localhost:3000/verify-email?token=raw-token")))
                .andRespond(withStatus(HttpStatus.CREATED));

        sender.sendVerification("an@example.com", "Nguyen Van An", "raw-token");

        server.verify();
    }

    @Test
    @DisplayName("nhắc đáo hạn có số gói trong subject và nội dung trong body")
    void sendsMaturityReminder() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.subject").value("Nhắc đáo hạn gói tiết kiệm #42"))
                .andExpect(jsonPath("$.textContent").value(
                        containsString("Gói của bạn đáo hạn trong 3 ngày")))
                .andRespond(withStatus(HttpStatus.CREATED));

        sender.sendMaturityReminder("an@example.com", "Nguyen Van An", 42L,
                "Gói của bạn đáo hạn trong 3 ngày");

        server.verify();
    }

    @Test
    @DisplayName("Brevo trả lỗi thì ném exception để Kafka retry")
    void throwsWhenBrevoRejects() {
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> sender.sendPasswordReset("an@example.com", "Nguyen Van An", "token"))
                .isInstanceOf(RestClientResponseException.class);
    }

    @Test
    @DisplayName("thiếu API key thì báo lỗi ngay khi khởi tạo")
    void rejectsMissingApiKey() {
        assertThatThrownBy(() -> new BrevoEmailSender(RestClient.builder(), PROPERTIES,
                new BrevoProperties("  ", "https://api.brevo.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BREVO_API_KEY");
    }
}
