package vn.nvest.savings.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import vn.nvest.savings.user.Role;
import vn.nvest.savings.user.TokenService;
import vn.nvest.savings.wallet.WalletService;

import java.util.TimeZone;
import java.util.UUID;

@SpringBootTest(properties = {
        "nvest.scheduling.enabled=false",
        // Test nghiệp vụ không cần Kafka. Bật ở đây sẽ khiến KafkaAdmin chờ broker
        // ~30 giây mỗi lần dựng context (và log lỗi) dù test không đụng tới Kafka.
        // Test riêng về Kafka tự bật lại bằng @DynamicPropertySource.
        "nvest.kafka.enabled=false",
        // Test dùng khóa JWT mặc định; JwtSecretGuard chỉ cho phép khi bật rõ ràng.
        "nvest.security.jwt.allow-dev-secret=true",
        // Test nghiệp vụ không phụ thuộc bộ đếm đăng nhập; test riêng sẽ bật lại.
        "nvest.security.login-rate-limit.enabled=false"
})
@AutoConfigureMockMvc
public abstract class AbstractPostgresIntegrationTest {

    @Autowired
    protected TokenService tokenService;

    /** Giá trị header Authorization cho một user đã có trong DB, token ký bằng khóa thật của ứng dụng. */
    protected String bearer(long userId) {
        return bearer(userId, Role.USER);
    }

    protected String bearer(long userId, Role role) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        return "Bearer " + tokenService.issue(userId, email, role).value();
    }

    static {
        // Test không đi qua hàm main, nên phải tự sửa lỗi múi giờ "Asia/Saigon" (xem Bước 1)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
    }

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    static {
        POSTGRES.start();   // chạy một lần cho cả bộ test, tự dọn khi JVM thoát
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected WalletService walletService;

    /** Tạo user mới (email duy nhất) kèm ví, và nạp sẵn số tiền ban đầu nếu > 0. */
    protected long createUserWithDeposit(long amount) {
        return createUserWithDeposit(amount, true);
    }

    /**
     * @param verified true để mô phỏng user cũ (V11 backfill đã đánh dấu xác thực).
     *                 Test cần user chưa xác thực thì truyền false.
     */
    protected long createUserWithDeposit(long amount, boolean verified) {
        Long userId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, full_name, email_verified_at) "
                        + "VALUES (?, 'x', 'Test', " + (verified ? "now()" : "NULL") + ") RETURNING id",
                Long.class, "u-" + UUID.randomUUID() + "@test.local");
        walletService.createUserWallet(userId);
        if (amount > 0) {
            walletService.deposit(userId, amount, "seed:" + userId);
        }
        return userId;
    }

    /**
     * Test không đi qua hộp thư, nên đánh dấu xác thực trực tiếp sau khi đăng ký.
     * Dùng cho các test cần login mà không muốn phụ thuộc luồng verify.
     */
    protected void markEmailVerified(String email) {
        int updated = jdbc.update(
                "UPDATE users SET email_verified_at = now() "
                        + "WHERE lower(email) = lower(?) AND email_verified_at IS NULL",
                email);
        if (updated != 1) {
            throw new IllegalStateException("Expected exactly one unverified user with email " + email);
        }
    }
}
