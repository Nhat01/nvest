package vn.nvest.savings.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import vn.nvest.savings.wallet.WalletService;

import java.util.TimeZone;
import java.util.UUID;

@SpringBootTest(properties = "nvest.scheduling.enabled=false")
public abstract class AbstractPostgresIntegrationTest {

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
        Long userId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, full_name) VALUES (?, 'x', 'Test') RETURNING id",
                Long.class, "u-" + UUID.randomUUID() + "@test.local");
        walletService.createUserWallet(userId);
        if (amount > 0) {
            walletService.deposit(userId, amount, "seed:" + userId);
        }
        return userId;
    }
}
