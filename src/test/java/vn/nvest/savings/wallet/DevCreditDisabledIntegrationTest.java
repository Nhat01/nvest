package vn.nvest.savings.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class DevCreditDisabledIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("R71: ngoài profile dev, endpoint nạp tiền thử KHÔNG tồn tại (không phải bị khóa)")
    void devCreditDoesNotExistWithoutTheDevProfile() throws Exception {
        long userId = createUserWithDeposit(0);

        assertThat(context.getBeansOfType(DevCreditController.class)).isEmpty();
        // Dù đã đăng nhập, gọi thử cũng không thể thành công và số dư không đổi
        mockMvc.perform(post("/api/dev/credit").header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000000}"))
                .andExpect(status().is4xxClientError());
        assertThat(walletService.getBalance(userId).available()).isZero();
    }
}