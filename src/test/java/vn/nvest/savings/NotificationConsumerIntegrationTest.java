package vn.nvest.savings.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import vn.nvest.savings.outbox.EventTypes;
import vn.nvest.savings.outbox.OutboxRelay;
import vn.nvest.savings.outbox.Topics;
import vn.nvest.savings.payment.DepositService;
import vn.nvest.savings.payment.GatewayStatus;
import vn.nvest.savings.payment.MockPaymentGateway;
import vn.nvest.savings.payment.PaymentTransaction;
import vn.nvest.savings.savings.MaturityOption;
import vn.nvest.savings.savings.MaturityService;
import vn.nvest.savings.savings.SavingsAccount;
import vn.nvest.savings.savings.SavingsService;
import vn.nvest.savings.support.AbstractKafkaIntegrationTest;

class NotificationConsumerIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private OutboxRelay relay;
    @Autowired private DepositService depositService;
    @Autowired private MockPaymentGateway gateway;
    @Autowired private SavingsService savingsService;
    @Autowired private MaturityService maturityService;

    private void send(String topic, long userId, String eventId, String eventType, String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, "user:" + userId, payload);
        record.headers().add("event-id", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private static String depositJson(long userId, long amount, String orderCode) {
        return "{\"paymentId\":1,\"userId\":" + userId + ",\"amount\":" + amount
                + ",\"orderCode\":\"" + orderCode + "\"}";
    }

    private long notifications(long userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, userId);
        return count;
    }

    private void awaitNotifications(long userId, long atLeast) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(notifications(userId)).isGreaterThanOrEqualTo(atLeast));
    }

    @Test
    @DisplayName("Đầu cuối: nạp thành công, relay gửi sự kiện, consumer tạo thông báo")
    void depositProducesANotification() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = depositService.createDeposit(userId, 500_000, "dep:" + UUID.randomUUID()).payment();
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(payment.getOrderCode(), GatewayStatus.SUCCEEDED, 500_000);
        depositService.handleWebhook(webhook.payload(), webhook.signature());

        relay.run();

        awaitNotifications(userId, 1);
        String message = jdbc.queryForObject(
                "SELECT message FROM notifications WHERE user_id = ?", String.class, userId);
        assertThat(message).contains("500.000đ");
    }

    @Test
    @DisplayName("R18: gói tự chuyển sang PAYOUT_ALL thì user được báo kèm lý do")
    void fallbackMaturityNotifiesTheReason() {
        long userId = createUserWithDeposit(5_000_000);
        SavingsAccount account = savingsService.open(userId, "FLEX_30", 5_000_000,
                MaturityOption.RENEW_ALL, "open:" + UUID.randomUUID());
        try {
            jdbc.update("UPDATE savings_products SET status = 'STOPPED' WHERE code = 'FLEX_30'");
            maturityService.settle(account.getId(), account.getMaturityDate()).orElseThrow();
        } finally {
            jdbc.update("UPDATE savings_products SET status = 'ON_SALE' WHERE code = 'FLEX_30'");
        }

        relay.run();

        awaitNotifications(userId, 1);
        String message = jdbc.queryForObject(
                "SELECT message FROM notifications WHERE user_id = ?", String.class, userId);
        assertThat(message).contains("không thể tái tục").contains("ngừng bán");
    }

    @Test
    @DisplayName("Sự kiện giao trùng chỉ tạo một thông báo (khử trùng theo event-id)")
    void duplicateDeliveryCreatesOneNotification() throws Exception {
        long userId = createUserWithDeposit(0);
        String dupId = "deposit-succeeded:DEP-DUP-" + UUID.randomUUID();
        String payload = depositJson(userId, 100_000, "DEP-DUP");

        send(Topics.PAYMENT_EVENTS, userId, dupId, EventTypes.DEPOSIT_SUCCEEDED, payload);
        send(Topics.PAYMENT_EVENTS, userId, dupId, EventTypes.DEPOSIT_SUCCEEDED, payload);
        // Sự kiện "mốc": cùng key nên cùng partition, và Kafka giữ thứ tự trong partition.
        // Thấy mốc được xử lý nghĩa là hai bản ghi trùng phía trước chắc chắn đã được xử lý.
        send(Topics.PAYMENT_EVENTS, userId, "deposit-succeeded:DEP-MARK-" + UUID.randomUUID(),
                EventTypes.DEPOSIT_SUCCEEDED, depositJson(userId, 200_000, "DEP-MARK"));

        awaitNotifications(userId, 2);
        assertThat(notifications(userId)).isEqualTo(2);   // 1 cho sự kiện trùng + 1 cho mốc
    }

    @Test
    @DisplayName("Bản ghi hỏng chuyển sang DLT, và không chặn các sự kiện sau nó")
    void poisonMessageGoesToDeadLetterTopic() throws Exception {
        long userId = createUserWithDeposit(0);
        send(Topics.PAYMENT_EVENTS, userId, "poison:" + UUID.randomUUID(),
                EventTypes.DEPOSIT_SUCCEEDED, "{ this is not json");
        send(Topics.PAYMENT_EVENTS, userId, "deposit-succeeded:DEP-OK-" + UUID.randomUUID(),
                EventTypes.DEPOSIT_SUCCEEDED, depositJson(userId, 300_000, "DEP-OK"));

        awaitNotifications(userId, 1);   // sự kiện hợp lệ sau bản ghi hỏng vẫn được xử lý

        List<ConsumerRecord<String, String>> dead = consume(Topics.PAYMENT_EVENTS + "-dlt", "user:" + userId);
        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).value()).isEqualTo("{ this is not json");
        assertThat(new String(dead.get(0).headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                StandardCharsets.UTF_8)).isEqualTo(Topics.PAYMENT_EVENTS);
        assertThat(notifications(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Loại sự kiện chưa biết bị bỏ qua, không vào DLT, không làm kẹt consumer")
    void unknownEventTypeIsIgnored() throws Exception {
        long userId = createUserWithDeposit(0);
        send(Topics.PAYMENT_EVENTS, userId, "future:" + UUID.randomUUID(), "SomethingFromTheFuture", "{}");
        send(Topics.PAYMENT_EVENTS, userId, "deposit-succeeded:DEP-AFTER-" + UUID.randomUUID(),
                EventTypes.DEPOSIT_SUCCEEDED, depositJson(userId, 100_000, "DEP-AFTER"));

        awaitNotifications(userId, 1);

        assertThat(notifications(userId)).isEqualTo(1);
    }
}