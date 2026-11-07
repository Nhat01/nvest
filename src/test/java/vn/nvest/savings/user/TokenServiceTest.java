package vn.nvest.savings.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class TokenServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes-long!!";
    private static final String OTHER_SECRET = "a-completely-different-secret-of-32-plus-bytes!";

    private static TokenService service(String secret, Clock clock) {
        return new TokenService(secret, Duration.ofHours(1), clock);
    }

    private static JwtDecoder decoder(String secret) {
        return NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Test
    @DisplayName("R62: token mang đúng id, email, vai trò và sống đúng 1 giờ")
    void issuedTokenCarriesTheExpectedClaims() {
        TokenService.IssuedToken token = service(SECRET, Clock.systemUTC()).issue(7L, "an@test.local", Role.USER);

        Jwt jwt = decoder(SECRET).decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("an@test.local");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
        assertThat(token.expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("Token ký bằng khóa khác bị từ chối")
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = service(OTHER_SECRET, Clock.systemUTC()).issue(7L, "an@test.local", Role.USER).value();

        assertThatThrownBy(() -> decoder(SECRET).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Token hết hạn bị từ chối")
    void expiredTokenIsRejected() {
        Clock threeHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(3)), ZoneOffset.UTC);
        String token = service(SECRET, threeHoursAgo).issue(7L, "an@test.local", Role.USER).value();

        assertThatThrownBy(() -> decoder(SECRET).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Sửa payload để tự phong ADMIN thì chữ ký không còn khớp")
    void forgedPayloadIsRejected() {
        String token = service(SECRET, Clock.systemUTC()).issue(7L, "an@test.local", Role.USER).value();
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"7\",\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8));
        String forgedToken = parts[0] + "." + forgedPayload + "." + parts[2];   // giữ nguyên chữ ký cũ

        assertThatThrownBy(() -> decoder(SECRET).decode(forgedToken)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Khóa quá ngắn bị từ chối ngay lúc khởi tạo")
    void shortSecretIsRejected() {
        assertThatThrownBy(() -> service("too-short", Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}