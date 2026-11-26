package vn.nvest.savings.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

/**
 * Test HTTP cho T3 (login trả refresh token) và T4 (endpoint refresh + xoay vòng).
 * Dùng MockMvc nên đi qua cả controller, validate, security và transaction như thật.
 */
class RefreshTokenApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "S3cure-passw0rd";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RefreshTokenProperties properties;

    private record TokenRow(String hash, UUID familyId, Instant issuedAt, Instant expiresAt,
                            String revokedReason) {
    }

    private static String uniqueEmail() {
        return "refresh-" + UUID.randomUUID() + "@test.local";
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String registerJson(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Refresh Test\"}";
    }

    private static String refreshJson(String rawToken) {
        return "{\"refreshToken\":\"" + rawToken + "\"}";
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isCreated());
        markEmailVerified(email);
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private ResultActions refresh(String rawToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(refreshJson(rawToken)));
    }

    private String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String loginAndGetRefreshToken(String email) throws Exception {
        String json = body(login(email).andExpect(status().isOk()));
        return JsonPath.read(json, "$.refreshToken");
    }

    private String refreshAndGetNewToken(String rawToken) throws Exception {
        String json = body(refresh(rawToken).andExpect(status().isOk()));
        return JsonPath.read(json, "$.refreshToken");
    }

    private TokenRow rowOf(String rawToken) {
        return jdbc.queryForObject("""
                SELECT token_hash, family_id, issued_at, expires_at, revoked_reason
                  FROM refresh_tokens
                 WHERE token_hash = ?
                """, (rs, rowNum) -> new TokenRow(
                rs.getString("token_hash"),
                rs.getObject("family_id", UUID.class),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getString("revoked_reason")), sha256Hex(rawToken));
    }

    private long countRowsOf(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Long.class, userId);
    }

    private long userIdOf(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    // ---------- T3: login trả refresh token ----------

    @Test
    @DisplayName("T3: login trả thêm refresh token và chỉ ghi đúng một dòng vào DB")
    void loginReturnsRefreshTokenAndStoresExactlyOneRow() throws Exception {
        String email = uniqueEmail();
        register(email);

        String json = body(login(email)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshExpiresIn").isNumber()));

        String rawRefresh = JsonPath.read(json, "$.refreshToken");
        long refreshExpiresIn = ((Number) JsonPath.read(json, "$.refreshExpiresIn")).longValue();
        long expectedTtl = properties.ttl().toSeconds();

        assertThat(rawRefresh)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");
        assertThat(refreshExpiresIn).isEqualTo(expectedTtl);
        assertThat(countRowsOf(userIdOf(email))).isEqualTo(1);

        TokenRow row = rowOf(rawRefresh);
        assertThat(row.hash()).isEqualTo(sha256Hex(rawRefresh));
        assertThat(row.revokedReason()).isNull();
        assertThat(Duration.between(row.issuedAt(), row.expiresAt()).toSeconds()).isEqualTo(expectedTtl);
    }

    @Test
    @DisplayName("T3: mỗi lần login tạo một family riêng, không đá phiên cũ")
    void eachLoginCreatesItsOwnFamily() throws Exception {
        String email = uniqueEmail();
        register(email);

        String first = loginAndGetRefreshToken(email);
        String second = loginAndGetRefreshToken(email);

        assertThat(rowOf(first).familyId()).isNotEqualTo(rowOf(second).familyId());
        assertThat(countRowsOf(userIdOf(email))).isEqualTo(2);
    }

    // ---------- T4: refresh + xoay vòng ----------

    @Test
    @DisplayName("T4: refresh cấp cặp token mới, token cũ bị ROTATED, token mới dùng được")
    void refreshRotatesToken() throws Exception {
        String email = uniqueEmail();
        register(email);
        String oldToken = loginAndGetRefreshToken(email);

        String json = body(refresh(oldToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshExpiresIn").isNumber()));

        String newToken = JsonPath.read(json, "$.refreshToken");
        long refreshExpiresIn = ((Number) JsonPath.read(json, "$.refreshExpiresIn")).longValue();

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(refreshExpiresIn).isPositive().isLessThanOrEqualTo(properties.ttl().toSeconds());

        assertThat(rowOf(oldToken).revokedReason()).isEqualTo(RevokedReason.ROTATED.name());
        assertThat(rowOf(newToken).revokedReason()).isNull();
        assertThat(rowOf(newToken).familyId()).isEqualTo(rowOf(oldToken).familyId());

        // Token mới phải dùng được ngay
        assertThat(refreshAndGetNewToken(newToken)).isNotBlank();
    }

    @Test
    @DisplayName("T4: xoay 3 lần liên tiếp vẫn giữ family_id và expires_at tuyệt đối")
    void threeRotationsKeepFamilyAndAbsoluteExpiry() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = loginAndGetRefreshToken(email);

        TokenRow first = rowOf(token);
        for (int i = 0; i < 3; i++) {
            token = refreshAndGetNewToken(token);
        }
        TokenRow last = rowOf(token);

        assertThat(last.familyId()).isEqualTo(first.familyId());
        assertThat(last.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(last.revokedReason()).isNull();
    }

    @Test
    @DisplayName("T4: thiếu hoặc rỗng refreshToken trả 400")
    void missingOrBlankRefreshTokenIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.refreshToken").exists());

        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson("   ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T4: token rác trả 401 với thông báo chung, không lộ lý do")
    void unknownRefreshTokenIsUnauthorizedWithGenericMessage() throws Exception {
        String body = body(refresh("not-a-real-token")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)));

        assertThat(body)
                .contains("Invalid refresh token")
                .doesNotContain("not-a-real-token");
    }

    // ---------- T5: dùng lại token đã xoay ----------

    @Test
    @DisplayName("T5: dùng lại token cũ trả 401 và thu hồi cả family, phiên khác vẫn sống")
    void reuseRevokesWholeFamilyButNotOtherSessions() throws Exception {
        String email = uniqueEmail();
        register(email);
        String sessionA = loginAndGetRefreshToken(email);
        String sessionB = loginAndGetRefreshToken(email);

        String a2 = refreshAndGetNewToken(sessionA);

        // Dùng lại token đã xoay
        refresh(sessionA).andExpect(status().isUnauthorized());

        // Token mới nhất của family A cũng bị thu hồi ngay
        refresh(a2).andExpect(status().isUnauthorized());
        assertThat(rowOf(a2).revokedReason()).isEqualTo(RevokedReason.REUSE_DETECTED.name());

        // Phiên B không liên quan vẫn hoạt động
        refresh(sessionB).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T5: việc thu hồi family khi phát hiện dùng lại phải được COMMIT, không bị rollback")
    void reuseRevocationIsPersistedEvenThoughRequestFails() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = loginAndGetRefreshToken(email);
        String rotated = refreshAndGetNewToken(token);

        // Request thất bại (401) nhưng UPDATE revoked_reason phải còn trong DB
        refresh(token).andExpect(status().isUnauthorized());

        assertThat(rowOf(token).revokedReason()).isEqualTo(RevokedReason.ROTATED.name());
        assertThat(rowOf(rotated).revokedReason()).isEqualTo(RevokedReason.REUSE_DETECTED.name());
    }

    // ---------- T7: trường hợp biên ----------

    @Test
    @DisplayName("T7: token hết hạn trả 401 và không bị đánh dấu revoked")
    void expiredTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = loginAndGetRefreshToken(email);

        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
                sha256Hex(token));

        refresh(token).andExpect(status().isUnauthorized());
        assertThat(rowOf(token).revokedReason()).isNull();
    }

    @Test
    @DisplayName("T7: user bị khóa không refresh được và family bị thu hồi USER_LOCKED")
    void lockedUserRefreshIsRejectedAndFamilyRevoked() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = loginAndGetRefreshToken(email);

        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", userIdOf(email));

        refresh(token).andExpect(status().isUnauthorized());
        assertThat(rowOf(token).revokedReason()).isEqualTo(RevokedReason.USER_LOCKED.name());
    }

    @Test
    @DisplayName("T7: user bị xóa thì token bị xóa theo (ON DELETE CASCADE) và refresh trả 401")
    void deletedUserRefreshIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = loginAndGetRefreshToken(email);

        // wallets không có ON DELETE CASCADE nên phải xóa ví trước;
        // refresh_tokens thì có, nên xóa user là token tự mất.
        long userId = userIdOf(email);
        jdbc.update("DELETE FROM wallets WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Long.class, sha256Hex(token));
        assertThat(remaining).isZero();

        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("T7: body rỗng hoặc JSON hỏng trả 400")
    void unreadableBodyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }
}
