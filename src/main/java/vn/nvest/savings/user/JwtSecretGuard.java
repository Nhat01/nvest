package vn.nvest.savings.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chặn việc chạy production bằng khóa JWT mặc định trong mã nguồn.
 *
 * Khóa mặc định dài >= 32 byte nên lọt qua kiểm tra độ dài của TokenService.
 * Nếu không chặn, một deployment quên set NVEST_JWT_SECRET sẽ ký token bằng khóa
 * ai đọc mã nguồn cũng biết.
 *
 * Chỉ cho phép khóa mặc định khi bật rõ ràng NVEST_ALLOW_DEV_JWT_SECRET=true.
 * Cố ý không tự động cho phép theo profile: profile dev còn mở endpoint nạp tiền
 * thử, nên không muốn khuyến khích bật nó ở production.
 */
@Component
public class JwtSecretGuard {

    private static final Logger log = LoggerFactory.getLogger(JwtSecretGuard.class);

    public JwtSecretGuard(
            @Value("${nvest.security.jwt.secret:" + TokenService.DEV_DEFAULT_SECRET + "}") String secret,
            @Value("${nvest.security.jwt.allow-dev-secret:false}") boolean allowDevSecret) {
        validate(secret, allowDevSecret);
    }

    static void validate(String secret, boolean allowDevSecret) {
        if (!TokenService.DEV_DEFAULT_SECRET.equals(secret)) {
            return;
        }
        if (allowDevSecret) {
            log.warn("Using the built-in development JWT secret. "
                    + "Set NVEST_JWT_SECRET before deploying to production.");
            return;
        }
        throw new IllegalStateException(
                "Refusing to start with the built-in development JWT secret. "
                        + "Set NVEST_JWT_SECRET to a random value of at least 32 bytes, "
                        + "or set NVEST_ALLOW_DEV_JWT_SECRET=true for local development only.");
    }
}
