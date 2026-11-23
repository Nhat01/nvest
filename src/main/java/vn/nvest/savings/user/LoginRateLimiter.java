package vn.nvest.savings.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import vn.nvest.savings.common.TooManyLoginAttemptsException;

/**
 * Bộ đếm đăng nhập sai trong bộ nhớ của từng instance.
 *
 * Chỉ đếm lần đăng nhập THẤT BẠI; đăng nhập thành công xóa lịch sử của cặp
 * (IP, email). Nhờ vậy người dùng gõ đúng mật khẩu không bị chặn oan, còn kẻ
 * dò mật khẩu vẫn bị giới hạn.
 *
 * Đây là giải pháp đủ cho một instance (dự án học tập). Khi chạy nhiều instance,
 * bộ đếm nên chuyển sang Redis hoặc một kho dùng chung khác.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final boolean enabled;
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginRateLimiter(LoginRateLimitProperties properties, Clock clock) {
        this.enabled = properties.enabled();
        this.maxFailures = properties.maxFailures();
        this.window = properties.window();
        this.clock = clock;
    }

    /** Khóa đếm gồm IP và email đã chuẩn hóa, để hai tài khoản khác nhau không ảnh hưởng nhau. */
    public String key(String remoteAddress, String email) {
        String address = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.trim();
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return address + "|" + normalizedEmail;
    }

    /** Ném 429 nếu cặp (IP, email) đã sai quá số lần cho phép trong cửa sổ thời gian. */
    public void checkAllowed(String key) {
        if (!enabled) {
            return;
        }
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return;
        }
        Instant now = clock.instant();
        synchronized (attempts) {
            prune(attempts, now.minus(window));
            if (attempts.size() >= maxFailures) {
                Instant oldest = attempts.peekFirst();
                long retryAfter = oldest == null ? window.toSeconds()
                        : Math.max(1, Duration.between(now, oldest.plus(window)).toSeconds());
                throw new TooManyLoginAttemptsException(retryAfter);
            }
        }
    }

    public void recordFailure(String key) {
        if (!enabled) {
            return;
        }
        Deque<Instant> attempts = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        Instant now = clock.instant();
        synchronized (attempts) {
            prune(attempts, now.minus(window));
            attempts.addLast(now);
        }
        if (failures.size() > MAX_TRACKED_KEYS) {
            removeExpiredKeys(now.minus(window));
            if (failures.size() > MAX_TRACKED_KEYS) {
                // Chống phình bộ nhớ khi bị flood nhiều cặp (IP, email) lạ.
                // Đây là biện pháp thô nhưng an toàn hơn để map tăng vô hạn.
                failures.clear();
            }
        }
    }

    /** Đăng nhập thành công: xóa lịch sử sai của cặp (IP, email). */
    public void reset(String key) {
        failures.remove(key);
    }

    private void removeExpiredKeys(Instant cutoff) {
        failures.entrySet().removeIf(entry -> {
            Deque<Instant> attempts = entry.getValue();
            synchronized (attempts) {
                prune(attempts, cutoff);
                return attempts.isEmpty();
            }
        });
    }

    private static void prune(Deque<Instant> attempts, Instant cutoff) {
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
            attempts.pollFirst();
        }
    }
}
