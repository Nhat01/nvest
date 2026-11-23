package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

/**
 * VNPAY không có API chi tiền. Khi provider=vnpay, API rút phải trả lỗi rõ ràng
 * thay vì âm thầm dùng MockPaymentGateway để "chi" tiền giả.
 */
@TestPropertySource(properties = {
        "nvest.payment.provider=vnpay",
        "nvest.payment.vnpay.tmn-code=TEST",
        "nvest.payment.vnpay.hash-secret=test-secret",
        "nvest.payment.vnpay.return-url=http://localhost:3000/payment/result"
})
class WithdrawalUnavailableIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("provider=vnpay: rút tiền trả 501, không giữ tiền và không tạo giao dịch")
    void withdrawalIsRejectedWhenPayoutIsNotSupported() throws Exception {
        long userId = createUserWithDeposit(1_000_000);

        mockMvc.perform(post("/api/withdrawals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .header("Idempotency-Key", "wd:" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50000}"))
                .andExpect(status().isNotImplemented())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Withdrawals not supported"));

        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(1_000_000))
                .andExpect(jsonPath("$.held").value(0));

        Long withdrawals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions WHERE direction = 'WITHDRAW' AND user_id = ?",
                Long.class, userId);
        assertThat(withdrawals).isZero();
    }
}
