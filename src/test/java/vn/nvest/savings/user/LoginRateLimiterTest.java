package vn.nvest.savings.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.nvest.savings.common.TooManyLoginAttemptsException;

class LoginRateLimiterTest {

    private static final String KEY = "127.0.0.1|user@test.local";

    @Test
    @DisplayName("Đủ số lần sai thì chặn; hết cửa sổ thời gian thì mở lại")
    void blocksThenUnblocksAfterWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(
                new LoginRateLimitProperties(true, 2, Duration.ofMinutes(1)), clock);

        limiter.checkAllowed(KEY);
        limiter.recordFailure(KEY);
        limiter.recordFailure(KEY);

        assertThatThrownBy(() -> limiter.checkAllowed(KEY))
                .isInstanceOf(TooManyLoginAttemptsException.class);

        clock.advance(Duration.ofMinutes(2));

        assertThatCode(() -> limiter.checkAllowed(KEY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Đăng nhập thành công xóa lịch sử sai")
    void resetClearsFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(
                new LoginRateLimitProperties(true, 2, Duration.ofMinutes(1)), clock);

        limiter.recordFailure(KEY);
        limiter.recordFailure(KEY);
        limiter.reset(KEY);

        assertThatCode(() -> limiter.checkAllowed(KEY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Tắt cấu hình thì không bao giờ chặn")
    void disabledLimiterNeverBlocks() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(
                new LoginRateLimitProperties(false, 1, Duration.ofMinutes(1)), clock);

        for (int i = 0; i < 10; i++) {
            limiter.recordFailure(KEY);
        }

        assertThatCode(() -> limiter.checkAllowed(KEY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Khóa đếm chuẩn hóa email và tách theo IP")
    void keyNormalizesEmailAndSeparatesAddresses() {
        LoginRateLimiter limiter = new LoginRateLimiter(
                new LoginRateLimitProperties(true, 3, Duration.ofMinutes(1)),
                Clock.systemUTC());

        String first = limiter.key("127.0.0.1", "  User@Test.Local ");
        String sameEmailOtherAddress = limiter.key("10.0.0.2", "user@test.local");

        assertThat(first).isEqualTo(KEY);
        assertThat(sameEmailOtherAddress).isNotEqualTo(first);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
