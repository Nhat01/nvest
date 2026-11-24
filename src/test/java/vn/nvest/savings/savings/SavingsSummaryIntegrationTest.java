package vn.nvest.savings.savings;

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

class SavingsSummaryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SavingsService savingsService;
    @Autowired private InterestAccrualService accrualService;

    @Test
    @DisplayName("Tổng hợp: chỉ tính gói của chính user, gốc + lãi tạm tính + lãi dự kiến cả kỳ")
    void summaryCoversOnlyTheCallersActiveAccounts() throws Exception {
        long userId = createUserWithDeposit(15_000_000);
        long other = createUserWithDeposit(3_000_000);
        SavingsAccount a = savingsService.open(userId, "SAVE_90", 10_000_000, MaturityOption.PAYOUT_ALL, "o:" + UUID.randomUUID());
        savingsService.open(userId, "FLEX_30", 5_000_000, MaturityOption.PAYOUT_ALL, "o:" + UUID.randomUUID());
        savingsService.open(other, "SAVE_90", 3_000_000, MaturityOption.PAYOUT_ALL, "o:" + UUID.randomUUID());
        accrualService.accrueAccount(a.getId(), a.getStartDate().plusDays(9));   // 10 ngày: 16.438đ

        mockMvc.perform(get("/api/savings/summary").header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(2))
                .andExpect(jsonPath("$.totalPrincipal").value(15_000_000))
                .andExpect(jsonPath("$.accruedInterest").value(16_438))
                .andExpect(jsonPath("$.expectedInterest").value(147_945 + 18_493));
    }

    @Test
    @DisplayName("Tổng hợp: chưa có gói thì toàn số 0; chưa đăng nhập 401")
    void emptyAndUnauthenticated() throws Exception {
        long userId = createUserWithDeposit(0);

        mockMvc.perform(get("/api/savings/summary").header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(jsonPath("$.activeCount").value(0))
                .andExpect(jsonPath("$.totalPrincipal").value(0));
        mockMvc.perform(get("/api/savings/summary")).andExpect(status().isUnauthorized());
    }
}
