package vn.nvest.savings.user;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class TokenService {
    /** Khóa mặc định CHỈ để chạy dev và test. Khi triển khai thật phải đặt NVEST_JWT_SECRET (R62). */
    public static final String DEV_DEFAULT_SECRET = "dev-only-secret-change-me-in-production-32b";
    private static final int MIN_SECRET_BYTES = 32;   // HS256 cần khóa tối thiểu 256 bit

    public record IssuedToken(String value, long expiresInSeconds) {
    }

    private final SecretKey key;
    private final JwtEncoder encoder;
    private final Duration ttl;
    private final Clock clock;

    public TokenService(@Value("${nvest.security.jwt.secret:" + DEV_DEFAULT_SECRET + "}") String secret,
                        @Value("${nvest.security.jwt.ttl:PT1H}") Duration ttl,
                        Clock clock) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if(secretBytes.length < MIN_SECRET_BYTES){
            throw new IllegalArgumentException("JWT secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(secretBytes,"HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.clock = clock;
        this.ttl = ttl;
    }

    public IssuedToken issue(Long userId, String email, Role role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("email", email)
                .claim("role", role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, ttl.getSeconds());
    }

    public IssuedToken issue(User user) {
        return issue(user.getId(), user.getEmail(), user.getRole());
    }

    /** Dùng để dựng bộ kiểm tra token ở SecurityConfig. */
    public SecretKey signingKey() {
        return key;
    }
}
