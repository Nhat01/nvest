package vn.nvest.savings.savings;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class SavingsApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InterestAccrualService accrualService;

    @Autowired
    private Clock clock;

    private ResultActions openRequest(String bearer, String key, String product, long amount, String option)
            throws Exception {
        var request = post("/api/savings").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productCode\":\"" + product + "\",\"amount\":" + amount
                        + ",\"maturityOption\":\"" + option + "\"}");
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private long openAndGetId(String bearer, long amount) throws Exception {
        String body = openRequest(bearer, "open:" + UUID.randomUUID(), "SAVE_90", amount, "PAYOUT_ALL")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void travelBack(long accountId, int days) {
        jdbc.update("UPDATE savings_accounts SET start_date = start_date - " + days
                + ", maturity_date = maturity_date - " + days + " WHERE id = ?", accountId);
    }

    @Test
    @DisplayName("R67: danh sách sản phẩm trả lãi suất theo phần trăm (6.00 nghĩa là 6%)")
    void productsExposeRatePercent() throws Exception {
        String token = bearer(createUserWithDeposit(0));

        mockMvc.perform(get("/api/savings/products").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'SAVE_90')].termDays").value(90))
                .andExpect(jsonPath("$[?(@.code == 'SAVE_90')].annualRatePercent").value(6.0))
                .andExpect(jsonPath("$[?(@.code == 'FLEX_30')].annualRatePercent").value(4.5));
    }

    @Test
    @DisplayName("Mở gói qua API: chốt lãi suất, tính ngày đáo hạn, trừ ví; gửi lại cùng key trả gói cũ")
    void openAccountThroughTheApi() throws Exception {
        long userId = createUserWithDeposit(10_000_000);
        String token = bearer(userId);
        String key = "open:" + UUID.randomUUID();

        String body = openRequest(token, key, "SAVE_90", 10_000_000, "RENEW_ALL")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(10_000_000))
                .andExpect(jsonPath("$.annualRatePercent").value(6.0))
                .andExpect(jsonPath("$.termDays").value(90))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.maturityOption").value("RENEW_ALL"))
                .andExpect(jsonPath("$.cycleNo").value(1))
                .andExpect(jsonPath("$.maturityDate").value(LocalDate.now(clock).plusDays(90).toString()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long accountId = ((Number) JsonPath.read(body, "$.id")).longValue();

        openRequest(token, key, "SAVE_90", 10_000_000, "RENEW_ALL")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(accountId));
        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.available").value(0));
        mockMvc.perform(get("/api/savings").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("R66: không đủ tiền 422, sản phẩm lạ 404, số tiền sai 400, thiếu key hoặc enum sai 400")
    void openAccountErrors() throws Exception {
        String token = bearer(createUserWithDeposit(1_000_000));

        openRequest(token, "k:" + UUID.randomUUID(), "SAVE_90", 2_000_000, "PAYOUT_ALL")
                .andExpect(status().isUnprocessableEntity());
        openRequest(token, "k:" + UUID.randomUUID(), "NO_SUCH", 1_000_000, "PAYOUT_ALL")
                .andExpect(status().isNotFound());
        openRequest(token, "k:" + UUID.randomUUID(), "SAVE_90", 999_999, "PAYOUT_ALL")
                .andExpect(status().isBadRequest());
        openRequest(token, null, "SAVE_90", 1_000_000, "PAYOUT_ALL").andExpect(status().isBadRequest());
        openRequest(token, "k:" + UUID.randomUUID(), "SAVE_90", 1_000_000, "TO_THE_MOON")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Chi tiết gói có lãi tạm tính; gói của người khác trả 404")
    void detailShowsAccruedInterestAndHidesForeignAccounts() throws Exception {
        long owner = createUserWithDeposit(10_000_000);
        long stranger = createUserWithDeposit(0);
        String ownerToken = bearer(owner);
        long accountId = openAndGetId(ownerToken, 10_000_000);

        mockMvc.perform(get("/api/savings/" + accountId).header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(jsonPath("$.accruedInterest").value(0));
        accrualService.accrueAccount(accountId, LocalDate.now(clock).plusDays(9));   // như thể đã qua 10 ngày
        mockMvc.perform(get("/api/savings/" + accountId).header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(jsonPath("$.accruedInterest").value(16_438));

        mockMvc.perform(get("/api/savings/" + accountId).header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/savings").header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("R16: đổi tùy chọn đáo hạn qua API; người khác không đổi được")
    void changeMaturityOptionThroughTheApi() throws Exception {
        long owner = createUserWithDeposit(10_000_000);
        long stranger = createUserWithDeposit(0);
        String ownerToken = bearer(owner);
        long accountId = openAndGetId(ownerToken, 10_000_000);

        mockMvc.perform(patch("/api/savings/" + accountId + "/maturity-option")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maturityOption\":\"RENEW_PRINCIPAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maturityOption").value("RENEW_PRINCIPAL"));
        mockMvc.perform(patch("/api/savings/" + accountId + "/maturity-option")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maturityOption\":\"RENEW_ALL\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("R23, R52: rút sớm sau 40 ngày hoàn gốc + 5.479đ lãi; gửi lại trả biên lai cũ")
    void earlyWithdrawalThroughTheApi() throws Exception {
        long userId = createUserWithDeposit(10_000_000);
        long stranger = createUserWithDeposit(0);
        String token = bearer(userId);
        long accountId = openAndGetId(token, 10_000_000);
        travelBack(accountId, 40);

        mockMvc.perform(post("/api/savings/" + accountId + "/early-withdrawal")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isNotFound());

        String body = mockMvc.perform(post("/api/savings/" + accountId + "/early-withdrawal")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("EARLY_WITHDRAWAL"))
                .andExpect(jsonPath("$.principal").value(10_000_000))
                .andExpect(jsonPath("$.interest").value(5_479))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long settlementId = ((Number) JsonPath.read(body, "$.id")).longValue();

        mockMvc.perform(post("/api/savings/" + accountId + "/early-withdrawal")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(settlementId));
        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.available").value(10_005_479));
    }

    @Test
    @DisplayName("R51, R66: đã quá ngày đáo hạn thì rút sớm trả 409")
    void earlyWithdrawalAfterMaturityIsAConflict() throws Exception {
        long userId = createUserWithDeposit(10_000_000);
        String token = bearer(userId);
        long accountId = openAndGetId(token, 10_000_000);
        travelBack(accountId, 100);

        mockMvc.perform(post("/api/savings/" + accountId + "/early-withdrawal")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict());
    }
}