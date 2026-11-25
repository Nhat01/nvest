package vn.nvest.savings.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

/**
 * Test cho tầng dịch vụ refresh token: sinh, băm, xoay vòng, thu hồi.
 * Chưa đụng tới controller; các test HTTP sẽ nằm ở T4/T6.
 */
class RefreshTokenServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenProperties properties;

    private record TokenRow(String hash, UUID familyId, Instant issuedAt, Instant expiresAt,
                            String revokedReason) {
    }

    private User newUser() {
        long userId = createUserWithDeposit(0);
        return userRepository.findById(userId).orElseThrow();
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

    @Test
    @DisplayName("issue: lưu hash SHA-256, không lưu token gốc, hạn đúng cấu hình")
    void issueStoresHashedTokenWithConfiguredExpiry() {
        User user = newUser();

        TokenPair pair = refreshTokenService.issue(user);
        long ttlSeconds = properties.ttl().toSeconds();

        assertThat(pair.accessToken().value()).isNotBlank();
        assertThat(pair.accessToken().expiresInSeconds()).isPositive();
        assertThat(pair.refreshToken())
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");
        assertThat(pair.refreshExpiresIn()).isEqualTo(ttlSeconds);

        TokenRow row = rowOf(pair.refreshToken());
        assertThat(row.hash())
                .isNotEqualTo(pair.refreshToken())
                .isEqualTo(sha256Hex(pair.refreshToken()));
        assertThat(row.revokedReason()).isNull();
        assertThat(Duration.between(row.issuedAt(), row.expiresAt()).toSeconds()).isEqualTo(ttlSeconds);
    }

    @Test
    @DisplayName("issue: mỗi lần đăng nhập có family riêng")
    void eachLoginCreatesItsOwnFamily() {
        User user = newUser();

        var first = refreshTokenService.issue(user);
        var second = refreshTokenService.issue(user);

        assertThat(rowOf(first.refreshToken()).familyId())
                .isNotEqualTo(rowOf(second.refreshToken()).familyId());
    }

    @Test
    @DisplayName("rotate: cấp token mới cùng family, thu hồi token cũ, không kéo dài hạn")
    void rotateIssuesNewTokenAndRevokesOld() {
        User user = newUser();
        var first = refreshTokenService.issue(user);

        var second = refreshTokenService.rotate(first.refreshToken()).orElseThrow();

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.accessToken().value()).isNotBlank();
        assertThat(second.refreshExpiresIn()).isPositive();

        TokenRow firstRow = rowOf(first.refreshToken());
        TokenRow secondRow = rowOf(second.refreshToken());

        assertThat(firstRow.revokedReason()).isEqualTo(RevokedReason.ROTATED.name());
        assertThat(secondRow.revokedReason()).isNull();
        assertThat(secondRow.familyId()).isEqualTo(firstRow.familyId());
        assertThat(secondRow.expiresAt()).isEqualTo(firstRow.expiresAt());
    }

    @Test
    @DisplayName("rotate: hạn tuyệt đối, xoay nhiều lần vẫn giữ nguyên expires_at")
    void rotationKeepsAbsoluteExpiry() {
        User user = newUser();
        var token = refreshTokenService.issue(user);
        Instant originalExpiry = rowOf(token.refreshToken()).expiresAt();

        for (int i = 0; i < 3; i++) {
            token = refreshTokenService.rotate(token.refreshToken()).orElseThrow();
        }

        assertThat(rowOf(token.refreshToken()).expiresAt()).isEqualTo(originalExpiry);
    }

    @Test
    @DisplayName("rotate: token rác trả rỗng")
    void unknownTokenIsRejected() {
        assertThat(refreshTokenService.rotate("not-a-real-token")).isEmpty();
    }

    @Test
    @DisplayName("rotate: token hết hạn bị từ chối")
    void expiredTokenIsRejected() {
        User user = newUser();
        var pair = refreshTokenService.issue(user);

        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
                sha256Hex(pair.refreshToken()));

        assertThat(refreshTokenService.rotate(pair.refreshToken())).isEmpty();
    }

    @Test
    @DisplayName("rotate: dùng lại token cũ thu hồi cả family, không ảnh hưởng phiên khác")
    void reuseRevokesWholeFamilyButNotOtherSessions() {
        User user = newUser();
        var sessionA = refreshTokenService.issue(user);
        var sessionB = refreshTokenService.issue(user);

        var a2 = refreshTokenService.rotate(sessionA.refreshToken()).orElseThrow();

        // Dùng lại token đã xoay: phải bị từ chối và thu hồi cả family A
        assertThat(refreshTokenService.rotate(sessionA.refreshToken())).isEmpty();
        assertThat(refreshTokenService.rotate(a2.refreshToken())).isEmpty();

        assertThat(rowOf(a2.refreshToken()).revokedReason())
                .isEqualTo(RevokedReason.REUSE_DETECTED.name());

        // Phiên B không liên quan vẫn dùng được
        assertThat(refreshTokenService.rotate(sessionB.refreshToken())).isPresent();
    }

    @Test
    @DisplayName("rotate: user bị khóa thì từ chối và thu hồi family")
    void lockedUserCannotRotate() {
        User user = newUser();
        var pair = refreshTokenService.issue(user);

        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", user.getId());

        assertThat(refreshTokenService.rotate(pair.refreshToken())).isEmpty();
        assertThat(rowOf(pair.refreshToken()).revokedReason())
                .isEqualTo(RevokedReason.USER_LOCKED.name());
    }

    @Test
    @DisplayName("revoke: chỉ thu hồi đúng phiên được chỉ định")
    void revokeEndsOnlyThatSession() {
        User user = newUser();
        var sessionA = refreshTokenService.issue(user);
        var sessionB = refreshTokenService.issue(user);

        refreshTokenService.revoke(sessionA.refreshToken(), false);

        assertThat(rowOf(sessionA.refreshToken()).revokedReason())
                .isEqualTo(RevokedReason.LOGOUT.name());
        assertThat(rowOf(sessionB.refreshToken()).revokedReason()).isNull();

        assertThat(refreshTokenService.rotate(sessionA.refreshToken())).isEmpty();
        assertThat(refreshTokenService.rotate(sessionB.refreshToken())).isPresent();
    }

    @Test
    @DisplayName("revoke: allDevices thu hồi mọi phiên của user")
    void revokeAllDevicesEndsEverySession() {
        User user = newUser();
        var first = refreshTokenService.issue(user);
        var second = refreshTokenService.issue(user);
        var third = refreshTokenService.issue(user);

        refreshTokenService.revoke(first.refreshToken(), true);

        for (var pair : java.util.List.of(first, second, third)) {
            assertThat(rowOf(pair.refreshToken()).revokedReason())
                    .isEqualTo(RevokedReason.LOGOUT_ALL.name());
            assertThat(refreshTokenService.rotate(pair.refreshToken())).isEmpty();
        }
    }

    @Test
    @DisplayName("revoke: token không tồn tại thì im lặng, không ném lỗi")
    void revokeUnknownTokenIsSilent() {
        assertThatCode(() -> refreshTokenService.revoke("not-a-real-token", false))
                .doesNotThrowAnyException();
    }
}
