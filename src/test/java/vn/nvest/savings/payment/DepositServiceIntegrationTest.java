package vn.nvest.savings.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.common.IdempotencyConflictException;
import vn.nvest.savings.common.InvalidSignatureException;
import vn.nvest.savings.common.PaymentAmountMismatchException;
import vn.nvest.savings.common.PaymentNotFoundException;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;
import vn.nvest.savings.support.ParallelRunner;

class DepositServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DepositService depositService;

    @Autowired
    private MockPaymentGateway gateway;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    private DepositService.DepositSession newDeposit(long userId, long amount) {
        return depositService.createDeposit(userId, amount, "dep:" + UUID.randomUUID());
    }

    private PaymentTransaction reload(long paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private long ledgerRows(String orderCode) {
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Long.class, "deposit:" + orderCode);
        return rows;
    }

    @Test
    @DisplayName("Tạo đơn nạp: PENDING, có mã đơn và đường dẫn thanh toán, chưa có đồng nào vào ví")
    void createDepositStartsPendingAndMovesNoMoney() {
        long userId = createUserWithDeposit(0);

        DepositService.DepositSession session = newDeposit(userId, 500_000);

        PaymentTransaction payment = reload(session.payment().getId());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getDirection()).isEqualTo(PaymentDirection.DEPOSIT);
        assertThat(payment.getOrderCode()).startsWith("DEP-");
        assertThat(payment.getProvider()).isEqualTo("MOCK");
        assertThat(payment.getProviderRef()).isNotBlank();
        assertThat(session.checkoutUrl()).contains(payment.getOrderCode());
        assertThat(walletService.getBalance(userId).available()).isZero();
    }

    @Test
    @DisplayName("R27: nạp tối thiểu 10.000đ, tối đa 100.000.000đ")
    void depositLimits() {
        long userId = createUserWithDeposit(0);

        assertThatThrownBy(() -> newDeposit(userId, 9_999)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newDeposit(userId, 100_000_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> newDeposit(userId, 10_000)).doesNotThrowAnyException();
        assertThatCode(() -> newDeposit(userId, 100_000_000)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R54: gửi lại cùng key trả đơn cũ; cùng key khác số tiền bị từ chối")
    void createDepositIsIdempotent() {
        long userId = createUserWithDeposit(0);
        String key = "dep:" + UUID.randomUUID();

        DepositService.DepositSession first = depositService.createDeposit(userId, 500_000, key);
        DepositService.DepositSession second = depositService.createDeposit(userId, 500_000, key);

        assertThat(second.payment().getId()).isEqualTo(first.payment().getId());
        assertThat(second.checkoutUrl()).isEqualTo(first.checkoutUrl());
        assertThatThrownBy(() -> depositService.createDeposit(userId, 600_000, key))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("R27: webhook hợp lệ cộng tiền vào ví, ghi tham chiếu về giao dịch thanh toán")
    void validWebhookCreditsWallet() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction created = newDeposit(userId, 500_000).payment();
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(created.getOrderCode(), GatewayStatus.SUCCEEDED, 500_000);

        depositService.handleWebhook(webhook.payload(), webhook.signature());

        assertThat(walletService.getBalance(userId).available()).isEqualTo(500_000);
        PaymentTransaction payment = reload(created.getId());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getRawPayload()).isEqualTo(webhook.payload());
        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT tx_type, reference_type, reference_id FROM ledger_transactions WHERE idempotency_key = ?",
                "deposit:" + created.getOrderCode());
        assertThat(ledger.get("tx_type")).isEqualTo("DEPOSIT");
        assertThat(ledger.get("reference_type")).isEqualTo("PAYMENT");
        assertThat(ledger.get("reference_id")).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("R28: webhook gửi trùng nhiều lần chỉ cộng tiền một lần")
    void duplicateWebhookCreditsOnce() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction created = newDeposit(userId, 500_000).payment();
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(created.getOrderCode(), GatewayStatus.SUCCEEDED, 500_000);

        depositService.handleWebhook(webhook.payload(), webhook.signature());
        depositService.handleWebhook(webhook.payload(), webhook.signature());
        depositService.handleWebhook(webhook.payload(), webhook.signature());

        assertThat(walletService.getBalance(userId).available()).isEqualTo(500_000);
        assertThat(ledgerRows(created.getOrderCode())).isEqualTo(1);
    }

    @Test
    @DisplayName("R55: chữ ký sai, thiếu, hoặc nội dung bị sửa thì bị từ chối và không đổi gì")
    void invalidSignatureIsRejected() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction created = newDeposit(userId, 500_000).payment();
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(created.getOrderCode(), GatewayStatus.SUCCEEDED, 500_000);
        String tamperedPayload = webhook.payload().replace("\"amount\":500000", "\"amount\":900000");

        assertThatThrownBy(() -> depositService.handleWebhook(webhook.payload(), "deadbeef"))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> depositService.handleWebhook(webhook.payload(), null))
                .isInstanceOf(InvalidSignatureException.class);
        // Giữ chữ ký cũ nhưng sửa nội dung (đổi số tiền): chữ ký không còn khớp
        assertThatThrownBy(() -> depositService.handleWebhook(tamperedPayload, webhook.signature()))
                .isInstanceOf(InvalidSignatureException.class);

        assertThat(walletService.getBalance(userId).available()).isZero();
        assertThat(reload(created.getId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("R56: số tiền webhook khác số tiền đơn thì không cộng tiền, đơn giữ PENDING")
    void amountMismatchIsRejected() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction created = newDeposit(userId, 500_000).payment();
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(created.getOrderCode(), GatewayStatus.SUCCEEDED, 400_000);   // chữ ký đúng, số tiền sai

        assertThatThrownBy(() -> depositService.handleWebhook(webhook.payload(), webhook.signature()))
                .isInstanceOf(PaymentAmountMismatchException.class);

        assertThat(walletService.getBalance(userId).available()).isZero();
        assertThat(reload(created.getId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Webhook cho đơn không tồn tại hoặc nội dung hỏng bị từ chối")
    void unknownOrderAndMalformedPayloadAreRejected() {
        MockPaymentGateway.SignedWebhook unknown = gateway.webhookFor("DEP-NOPE", GatewayStatus.SUCCEEDED, 1_000);
        assertThatThrownBy(() -> depositService.handleWebhook(unknown.payload(), unknown.signature()))
                .isInstanceOf(PaymentNotFoundException.class);

        String garbage = "this is not json";   // chữ ký đúng nhưng nội dung vô nghĩa
        assertThatThrownBy(() -> depositService.handleWebhook(garbage, gateway.sign(garbage)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("R55: FAILED rồi SUCCEEDED muộn vẫn cộng tiền; FAILED đến sau SUCCEEDED bị bỏ qua")
    void gatewayIsTheSourceOfTruth() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction created = newDeposit(userId, 500_000).payment();
        String orderCode = created.getOrderCode();

        MockPaymentGateway.SignedWebhook failed = gateway.webhookFor(orderCode, GatewayStatus.FAILED, 500_000);
        depositService.handleWebhook(failed.payload(), failed.signature());
        assertThat(reload(created.getId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(walletService.getBalance(userId).available()).isZero();

        MockPaymentGateway.SignedWebhook lateSuccess = gateway.webhookFor(orderCode, GatewayStatus.SUCCEEDED, 500_000);
        depositService.handleWebhook(lateSuccess.payload(), lateSuccess.signature());
        assertThat(reload(created.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(500_000);

        depositService.handleWebhook(failed.payload(), failed.signature());   // FAILED đến sau: bỏ qua
        assertThat(reload(created.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("R28: 8 webhook giống hệt nhau đến cùng lúc chỉ cộng tiền một lần")
    void concurrentDuplicateWebhooksCreditOnce() throws Exception {
        long userId = createUserWithDeposit(0);
        PaymentTransaction created = newDeposit(userId, 500_000).payment();
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(created.getOrderCode(), GatewayStatus.SUCCEEDED, 500_000);

        List<PaymentTransaction> results = ParallelRunner.run(8,
                () -> depositService.handleWebhook(webhook.payload(), webhook.signature()));

        assertThat(results).hasSize(8);
        assertThat(walletService.getBalance(userId).available()).isEqualTo(500_000);
        assertThat(ledgerRows(created.getOrderCode())).isEqualTo(1);
        assertThat(reload(created.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}