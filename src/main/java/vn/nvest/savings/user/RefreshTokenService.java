package vn.nvest.savings.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final Clock clock;
    private final RefreshTokenProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               UserRepository userRepository,
                               TokenService tokenService,
                               Clock clock,
                               RefreshTokenProperties properties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public TokenPair issue(User user) {
        Instant now = clock.instant();
        String rawToken = newRawToken();
        Instant expiresAt = now.plus(properties.ttl());

        refreshTokenRepository.save(RefreshToken.issue(user.getId(), hash(rawToken), UUID.randomUUID(), now, expiresAt));

        return new TokenPair(tokenService.issue(user), rawToken, properties.ttl().toSeconds());
    }

    @Transactional
    public Optional<TokenPair> rotate(String rawToken) {
        Instant now = clock.instant();

        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        RefreshToken current = found.get();

        if(current.isRevoked()) {
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now, RevokedReason.REUSE_DETECTED);
            log.warn("Refresh token reuse detected: user={}, family={}",
                    current.getUserId(), current.getFamilyId());
            return Optional.empty();
        }

        if(current.isExpired(now)) {
            return  Optional.empty();
        }

        Optional<User> user = userRepository.findById(current.getUserId())
                .filter(u -> u.getStatus() == UserStatus.ACTIVE);
        if (user.isEmpty()) {
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now, RevokedReason.USER_LOCKED);
            return Optional.empty();
        }

        current.rotate(now);

        String newRawToken = newRawToken();
        refreshTokenRepository.save(RefreshToken.issue(current.getUserId(), hash(newRawToken), current.getFamilyId(), now, current.getExpiresAt()));

        long refreshExpiresIn = Math.max(0, Duration.between(now, current.getExpiresAt()).toSeconds());
        return Optional.of(new TokenPair(tokenService.issue(user.get()), newRawToken, refreshExpiresIn));
    }

    @Transactional
    public void revoke(String rawToken, boolean allDevices) {
        Instant now = clock.instant();
        refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken)).ifPresent(token -> {
            if (allDevices) {
                refreshTokenRepository.revokeAllByUser(token.getUserId(), now, RevokedReason.LOGOUT_ALL);
            } else if (!token.isRevoked()) {
                token.revoke(now, RevokedReason.LOGOUT);
            }
        });
    }


    private String newRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
