package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class PaymentWebhookControllerTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DepositService depositService;

    @Autowired
    private MockPaymentGateway gateway;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    private ResultActions callWebhook(String payload, String signature) throws Exception {
        var request = post("/webhooks/mock").contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signature != null) {
            request = request.header("X-Signature", signature);
        }
        return mockMvc.perform(request);
    }

    private PaymentTransaction newDeposit(long userId, long amount) {
        return depositService.createDeposit(userId, amount, "dep:" + UUID.randomUUID()).payment();
    }

    @Test
    @DisplayName("Webhook hợp lệ trả 200 và cộng tiền; gửi trùng vẫn trả 200")
    void validWebhookReturns200() throws Exception {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(payment.getOrderCode(), GatewayStatus.SUCCEEDED, 500_000);

        callWebhook(webhook.payload(), webhook.signature()).andExpect(status().isOk());
        callWebhook(webhook.payload(), webhook.signature()).andExpect(status().isOk());

        assertThat(walletService.getBalance(userId).available()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("Sai chữ ký trả 401, thiếu chữ ký cũng 401")
    void badOrMissingSignatureReturns401() throws Exception {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(payment.getOrderCode(), GatewayStatus.SUCCEEDED, 500_000);

        callWebhook(webhook.payload(), "deadbeef").andExpect(status().isUnauthorized());
        callWebhook(webhook.payload(), null).andExpect(status().isUnauthorized());

        assertThat(walletService.getBalance(userId).available()).isZero();
    }

    @Test
    @DisplayName("Đơn không tồn tại trả 404, số tiền lệch trả 422, nội dung hỏng trả 400")
    void errorStatusCodes() throws Exception {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);

        MockPaymentGateway.SignedWebhook unknown = gateway.webhookFor("DEP-NOPE", GatewayStatus.SUCCEEDED, 1_000);
        callWebhook(unknown.payload(), unknown.signature()).andExpect(status().isNotFound());

        MockPaymentGateway.SignedWebhook wrongAmount =
                gateway.webhookFor(payment.getOrderCode(), GatewayStatus.SUCCEEDED, 400_000);
        callWebhook(wrongAmount.payload(), wrongAmount.signature()).andExpect(status().isUnprocessableEntity());

        String garbage = "{ not json";
        callWebhook(garbage, gateway.sign(garbage)).andExpect(status().isBadRequest());
    }
}