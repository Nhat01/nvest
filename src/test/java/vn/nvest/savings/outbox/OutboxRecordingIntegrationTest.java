package vn.nvest.savings.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import vn.nvest.savings.common.PaymentAmountMismatchException;
import vn.nvest.savings.payment.DepositService;
import vn.nvest.savings.payment.GatewayStatus;
import vn.nvest.savings.payment.MockPaymentGateway;
import vn.nvest.savings.payment.PaymentTransaction;
import vn.nvest.savings.savings.MaturityOption;
import vn.nvest.savings.savings.MaturityService;
import vn.nvest.savings.savings.SavingsAccount;
import vn.nvest.savings.savings.SavingsService;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class OutboxRecordingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private DepositService depositService;
    @Autowired private MockPaymentGateway gateway;
    @Autowired private OutboxService outboxService;
    @Autowired private SavingsService savingsService;
    @Autowired private MaturityService maturityService;

    private long outboxRows(String eventId) {
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_id = ?", Long.class, eventId);
        return rows;
    }

    private PaymentTransaction newDeposit(long userId, long amount) {
        return depositService.createDeposit(userId, amount, "dep:" + UUID.randomUUID()).payment();
    }

    @Test
    @DisplayName("Nạp thành công ghi đúng một sự kiện DepositSucceeded, chưa gửi, key theo user")
    void successfulDepositWritesOneEvent() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(payment.getOrderCode(), GatewayStatus.SUCCEEDED, 500_000);

        depositService.handleWebhook(webhook.payload(), webhook.signature());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT topic, event_type, aggregate_key, payload, published_at "
                        + "FROM outbox_events WHERE event_id = ?",
                "deposit-succeeded:" + payment.getOrderCode());
        assertThat(row.get("topic")).isEqualTo(Topics.PAYMENT_EVENTS);
        assertThat(row.get("event_type")).isEqualTo(EventTypes.DEPOSIT_SUCCEEDED);
        assertThat(row.get("aggregate_key")).isEqualTo("user:" + userId);
        assertThat(row.get("published_at")).isNull();
        assertThat((String) row.get("payload")).contains("\"amount\":500000");
    }

    @Test
    @DisplayName("Webhook gửi trùng nhiều lần vẫn chỉ có một sự kiện")
    void duplicateWebhooksWriteOneEvent() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(payment.getOrderCode(), GatewayStatus.SUCCEEDED, 500_000);

        depositService.handleWebhook(webhook.payload(), webhook.signature());
        depositService.handleWebhook(webhook.payload(), webhook.signature());
        depositService.handleWebhook(webhook.payload(), webhook.signature());

        assertThat(outboxRows("deposit-succeeded:" + payment.getOrderCode())).isEqualTo(1);
    }

    @Test
    @DisplayName("Transaction bị hủy (số tiền lệch) thì KHÔNG có sự kiện nào: sự kiện sống chết cùng nghiệp vụ")
    void rolledBackTransactionLeavesNoEvent() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = newDeposit(userId, 500_000);
        MockPaymentGateway.SignedWebhook wrongAmount =
                gateway.webhookFor(payment.getOrderCode(), GatewayStatus.SUCCEEDED, 400_000);

        assertThatThrownBy(() -> depositService.handleWebhook(wrongAmount.payload(), wrongAmount.signature()))
                .isInstanceOf(PaymentAmountMismatchException.class);

        assertThat(outboxRows("deposit-succeeded:" + payment.getOrderCode())).isZero();
    }

    @Test
    @DisplayName("Ghi sự kiện ngoài transaction bị từ chối (Propagation.MANDATORY)")
    void recordingOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> outboxService.record(
                Topics.PAYMENT_EVENTS, "Test", "test:" + UUID.randomUUID(), "user:1", Map.of("a", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("Đáo hạn ghi SavingsMatured kèm phương án thực tế đã áp dụng")
    void maturityWritesAnEvent() {
        long userId = createUserWithDeposit(10_000_000);
        SavingsAccount account = savingsService.open(userId, "SAVE_90", 10_000_000,
                MaturityOption.PAYOUT_ALL, "open:" + UUID.randomUUID());

        maturityService.settle(account.getId(), account.getMaturityDate()).orElseThrow();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT topic, payload FROM outbox_events WHERE event_id = ?", "savings-matured:" + account.getId());
        assertThat(row.get("topic")).isEqualTo(Topics.SAVINGS_EVENTS);
        assertThat((String) row.get("payload"))
                .contains("\"interest\":147945")
                .contains("\"optionApplied\":\"PAYOUT_ALL\"");
    }
}