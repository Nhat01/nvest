package vn.nvest.savings.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import com.jayway.jsonpath.JsonPath;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class EmailVerificationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "S3cure-passw0rd";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailSender emailSender;

    @Autowired
    private UserTokenService userTokenService;

    private static String uniqueEmail() {
        return "verify-" + UUID.randomUUID() + "@test.local";
    }

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                        + "\",\"fullName\":\"Nguyen Van An\"}"));
    }

    private ResultActions verifyEmail(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"));
    }

    private ResultActions resend(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/resend-verification").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private long registerAndGetId(String email) throws Exception {
        String body = register(email).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Token gốc chỉ tồn tại trong lời gọi EmailSender, nên test phải bắt ở đó. */
    private List<String> capturedVerificationTokens() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce())
                .sendVerification(anyString(), anyString(), captor.capture());
        return captor.getAllValues();
    }

    private Instant emailVerifiedAt(long userId) {
        List<Instant> values = jdbc.query("SELECT email_verified_at FROM users WHERE id = ?",
                (rs, rowNum) -> {
                    var timestamp = rs.getTimestamp("email_verified_at");
                    return timestamp == null ? null : timestamp.toInstant();
                },
                userId);
        return values.isEmpty() ? null : values.getFirst();
    }

    private int countTokens(long userId, String purpose) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM user_tokens WHERE user_id = ? AND purpose = ?",
                Integer.class, userId, purpose);
    }

    private Instant usedAt(String rawToken) {
        return jdbc.queryForObject("SELECT used_at FROM user_tokens WHERE token_hash = ?",
                (rs, rowNum) -> {
                    var timestamp = rs.getTimestamp("used_at");
                    return timestamp == null ? null : timestamp.toInstant();
                },
                OpaqueTokens.sha256Hex(rawToken));
    }

    @Test
    @DisplayName("T5: đăng ký tạo user chưa xác thực và gửi 1 token EMAIL_VERIFICATION")
    void registerCreatesUnverifiedUserAndSendsEmail() throws Exception {
        String email = uniqueEmail();

        long userId = registerAndGetId(email);

        assertThat(emailVerifiedAt(userId)).isNull();
        assertThat(countTokens(userId, "EMAIL_VERIFICATION")).isEqualTo(1);
        assertThat(usedAt(capturedVerificationTokens().getFirst())).isNull();

        String rawToken = capturedVerificationTokens().getFirst();
        assertThat(rawToken).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(jdbc.queryForObject(
                "SELECT token_hash FROM user_tokens WHERE user_id = ?", String.class, userId))
                .isEqualTo(OpaqueTokens.sha256Hex(rawToken));
    }

    @Test
    @DisplayName("T5: verify đúng token đánh dấu email_verified_at và dùng hết token")
    void verifyMarksEmailVerifiedAndConsumesToken() throws Exception {
        String email = uniqueEmail();
        long userId = registerAndGetId(email);
        String rawToken = capturedVerificationTokens().getFirst();

        verifyEmail(rawToken).andExpect(status().isNoContent());

        assertThat(emailVerifiedAt(userId)).isNotNull();
        assertThat(usedAt(rawToken)).isNotNull();
    }

    @Test
    @DisplayName("T5: verify token lạ trả 400 với thông báo chung")
    void verifyWithUnknownTokenReturns400() throws Exception {
        verifyEmail("khong-phai-token-that")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid or expired token"));
    }

    @Test
    @DisplayName("T5: verify lần hai cùng token trả 400")
    void verifyTwiceReturns400() throws Exception {
        registerAndGetId(uniqueEmail());
        String rawToken = capturedVerificationTokens().getFirst();

        verifyEmail(rawToken).andExpect(status().isNoContent());
        verifyEmail(rawToken).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T5: token PASSWORD_RESET không dùng được để xác thực email và không bị đánh dấu đã dùng")
    void verifyRejectsTokenOfAnotherPurpose() throws Exception {
        long userId = createUserWithDeposit(0, false);
        String resetToken = userTokenService.issue(
                userId, UserTokenPurpose.PASSWORD_RESET, Duration.ofMinutes(30));

        verifyEmail(resetToken).andExpect(status().isBadRequest());

        assertThat(usedAt(resetToken)).isNull();
        assertThat(emailVerifiedAt(userId)).isNull();
    }

    @Test
    @DisplayName("T5: resend với email chưa đăng ký trả 204 và không gửi gì")
    void resendForUnknownEmailDoesNothing() throws Exception {
        resend(uniqueEmail()).andExpect(status().isNoContent());

        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("T5: resend với email sai định dạng trả 400")
    void resendWithInvalidEmailReturns400() throws Exception {
        resend("not-an-email").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T5: resend khi email đã xác thực trả 204 và không gửi gì")
    void resendForVerifiedUserDoesNothing() throws Exception {
        String email = uniqueEmail();
        registerAndGetId(email);
        verifyEmail(capturedVerificationTokens().getFirst()).andExpect(status().isNoContent());
        clearInvocations(emailSender);

        resend(email).andExpect(status().isNoContent());

        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("T5: resend ngay sau đăng ký bị cooldown chặn, vẫn trả 204")
    void resendWithinCooldownIsSilentlyIgnored() throws Exception {
        String email = uniqueEmail();
        registerAndGetId(email);

        resend(email).andExpect(status().isNoContent());

        verify(emailSender, times(1))
                .sendVerification(eq(email), anyString(), anyString());
    }

    @Test
    @DisplayName("T5: resend sau cooldown phát token mới, token cũ chết, token mới verify được")
    void resendAfterCooldownIssuesFreshTokenAndInvalidatesOld() throws Exception {
        String email = uniqueEmail();
        long userId = registerAndGetId(email);
        String oldToken = capturedVerificationTokens().getFirst();

        // Đẩy issued_at lùi 5 phút để cooldown 60 giây trôi qua.
        jdbc.update("UPDATE user_tokens SET issued_at = now() - interval '5 minutes' WHERE user_id = ?",
                userId);

        resend(email).andExpect(status().isNoContent());

        List<String> tokens = capturedVerificationTokens();
        assertThat(tokens).hasSize(2);
        String newToken = tokens.getLast();

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(usedAt(oldToken)).isNotNull();
        assertThat(usedAt(newToken)).isNull();
        assertThat(countTokens(userId, "EMAIL_VERIFICATION")).isEqualTo(2);

        verifyEmail(oldToken).andExpect(status().isBadRequest());
        verifyEmail(newToken).andExpect(status().isNoContent());
        assertThat(emailVerifiedAt(userId)).isNotNull();
    }

    @Test
    @DisplayName("T5: gửi mail lỗi không làm đăng ký thất bại và xoá token để resend được ngay")
    void registerStillSucceedsAndDiscardsTokenWhenEmailFails() throws Exception {
        String email = uniqueEmail();
        // Lần đăng ký ném lỗi SMTP; lần resend sau đó thành công.
        doThrow(new IllegalStateException("smtp down"))
                .doNothing()
                .when(emailSender).sendVerification(anyString(), anyString(), anyString());

        long userId = registerAndGetId(email);

        assertThat(emailVerifiedAt(userId)).isNull();
        // Token vừa tạo đã bị xoá nên user_tokens trống và cooldown không chặn.
        assertThat(countTokens(userId, "EMAIL_VERIFICATION")).isZero();

        resend(email).andExpect(status().isNoContent());

        assertThat(countTokens(userId, "EMAIL_VERIFICATION")).isEqualTo(1);
        // Token của lần gửi lỗi đã bị xoá, nên dùng token của lần resend (lần gọi sau cùng).
        verifyEmail(capturedVerificationTokens().getLast()).andExpect(status().isNoContent());
        assertThat(emailVerifiedAt(userId)).isNotNull();
    }
}
