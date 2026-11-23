package vn.nvest.savings.common;

/** Quá nhiều lần đăng nhập sai từ cùng một nguồn; trả 429 kèm Retry-After. */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super("Too many failed login attempts. Please try again later.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
