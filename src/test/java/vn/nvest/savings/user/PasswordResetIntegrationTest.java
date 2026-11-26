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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class PasswordResetIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "S3cure-passw0rd";
    private static final String NEW_PASSWORD = "N3w-passw0rd";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailSender emailSender;

    @Autowired
    private UserTokenService userTokenService;

    private static String uniqueEmail() {
        return "reset-" + UUID.randomUUID() + "@test.local";
    }

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                        + "\",\"fullName\":\"Nguyen Van An\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions forgot(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"));
    }

    private String capturedResetToken() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce())
                .sendPasswordReset(anyString(), anyString(), captor.capture());
        return captor.getValue();
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

    private long userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE lower(email) = lower(?)", Long.class, email);
    }

    @Test
    @DisplayName("R77: forgot với email chưa đăng ký trả 204 và không gửi gì")
    void forgotForUnknownEmailDoesNothing() throws Exception {
        forgot(uniqueEmail()).andExpect(status().isNoContent());

        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("R78: forgot tạo token PASSWORD_RESET đúng TTL và gửi mail")
    void forgotCreatesResetTokenAndSendsEmail() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        long id = userId(email);

        forgot(email).andExpect(status().isNoContent());

        String rawToken = capturedResetToken();
        assertThat(rawToken).hasSize(43).matches("[A-Za-z0-9_-]+");

        var row = jdbc.queryForMap(
                "SELECT purpose, token_hash, used_at, issued_at, expires_at FROM user_tokens "
                        + "WHERE user_id = ? AND purpose = 'PASSWORD_RESET'",
                id);
        assertThat(row.get("purpose")).isEqualTo("PASSWORD_RESET");
        assertThat(row.get("token_hash")).isEqualTo(OpaqueTokens.sha256Hex(rawToken));
        assertThat(row.get("used_at")).isNull();
        Duration ttl = Duration.between(
                ((Timestamp) row.get("issued_at")).toInstant(),
                ((Timestamp) row.get("expires_at")).toInstant());
        assertThat(ttl.toSeconds()).isEqualTo(Duration.ofMinutes(30).toSeconds());
    }

    @Test
    @DisplayName("R78: reset đổi mật khẩu, thu hồi mọi refresh token với lý do PASSWORD_RESET")
    void resetChangesPasswordAndRevokesSessions() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        markEmailVerified(email);

        String loginBody = login(email, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String refreshToken = JsonPath.read(loginBody, "$.refreshToken");

        forgot(email).andExpect(status().isNoContent());
        String resetToken = capturedResetToken();

        reset(resetToken, NEW_PASSWORD).andExpect(status().isNoContent());

        login(email, PASSWORD).andExpect(status().isUnauthorized());
        login(email, NEW_PASSWORD).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject(
                "SELECT revoked_reason FROM refresh_tokens WHERE token_hash = ?",
                String.class, OpaqueTokens.sha256Hex(refreshToken)))
                .isEqualTo("PASSWORD_RESET");
    }

    @Test
    @DisplayName("R78: reset không tự đăng nhập, body rỗng")
    void resetDoesNotAutoLogin() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        forgot(email);
        String resetToken = capturedResetToken();

        reset(resetToken, NEW_PASSWORD)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("R78: link reset chỉ dùng được một lần")
    void resetIsSingleUse() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        forgot(email);
        String resetToken = capturedResetToken();

        reset(resetToken, NEW_PASSWORD).andExpect(status().isNoContent());
        reset(resetToken, "Another-passw0rd").andExpect(status().isBadRequest());

        // Mật khẩu của lần reset đầu vẫn là mật khẩu hiện tại.
        login(email, NEW_PASSWORD).andExpect(status().isOk());
        login(email, "Another-passw0rd").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R78: token reset hết hạn trả 400")
    void resetRejectsExpiredToken() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        forgot(email);
        String resetToken = capturedResetToken();

        jdbc.update("UPDATE user_tokens SET expires_at = now() - interval '1 hour' WHERE token_hash = ?",
                OpaqueTokens.sha256Hex(resetToken));

        reset(resetToken, NEW_PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R78: token EMAIL_VERIFICATION không dùng được để reset và không bị đánh dấu dùng")
    void resetRejectsTokenOfAnotherPurpose() throws Exception {
        long userId = createUserWithDeposit(0);
        String verificationToken = userTokenService.issue(
                userId, UserTokenPurpose.EMAIL_VERIFICATION, Duration.ofHours(24));

        reset(verificationToken, NEW_PASSWORD).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_tokens WHERE token_hash = ? AND used_at IS NOT NULL",
                Integer.class, OpaqueTokens.sha256Hex(verificationToken))).isZero();
    }

    @Test
    @DisplayName("R78: token reset không tồn tại trả 400 chung")
    void resetWithUnknownTokenReturns400() throws Exception {
        reset("khong-phai-token", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid or expired token"));
    }

    @Test
    @DisplayName("R78: mật khẩu mới phải đúng chính sách (>= 8 ký tự, <= 72 byte)")
    void resetEnforcesPasswordPolicy() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        forgot(email);
        String resetToken = capturedResetToken();

        reset(resetToken, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").exists());

        reset(resetToken, "ệ".repeat(30))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isNotEmpty());

        // Token vẫn còn dùng được vì request bị chặn trước khi chạm service.
        reset(resetToken, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("R77/R78: thiếu field hoặc JSON hỏng trả 400, không chạm service")
    void malformedRequestsReturn400() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());

        forgot("khong-phai-email")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());

        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").exists());

        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"\",\"newPassword\":\"N3w-passw0rd\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.token").exists());

        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R81: tài khoản bị người khác đăng ký trước lấy lại được bằng forgot + reset")
    void resetClaimsUnverifiedAccount() throws Exception {
        String email = uniqueEmail();
        // Mô phỏng người lạ đăng ký trước: tài khoản tồn tại nhưng chưa xác thực,
        // mật khẩu là của người lạ nên chủ hộp thư không đăng nhập được.
        register(email).andExpect(status().isCreated());
        long id = userId(email);
        assertThat(emailVerifiedAt(id)).isNull();
        login(email, PASSWORD).andExpect(status().isForbidden());

        forgot(email).andExpect(status().isNoContent());
        String resetToken = capturedResetToken();
        reset(resetToken, NEW_PASSWORD).andExpect(status().isNoContent());

        // R81: reset chứng minh quyền kiểm soát hộp thư nên cũng đánh dấu đã xác thực.
        assertThat(emailVerifiedAt(id)).isNotNull();
        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("R77: forgot trong cooldown 60 giây không gửi thêm mail, vẫn 204")
    void forgotWithinCooldownIsSilentlyIgnored() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        clearInvocations(emailSender);

        forgot(email).andExpect(status().isNoContent());
        forgot(email).andExpect(status().isNoContent());

        verify(emailSender, times(1)).sendPasswordReset(eq(email), anyString(), anyString());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_tokens WHERE user_id = ? AND purpose = 'PASSWORD_RESET'",
                Integer.class, userId(email))).isEqualTo(1);
    }

    @Test
    @DisplayName("R77: forgot cho tài khoản bị khóa trả 204 và không gửi mail")
    void forgotForLockedUserDoesNothing() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE lower(email) = lower(?)", email);
        clearInvocations(emailSender);

        forgot(email).andExpect(status().isNoContent());

        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("R77: gửi mail reset lỗi vẫn 204, token bị xoá nên gửi lại được ngay")
    void mailFailureKeeps204AndAllowsImmediateRetry() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        clearInvocations(emailSender);
        doThrow(new IllegalStateException("smtp down"))
                .doNothing()
                .when(emailSender).sendPasswordReset(anyString(), anyString(), anyString());

        forgot(email).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_tokens WHERE user_id = ? AND purpose = 'PASSWORD_RESET'",
                Integer.class, userId(email))).isZero();

        // Token lỗi đã bị xoá nên cooldown không chặn lần gửi lại.
        forgot(email).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_tokens WHERE user_id = ? AND purpose = 'PASSWORD_RESET'",
                Integer.class, userId(email))).isEqualTo(1);
    }
}
