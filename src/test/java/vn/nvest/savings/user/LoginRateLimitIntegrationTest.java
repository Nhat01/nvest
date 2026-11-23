package vn.nvest.savings.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

@TestPropertySource(properties = {
        "nvest.security.login-rate-limit.enabled=true",
        "nvest.security.login-rate-limit.max-failures=3",
        "nvest.security.login-rate-limit.window=PT1M"
})
class LoginRateLimitIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "S3cure-passw0rd";

    @Autowired
    private MockMvc mockMvc;

    private static String uniqueEmail() {
        return "limit-" + UUID.randomUUID() + "@test.local";
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                                + "\",\"fullName\":\"Rate Limit\"}"))
                .andExpect(status().isCreated());
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    @DisplayName("Sai mật khẩu quá số lần cho phép trả 429 kèm Retry-After")
    void tooManyFailuresReturn429() throws Exception {
        String email = uniqueEmail();
        register(email);

        for (int attempt = 1; attempt <= 3; attempt++) {
            login(email, "wrong-password").andExpect(status().isUnauthorized());
        }

        // Đúng mật khẩu vẫn bị chặn cho tới khi cửa sổ trôi qua.
        login(email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("Đăng nhập thành công xóa lịch sử sai nên không khóa người dùng")
    void successResetsFailures() throws Exception {
        String email = uniqueEmail();
        register(email);

        login(email, "wrong-password").andExpect(status().isUnauthorized());
        login(email, "wrong-password").andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isOk());

        // Sau khi reset, vẫn còn lượt sai trước khi chạm ngưỡng 3.
        login(email, "wrong-password").andExpect(status().isUnauthorized());
        login(email, "wrong-password").andExpect(status().isUnauthorized());
    }
}
