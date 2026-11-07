package vn.nvest.savings.wallet;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class WalletApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("R64: mỗi user chỉ thấy số dư của chính mình (id lấy từ token)")
    void balanceBelongsToTheTokenOwner() throws Exception {
        long alice = createUserWithDeposit(1_000_000);
        long bob = createUserWithDeposit(250_000);

        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(1_000_000))
                .andExpect(jsonPath("$.held").value(0));
        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(jsonPath("$.available").value(250_000));
        mockMvc.perform(get("/api/wallet")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Sao kê: mới nhất trước, đủ loại giao dịch, có thời điểm")
    void statementListsNewestFirst() throws Exception {
        long userId = createUserWithDeposit(500_000);
        walletService.holdForWithdrawal(userId, 100_000, "hold:" + UUID.randomUUID());

        mockMvc.perform(get("/api/wallet/transactions").header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].txType").value("WITHDRAW_HOLD"))
                .andExpect(jsonPath("$[0].balanceType").value("HELD"))
                .andExpect(jsonPath("$[0].amount").value(100_000))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[1].balanceType").value("AVAILABLE"))
                .andExpect(jsonPath("$[1].amount").value(-100_000))
                .andExpect(jsonPath("$[2].txType").value("DEPOSIT"))
                .andExpect(jsonPath("$[2].amount").value(500_000));
    }

    @Test
    @DisplayName("R68: phân trang và không thấy sao kê của người khác")
    void paginationAndIsolation() throws Exception {
        long alice = createUserWithDeposit(500_000);
        long bob = createUserWithDeposit(300_000);
        walletService.deposit(alice, 100_000, "second:" + UUID.randomUUID());

        mockMvc.perform(get("/api/wallet/transactions?page=0&size=1").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].amount").value(100_000));
        mockMvc.perform(get("/api/wallet/transactions?page=1&size=1").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(jsonPath("$[0].amount").value(500_000));
        mockMvc.perform(get("/api/wallet/transactions").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].amount").value(300_000));
    }
}