package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class SavingsSimulationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final long PRINCIPAL = 10_000_000;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SavingsService savingsService;

    @Autowired
    private EarlyWithdrawalService earlyWithdrawalService;

    @Autowired
    private SavingsAccountRepository accountRepository;

    @Autowired
    private SavingsProductRepository productRepository;

    @Autowired
    private Clock clock;

    private long openAccount(long userId) {
        return savingsService.open(userId, "SAVE_90", PRINCIPAL, MaturityOption.PAYOUT_ALL,
                "sim:" + UUID.randomUUID()).getId();
    }

    /** Dời cả ngày bắt đầu và đáo hạn lùi days ngày, như thể gói đã mở từ trước. */
    private void travelBack(long accountId, int days) {
        jdbc.update("UPDATE savings_accounts SET start_date = start_date - " + days
                + ", maturity_date = maturity_date - " + days + " WHERE id = ?", accountId);
    }

    @Test
    @DisplayName("Máy tính lãi sản phẩm: lãi cả kỳ theo R8, rút ngay trong ngày thì lãi 0")
    void productSimulationDefaultsToToday() throws Exception {
        long userId = createUserWithDeposit(0);
        LocalDate today = LocalDate.now(clock);

        mockMvc.perform(get("/api/savings/products/SAVE_90/simulation?amount=" + PRINCIPAL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value("SAVE_90"))
                .andExpect(jsonPath("$.annualRatePercent").value(6.0))
                .andExpect(jsonPath("$.demandRatePercent").value(0.5))
                .andExpect(jsonPath("$.termDays").value(90))
                .andExpect(jsonPath("$.startDate").value(today.toString()))
                .andExpect(jsonPath("$.maturityDate").value(today.plusDays(90).toString()))
                .andExpect(jsonPath("$.asOfDate").value(today.toString()))
                .andExpect(jsonPath("$.daysHeld").value(0))
                .andExpect(jsonPath("$.daysRemaining").value(90))
                .andExpect(jsonPath("$.maturityInterest").value(147_945))
                .andExpect(jsonPath("$.maturityPayout").value(10_147_945))
                .andExpect(jsonPath("$.earlyWithdrawalAvailable").value(true))
                .andExpect(jsonPath("$.earlyWithdrawalInterest").value(0))
                .andExpect(jsonPath("$.earlyWithdrawalPayout").value(10_000_000))
                .andExpect(jsonPath("$.interestLoss").value(147_945));
    }

    @Test
    @DisplayName("Mô phỏng sau 40 ngày khớp ví dụ R23: lãi sớm 5.479đ, mất 142.466đ so với đáo hạn")
    void productSimulationAfterFortyDays() throws Exception {
        long userId = createUserWithDeposit(0);
        LocalDate start = LocalDate.now(clock).minusDays(40);

        mockMvc.perform(get("/api/savings/products/SAVE_90/simulation?amount=" + PRINCIPAL
                        + "&startDate=" + start)
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysHeld").value(40))
                .andExpect(jsonPath("$.daysRemaining").value(50))
                .andExpect(jsonPath("$.maturityInterest").value(147_945))
                .andExpect(jsonPath("$.earlyWithdrawalInterest").value(5_479))
                .andExpect(jsonPath("$.earlyWithdrawalPayout").value(10_005_479))
                .andExpect(jsonPath("$.interestLoss").value(142_466));
    }

    @Test
    @DisplayName("Mô phỏng gói dùng lãi suất đã chốt, không đổi trạng thái hay số dư")
    void accountSimulationIsReadOnly() throws Exception {
        long userId = createUserWithDeposit(PRINCIPAL);
        long accountId = openAccount(userId);
        travelBack(accountId, 40);
        String token = bearer(userId);

        mockMvc.perform(get("/api/savings/" + accountId + "/early-withdrawal-simulation")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysHeld").value(40))
                .andExpect(jsonPath("$.daysRemaining").value(50))
                .andExpect(jsonPath("$.maturityInterest").value(147_945))
                .andExpect(jsonPath("$.earlyWithdrawalInterest").value(5_479))
                .andExpect(jsonPath("$.earlyWithdrawalPayout").value(10_005_479));

        LocalDate start = accountRepository.findById(accountId).orElseThrow().getStartDate();
        mockMvc.perform(get("/api/savings/" + accountId + "/early-withdrawal-simulation?asOfDate="
                        + start.plusDays(10))
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.daysHeld").value(10))
                .andExpect(jsonPath("$.daysRemaining").value(80))
                .andExpect(jsonPath("$.earlyWithdrawalInterest").value(1_369))
                .andExpect(jsonPath("$.interestLoss").value(146_576));

        assertThat(accountRepository.findById(accountId).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.ACTIVE);
        assertThat(walletService.getBalance(userId).available()).isZero();

        Settlement settlement = earlyWithdrawalService.withdraw(userId, accountId);
        assertThat(settlement.getInterest()).isEqualTo(5_479);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(10_005_479);
    }

    @Test
    @DisplayName("R5: đổi lãi suất sản phẩm sau khi mở gói không làm thay đổi mô phỏng của gói đã mở")
    void accountSimulationKeepsLockedRate() throws Exception {
        long userId = createUserWithDeposit(PRINCIPAL);
        String code = "SIM" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        SavingsProduct product = productRepository.save(new SavingsProduct(
                code, "Simulation rate lock", 90, new BigDecimal("0.0600"), 1_000_000, 500_000_000));
        long accountId = savingsService.open(userId, code, PRINCIPAL, MaturityOption.PAYOUT_ALL,
                "sim-lock:" + UUID.randomUUID()).getId();

        product.changeAnnualRate(new BigDecimal("0.0700"));
        productRepository.saveAndFlush(product);

        // Gói đã mở giữ nguyên 6% đã chốt lúc mở, dù sản phẩm đã lên 7%.
        mockMvc.perform(get("/api/savings/" + accountId + "/early-withdrawal-simulation")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualRatePercent").value(6.0))
                .andExpect(jsonPath("$.maturityInterest").value(147_945));

        // Máy tính cho khoản gửi mới lấy lãi suất hiện hành 7%.
        mockMvc.perform(get("/api/savings/products/" + code + "/simulation?amount=" + PRINCIPAL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualRatePercent").value(7.0))
                .andExpect(jsonPath("$.maturityInterest").value(172_602));
    }

    @Test
    @DisplayName("Đã tới ngày đáo hạn thì không còn rút sớm; các trường earlyWithdrawal là null")
    void simulationAtMaturityHasNoEarlyWithdrawal() throws Exception {
        long userId = createUserWithDeposit(0);
        LocalDate maturity = LocalDate.now(clock).plusDays(90);

        mockMvc.perform(get("/api/savings/products/SAVE_90/simulation?amount=" + PRINCIPAL
                        + "&asOfDate=" + maturity)
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysHeld").value(90))
                .andExpect(jsonPath("$.daysRemaining").value(0))
                .andExpect(jsonPath("$.maturityInterest").value(147_945))
                .andExpect(jsonPath("$.earlyWithdrawalAvailable").value(false))
                .andExpect(jsonPath("$.earlyWithdrawalInterest").value(nullValue()))
                .andExpect(jsonPath("$.earlyWithdrawalPayout").value(nullValue()))
                .andExpect(jsonPath("$.interestLoss").value(nullValue()));
    }

    @Test
    @DisplayName("Mô phỏng gói người khác trả 404, gói không ACTIVE trả 409, ngày trước khi mở trả 400")
    void accountSimulationRejectsInvalidCases() throws Exception {
        long owner = createUserWithDeposit(PRINCIPAL);
        long stranger = createUserWithDeposit(0);
        long accountId = openAccount(owner);

        mockMvc.perform(get("/api/savings/" + accountId + "/early-withdrawal-simulation")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isNotFound());

        LocalDate start = accountRepository.findById(accountId).orElseThrow().getStartDate();
        mockMvc.perform(get("/api/savings/" + accountId + "/early-withdrawal-simulation?asOfDate="
                        + start.minusDays(1))
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isBadRequest());

        earlyWithdrawalService.withdraw(owner, accountId);
        mockMvc.perform(get("/api/savings/" + accountId + "/early-withdrawal-simulation")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Máy tính sản phẩm: mã lạ 404, số tiền ngoài R3 400, asOf trước startDate 400")
    void productSimulationValidatesInput() throws Exception {
        long userId = createUserWithDeposit(0);
        String token = bearer(userId);

        mockMvc.perform(get("/api/savings/products/NO_SUCH/simulation?amount=" + PRINCIPAL)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/savings/products/SAVE_90/simulation?amount=999999")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());

        LocalDate start = LocalDate.now(clock);
        mockMvc.perform(get("/api/savings/products/SAVE_90/simulation?amount=" + PRINCIPAL
                        + "&startDate=" + start + "&asOfDate=" + start.minusDays(1))
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
    }
}
