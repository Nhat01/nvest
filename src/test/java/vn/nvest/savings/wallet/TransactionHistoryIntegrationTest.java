package vn.nvest.savings.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.nvest.savings.common.ClockConfig;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class TransactionHistoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Tạo 4 dòng sao kê cho ví user:
     * +500.000 DEPOSIT, -100.000 và +100.000 của WITHDRAW_HOLD, +250.000 DEPOSIT.
     */
    private long userWithMixedHistory() {
        long userId = createUserWithDeposit(500_000);
        walletService.holdForWithdrawal(userId, 100_000, "hold:" + UUID.randomUUID());
        walletService.deposit(userId, 250_000, "second:" + UUID.randomUUID());
        return userId;
    }

    @Test
    @DisplayName("Lọc theo loại giao dịch và hướng tiền")
    void filtersByTypeAndDirection() throws Exception {
        long userId = userWithMixedHistory();
        String auth = bearer(userId);

        mockMvc.perform(get("/api/wallet/transactions?type=DEPOSIT").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].txType").value("DEPOSIT"))
                .andExpect(jsonPath("$.items[1].txType").value("DEPOSIT"));

        mockMvc.perform(get("/api/wallet/transactions?direction=OUT").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].amount").value(-100_000));

        mockMvc.perform(get("/api/wallet/transactions?direction=IN").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(3)));
    }

    @Test
    @DisplayName("Lọc theo ngăn số dư, khoảng tiền tuyệt đối và từ khoá mô tả")
    void filtersByBalanceTypeAmountAndKeyword() throws Exception {
        long userId = userWithMixedHistory();
        String auth = bearer(userId);

        mockMvc.perform(get("/api/wallet/transactions?balanceType=HELD").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].amount").value(100_000));

        mockMvc.perform(get("/api/wallet/transactions?minAmount=100000&maxAmount=100000")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].amount").value(100_000))
                .andExpect(jsonPath("$.items[1].amount").value(-100_000));

        mockMvc.perform(get("/api/wallet/transactions?q=deposit").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].txType").value("DEPOSIT"));
    }

    @Test
    @DisplayName("Lọc theo ngày nghiệp vụ Việt Nam, 'to' bao gồm cả ngày đó")
    void filtersByBusinessDateRange() throws Exception {
        long userId = userWithMixedHistory();
        String auth = bearer(userId);
        LocalDate today = LocalDate.now(ClockConfig.BUSINESS_ZONE);

        mockMvc.perform(get("/api/wallet/transactions?from=" + today + "&to=" + today)
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(4)));

        mockMvc.perform(get("/api/wallet/transactions?to=" + today.minusDays(1))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(0));

        mockMvc.perform(get("/api/wallet/transactions?from=" + today.plusDays(1))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    @DisplayName("Bộ lọc sai trả 400 problem+json")
    void rejectsInvalidFilters() throws Exception {
        long userId = userWithMixedHistory();
        String auth = bearer(userId);

        mockMvc.perform(get("/api/wallet/transactions?minAmount=500000&maxAmount=100000")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(get("/api/wallet/transactions?type=NOT_A_TYPE")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/wallet/transactions?from=2026-02-10&to=2026-02-01")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Export CSV áp dụng cùng bộ lọc và không lẫn dữ liệu người khác")
    void exportsFilteredCsv() throws Exception {
        long alice = userWithMixedHistory();
        long bob = createUserWithDeposit(300_000);

        MvcResult result = mockMvc.perform(get("/api/wallet/transactions/export?direction=IN")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andExpect(header().string("X-Export-Rows", "3"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();

        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.lines()).hasSize(4);   // header + 3 dòng tiền vào
        assertThat(csv).startsWith("entryId,txType,balanceType,amount");
        assertThat(csv).contains("DEPOSIT");
        assertThat(csv).doesNotContain("-100000");

        MvcResult bobResult = mockMvc.perform(get("/api/wallet/transactions/export")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Rows", "1"))
                .andReturn();
        assertThat(bobResult.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("300000");
    }
}
