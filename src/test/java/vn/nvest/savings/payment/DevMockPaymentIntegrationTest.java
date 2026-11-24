package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

@ActiveProfiles("dev")
class DevMockPaymentIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DepositService depositService;

    private ResultActions simulate(String token, String orderCode, String result) throws Exception {
        return mockMvc.perform(post("/api/dev/deposits/" + orderCode + "/simulate")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"" + result + "\"}"));
    }

    @Test
    @DisplayName("Dev: khách 'thanh toán' thì ví được cộng; người khác không thao tác được đơn của bạn (404)")
    void ownerCanSimulatePaymentOthersCannot() throws Exception {
        long owner = createUserWithDeposit(0);
        long stranger = createUserWithDeposit(0);
        String orderCode = depositService.createDeposit(owner, 500_000, "dep:" + UUID.randomUUID())
                .payment().getOrderCode();

        simulate(bearer(stranger), orderCode, "SUCCEEDED").andExpect(status().isNotFound());
        assertThat(walletService.getBalance(owner).available()).isZero();

        simulate(bearer(owner), orderCode, "SUCCEEDED")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(walletService.getBalance(owner).available()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("Dev: khách hủy thì đơn FAILED; result khác SUCCEEDED/FAILED bị từ chối 400")
    void cancelAndInvalidResult() throws Exception {
        long owner = createUserWithDeposit(0);
        String orderCode = depositService.createDeposit(owner, 500_000, "dep:" + UUID.randomUUID())
                .payment().getOrderCode();

        simulate(bearer(owner), orderCode, "PENDING").andExpect(status().isBadRequest());
        simulate(bearer(owner), orderCode, "FAILED").andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(walletService.getBalance(owner).available()).isZero();
    }
}
