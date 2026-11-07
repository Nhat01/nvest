package vn.nvest.savings.payment;

import static org.hamcrest.Matchers.greaterThan;
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
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class PaymentApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockPaymentGateway gateway;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    private ResultActions postAmount(String url, String bearer, String idempotencyKey, long amount) throws Exception {
        var request = post(url).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}");
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Nạp qua API: tạo đơn PENDING, webhook xác nhận thì ví được cộng, gửi lại cùng key trả đơn cũ")
    void depositFlowThroughTheApi() throws Exception {
        long userId = createUserWithDeposit(0);
        String token = bearer(userId);
        String key = "dep:" + UUID.randomUUID();

        String body = bodyOf(postAmount("/api/deposits", token, key, 500_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.direction").value("DEPOSIT"))
                .andExpect(jsonPath("$.checkoutUrl").isNotEmpty()));
        long paymentId = ((Number) JsonPath.read(body, "$.id")).longValue();
        String orderCode = JsonPath.read(body, "$.orderCode");

        postAmount("/api/deposits", token, key, 500_000)   // gửi lại: cùng đơn
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(paymentId));

        MockPaymentGateway.SignedWebhook webhook = gateway.webhookFor(orderCode, GatewayStatus.SUCCEEDED, 500_000);
        mockMvc.perform(post("/webhooks/mock").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", webhook.signature()).content(webhook.payload()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.available").value(500_000));
        mockMvc.perform(get("/api/payments/" + paymentId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("R65, R27: thiếu Idempotency-Key, số tiền không hợp lệ hoặc dưới mức tối thiểu đều trả 400")
    void invalidDepositRequestsAreRejected() throws Exception {
        String token = bearer(createUserWithDeposit(0));

        postAmount("/api/deposits", token, null, 500_000).andExpect(status().isBadRequest());                 // thiếu header
        postAmount("/api/deposits", token, "k:" + UUID.randomUUID(), 0).andExpect(status().isBadRequest());     // @Positive
        postAmount("/api/deposits", token, "k:" + UUID.randomUUID(), 5_000).andExpect(status().isBadRequest()); // dưới 10.000đ
        mockMvc.perform(post("/api/deposits").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500000}")
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnauthorized());                                                          // thiếu token
    }

    @Test
    @DisplayName("R68: giao dịch của người khác trả 404, danh sách chỉ có của mình, mới nhất trước")
    void paymentsAreIsolatedPerUser() throws Exception {
        long alice = createUserWithDeposit(0);
        long bob = createUserWithDeposit(0);
        String aliceToken = bearer(alice);

        long first = ((Number) JsonPath.read(bodyOf(postAmount("/api/deposits", aliceToken,
                "k:" + UUID.randomUUID(), 100_000)), "$.id")).longValue();
        long second = ((Number) JsonPath.read(bodyOf(postAmount("/api/deposits", aliceToken,
                "k:" + UUID.randomUUID(), 200_000)), "$.id")).longValue();

        mockMvc.perform(get("/api/payments/" + first).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/payments").header(HttpHeaders.AUTHORIZATION, aliceToken))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first));
        mockMvc.perform(get("/api/payments").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Rút thành công trả 200 SUCCEEDED và ví bị trừ")
    void successfulWithdrawal() throws Exception {
        long userId = createUserWithDeposit(1_000_000);
        String token = bearer(userId);

        postAmount("/api/withdrawals", token, "wd:" + UUID.randomUUID(), 300_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.direction").value("WITHDRAW"));

        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.available").value(700_000))
                .andExpect(jsonPath("$.held").value(0));
    }

    @Test
    @DisplayName("R66: không đủ tiền trả 422 theo chuẩn problem+json, không lộ thông tin ví")
    void insufficientFundsIs422() throws Exception {
        String token = bearer(createUserWithDeposit(1_000_000));

        postAmount("/api/withdrawals", token, "wd:" + UUID.randomUUID(), 2_000_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Insufficient funds"))
                .andExpect(jsonPath("$.detail").value("Available balance is not enough for this operation"));
    }

    @Test
    @DisplayName("R30, R66: cổng timeout thì trả 202 UNKNOWN và tiền vẫn bị giữ")
    void ambiguousWithdrawalIs202() throws Exception {
        long userId = createUserWithDeposit(1_000_000);
        String token = bearer(userId);
        gateway.scriptNextPayout(MockPaymentGateway.PayoutBehavior.TIMEOUT_BUT_EXECUTED);

        postAmount("/api/withdrawals", token, "wd:" + UUID.randomUUID(), 300_000)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));

        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.available").value(700_000))
                .andExpect(jsonPath("$.held").value(300_000));
    }

    @Test
    @DisplayName("R65: gửi lại lệnh rút cùng key chỉ rút một lần")
    void withdrawalIsIdempotent() throws Exception {
        long userId = createUserWithDeposit(1_000_000);
        String token = bearer(userId);
        String key = "wd:" + UUID.randomUUID();

        long firstId = ((Number) JsonPath.read(bodyOf(postAmount("/api/withdrawals", token, key, 300_000)
                .andExpect(status().isOk())), "$.id")).longValue();
        postAmount("/api/withdrawals", token, key, 300_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId));

        mockMvc.perform(get("/api/wallet").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.available").value(700_000));
        mockMvc.perform(get("/api/payments").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.length()").value(1));
    }
}