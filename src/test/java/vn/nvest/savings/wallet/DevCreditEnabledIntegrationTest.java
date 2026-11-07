package vn.nvest.savings.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

@ActiveProfiles("dev")
class DevCreditEnabledIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    private ResultActions credit(String bearer, long amount) throws Exception {
        var request = post("/api/dev/credit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + "}");
        if (bearer != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("R71: với profile dev, nạp tiền thử cộng vào ví của chính người gọi")
    void devCreditWorksWithTheDevProfile() throws Exception {
        long userId = createUserWithDeposit(0);
        String token = bearer(userId);

        assertThat(context.getBeansOfType(DevCreditController.class)).hasSize(1);
        credit(token, 5_000_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(5_000_000));
        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.available").value(5_000_000));
    }

    @Test
    @DisplayName("R71: giới hạn 100 triệu mỗi lần, số tiền không dương bị từ chối, phải đăng nhập")
    void devCreditIsLimited() throws Exception {
        String token = bearer(createUserWithDeposit(0));

        credit(token, 100_000_001).andExpect(status().isBadRequest());
        credit(token, 0).andExpect(status().isBadRequest());
        credit(null, 1_000_000).andExpect(status().isUnauthorized());
    }
}