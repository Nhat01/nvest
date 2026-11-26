package vn.nvest.savings.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class UserTokenServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private UserTokenService userTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailTokenProperties properties;

    @Autowired
    private UserTokenCleanupJob cleanupJob;

    private record Row(String tokenHash, String purpose, Instant issuedAt, Instant expiresAt,
                       Instant usedAt) {
    }

    private User newUser() {
        long userId = createUserWithDeposit(0);
        return userRepository.findById(userId).orElseThrow();
    }

    private Optional<Row> rowOf(String rawToken) {
        return jdbc.query("""
                        SELECT token_hash, purpose, issued_at, expires_at, used_at
                          FROM user_tokens
                         WHERE token_hash = ?
                        """,
                (rs, rowNum) -> new Row(
                        rs.getString("token_hash"),
                        rs.getString("purpose"),
                        rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant()),
                OpaqueTokens.sha256Hex(rawToken))
                .stream().findFirst();
    }

    @Test
    @DisplayName("issue: lưu hash SHA-256, purpose và TTL đúng, không lưu token gốc")
    void issueStoresHashPurposeAndTtl() {
        User user = newUser();
        Duration ttl = properties.verificationTtl();

        String rawToken = userTokenService.issue(user.getId(), UserTokenPurpose.EMAIL_VERIFICATION, ttl);

        assertThat(rawToken)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");

        Row row = rowOf(rawToken).orElseThrow();
        assertThat(row.tokenHash())
                .isEqualTo(OpaqueTokens.sha256Hex(rawToken))
                .isNotEqualTo(rawToken);
        assertThat(row.purpose()).isEqualTo(UserTokenPurpose.EMAIL_VERIFICATION.name());
        assertThat(row.usedAt()).isNull();
        assertThat(Duration.between(row.issuedAt(), row.expiresAt()).toSeconds())
                .isEqualTo(ttl.toSeconds());
    }

    @Test
    @DisplayName("issue: token mới cùng purpose vô hiệu token cũ, chỉ token mới dùng được")
    void issueInvalidatesPreviousUnusedToken() {
        User user = newUser();

        String first = userTokenService.issue(
                user.getId(), UserTokenPurpose.EMAIL_VERIFICATION, properties.verificationTtl());
        String second = userTokenService.issue(
                user.getId(), UserTokenPurpose.EMAIL_VERIFICATION, properties.verificationTtl());

        assertThat(rowOf(first).orElseThrow().usedAt()).isNotNull();
        assertThat(rowOf(second).orElseThrow().usedAt()).isNull();

        assertThat(userTokenService.consume(first, UserTokenPurpose.EMAIL_VERIFICATION)).isEmpty();
        assertThat(userTokenService.consume(second, UserTokenPurpose.EMAIL_VERIFICATION)).isPresent();
    }

    @Test
    @DisplayName("consume: token hợp lệ được đánh dấu đã dùng và trả đúng chủ sở hữu")
    void consumeMarksTokenUsed() {
        User user = newUser();
        String rawToken = userTokenService.issue(
                user.getId(), UserTokenPurpose.PASSWORD_RESET, properties.resetTtl());

        UserToken token = userTokenService.consume(rawToken, UserTokenPurpose.PASSWORD_RESET).orElseThrow();

        assertThat(token.getUserId()).isEqualTo(user.getId());
        assertThat(token.getPurpose()).isEqualTo(UserTokenPurpose.PASSWORD_RESET);
        assertThat(rowOf(rawToken).orElseThrow().usedAt()).isNotNull();
    }

    @Test
    @DisplayName("consume: dùng lần hai trả rỗng")
    void consumeRejectsSecondAttempt() {
        User user = newUser();
        String rawToken = userTokenService.issue(
                user.getId(), UserTokenPurpose.EMAIL_VERIFICATION, properties.verificationTtl());

        assertThat(userTokenService.consume(rawToken, UserTokenPurpose.EMAIL_VERIFICATION)).isPresent();
        assertThat(userTokenService.consume(rawToken, UserTokenPurpose.EMAIL_VERIFICATION)).isEmpty();
    }

    @Test
    @DisplayName("consume: sai purpose trả rỗng và KHÔNG đánh dấu đã dùng")
    void consumeRejectsWrongPurposeWithoutMarkingUsed() {
        User user = newUser();
        String rawToken = userTokenService.issue(
                user.getId(), UserTokenPurpose.EMAIL_VERIFICATION, properties.verificationTtl());

        assertThat(userTokenService.consume(rawToken, UserTokenPurpose.PASSWORD_RESET)).isEmpty();
        assertThat(rowOf(rawToken).orElseThrow().usedAt()).isNull();

        // Vẫn dùng được với đúng purpose
        assertThat(userTokenService.consume(rawToken, UserTokenPurpose.EMAIL_VERIFICATION)).isPresent();
    }

    @Test
    @DisplayName("consume: token hết hạn trả rỗng")
    void consumeRejectsExpiredToken() {
        User user = newUser();
        String rawToken = userTokenService.issue(
                user.getId(), UserTokenPurpose.PASSWORD_RESET, properties.resetTtl());

        jdbc.update("UPDATE user_tokens SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
                OpaqueTokens.sha256Hex(rawToken));

        assertThat(userTokenService.consume(rawToken, UserTokenPurpose.PASSWORD_RESET)).isEmpty();
    }

    @Test
    @DisplayName("consume: token không tồn tại trả rỗng")
    void consumeRejectsUnknownToken() {
        assertThat(userTokenService.consume("not-a-real-token", UserTokenPurpose.EMAIL_VERIFICATION))
                .isEmpty();
    }

    @Test
    @DisplayName("canIssue: chặn trong cooldown, cho phép sau khi cooldown trôi qua")
    void canIssueRespectsCooldown() {
        User user = newUser();

        assertThat(userTokenService.canIssue(user.getId(), UserTokenPurpose.EMAIL_VERIFICATION)).isTrue();

        userTokenService.issue(user.getId(), UserTokenPurpose.EMAIL_VERIFICATION, properties.verificationTtl());
        assertThat(userTokenService.canIssue(user.getId(), UserTokenPurpose.EMAIL_VERIFICATION)).isFalse();

        // Đẩy issued_at về quá khứ xa hơn cooldown
        jdbc.update("""
                UPDATE user_tokens
                   SET issued_at = now() - interval '2 minutes'
                 WHERE user_id = ?
                   AND purpose = 'EMAIL_VERIFICATION'
                """, user.getId());

        assertThat(userTokenService.canIssue(user.getId(), UserTokenPurpose.EMAIL_VERIFICATION)).isTrue();
    }

    @Test
    @DisplayName("issue: purpose khác và user khác không vô hiệu token của nhau")
    void purposesAndUsersAreIndependent() {
        User userA = newUser();
        User userB = newUser();

        String verification = userTokenService.issue(
                userA.getId(), UserTokenPurpose.EMAIL_VERIFICATION, properties.verificationTtl());

        userTokenService.issue(userA.getId(), UserTokenPurpose.PASSWORD_RESET, properties.resetTtl());
        userTokenService.issue(userB.getId(), UserTokenPurpose.EMAIL_VERIFICATION, properties.verificationTtl());

        assertThat(rowOf(verification).orElseThrow().usedAt()).isNull();
        assertThat(userTokenService.consume(verification, UserTokenPurpose.EMAIL_VERIFICATION)).isPresent();
    }

    @Test
    @DisplayName("T10: job dọn xoá token hết hạn quá 30 ngày, giữ token còn hạn")
    void cleanupDeletesOnlyLongExpiredTokens() {
        User user = newUser();
        String longExpired = userTokenService.issue(
                user.getId(), UserTokenPurpose.EMAIL_VERIFICATION, properties.verificationTtl());
        String fresh = userTokenService.issue(
                user.getId(), UserTokenPurpose.PASSWORD_RESET, properties.resetTtl());

        jdbc.update("UPDATE user_tokens SET expires_at = now() - interval '31 days' WHERE token_hash = ?",
                OpaqueTokens.sha256Hex(longExpired));

        // Đi đúng đường @Scheduled: nếu transaction bị self-invocation nuốt mất,
        // câu DELETE sẽ ném TransactionRequiredException.
        cleanupJob.runScheduled();

        assertThat(countByHash(longExpired)).isZero();
        assertThat(countByHash(fresh)).isEqualTo(1);
    }

    private int countByHash(String rawToken) {
        return jdbc.queryForObject("SELECT count(*) FROM user_tokens WHERE token_hash = ?",
                Integer.class, OpaqueTokens.sha256Hex(rawToken));
    }
}
