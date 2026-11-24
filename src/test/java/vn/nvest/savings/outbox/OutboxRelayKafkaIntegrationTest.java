package vn.nvest.savings.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.nvest.savings.payment.DepositService;
import vn.nvest.savings.payment.GatewayStatus;
import vn.nvest.savings.payment.MockPaymentGateway;
import vn.nvest.savings.payment.PaymentTransaction;
import vn.nvest.savings.support.AbstractKafkaIntegrationTest;

class OutboxRelayKafkaIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired private OutboxRelay relay;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private DepositService depositService;
    @Autowired private MockPaymentGateway gateway;

    @Test
    @DisplayName("Relay đẩy sự kiện lên Kafka với đúng key, header, nội dung; chạy lại không gửi lần hai")
    void relayPublishesToKafkaExactlyOncePerRun() {
        long userId = createUserWithDeposit(0);
        PaymentTransaction payment = depositService.createDeposit(userId, 500_000, "dep:" + UUID.randomUUID()).payment();
        MockPaymentGateway.SignedWebhook webhook =
                gateway.webhookFor(payment.getOrderCode(), GatewayStatus.SUCCEEDED, 500_000);
        depositService.handleWebhook(webhook.payload(), webhook.signature());

        OutboxRelay.RunResult first = relay.run();
        OutboxRelay.RunResult second = relay.run();

        assertThat(first.failed()).isFalse();
        assertThat(first.published()).isGreaterThanOrEqualTo(1);
        assertThat(second.published()).isZero();
        assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();

        List<ConsumerRecord<String, String>> records = consume(Topics.PAYMENT_EVENTS, "user:" + userId);
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        assertThat(record.value()).contains("\"amount\":500000").contains(payment.getOrderCode());
        assertThat(new String(record.headers().lastHeader("event-id").value(), StandardCharsets.UTF_8))
                .isEqualTo("deposit-succeeded:" + payment.getOrderCode());
        assertThat(new String(record.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
                .isEqualTo(EventTypes.DEPOSIT_SUCCEEDED);
    }
}