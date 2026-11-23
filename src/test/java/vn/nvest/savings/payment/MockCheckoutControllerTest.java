package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class MockCheckoutControllerTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockPaymentGateway gateway;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    private record Deposit(long paymentId, String orderCode, String checkoutUrl) {
    }

    private Deposit createDeposit(long userId, long amount) throws Exception {
        String body = mockMvc.perform(post("/api/deposits")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .header("Idempotency-Key", "dep:" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Deposit(((Number) JsonPath.read(body, "$.id")).longValue(),
                JsonPath.read(body, "$.orderCode"), JsonPath.read(body, "$.checkoutUrl"));
    }

    @Test
    @DisplayName("Trang checkout giả mở công khai; bấm Thanh toán cộng ví đúng một lần")
    void checkoutPageCanPayWithoutToken() throws Exception {
        long userId = createUserWithDeposit(0);
        Deposit deposit = createDeposit(userId, 500_000);

        assertThat(deposit.checkoutUrl()).endsWith("/mock-gateway/pay/" + deposit.orderCode());

        mockMvc.perform(get("/mock-gateway/pay/" + deposit.orderCode()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("CỔNG GIẢ")))
                .andExpect(content().string(containsString("500.000")));

        mockMvc.perform(post("/mock-gateway/pay/" + deposit.orderCode() + "/pay"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(jsonPath("$.available").value(500_000));
        mockMvc.perform(get("/api/payments/" + deposit.paymentId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        // Bấm lại: webhook gửi trùng, không cộng thêm (R28)
        mockMvc.perform(post("/mock-gateway/pay/" + deposit.orderCode() + "/pay"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(jsonPath("$.available").value(500_000));
    }

    @Test
    @DisplayName("Huỷ trên trang checkout giả đánh dấu FAILED và không cộng tiền")
    void cancelDoesNotCreditWallet() throws Exception {
        long userId = createUserWithDeposit(0);
        Deposit deposit = createDeposit(userId, 500_000);

        mockMvc.perform(post("/mock-gateway/pay/" + deposit.orderCode() + "/cancel"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(jsonPath("$.available").value(0));
        mockMvc.perform(get("/api/payments/" + deposit.paymentId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @DisplayName("Mã đơn không tồn tại trả trang 404")
    void unknownOrderShowsNotFoundPage() throws Exception {
        mockMvc.perform(get("/mock-gateway/pay/DEP-KHONGCO"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }
}
