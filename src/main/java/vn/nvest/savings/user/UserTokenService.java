package vn.nvest.savings.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class UserTokenService {

    private final UserTokenRepository repository;
    private final Clock clock;
    private final EmailTokenProperties properties;

    public UserTokenService(UserTokenRepository repository, Clock clock, EmailTokenProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public String issue(long userId, UserTokenPurpose purpose, Duration ttl) {
        Instant now = clock.instant();
        repository.invalidateUnused(userId, purpose, now);

        String rawToken = OpaqueTokens.newRawToken();
        repository.save(UserToken.issue(
                userId, OpaqueTokens.sha256Hex(rawToken), purpose, now, now.plus(ttl)));
        return rawToken;
    }

    @Transactional
    public Optional<UserToken> consume(String rawToken, UserTokenPurpose purpose) {
        Instant now = clock.instant();
        Optional<UserToken> found = repository.findByTokenHashForUpdate(
                OpaqueTokens.sha256Hex(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        UserToken token = found.get();
        if (token.getPurpose() != purpose || token.isUsed() || token.isExpired(now)) {
            return Optional.empty();
        }
        token.markUsed(now);
        return Optional.of(token);
    }

    @Transactional(readOnly = true)
    public boolean canIssue(long userId, UserTokenPurpose purpose) {
        return repository.findTopByUserIdAndPurposeOrderByIdDesc(userId, purpose)
                .map(last -> !last.getIssuedAt().plus(properties.cooldown()).isAfter(clock.instant()))
                .orElse(true);
    }

    // Xoá token vừa tạo khi gửi mail thất bại, để user được gửi lại ngay
    // thay vì phải chờ hết cooldown cho một token không ai nhận được.
    @Transactional
    public void discard(String rawToken) {
        repository.deleteByTokenHash(OpaqueTokens.sha256Hex(rawToken));
    }

    // Job dọn dẹp gọi method này. Không đặt @Transactional trong chính job vì
    // @Scheduled gọi this.run() sẽ bỏ qua proxy, câu DELETE thiếu transaction.
    @Transactional
    public int deleteExpiredBefore(Instant cutoff) {
        return repository.deleteExpiredBefore(cutoff);
    }
}
