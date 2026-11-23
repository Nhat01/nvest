package vn.nvest.savings.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import vn.nvest.savings.payment.DepositService;
import vn.nvest.savings.payment.GatewayStatus;
import vn.nvest.savings.payment.MockPaymentGateway;
import vn.nvest.savings.payment.PaymentTransaction;
import vn.nvest.savings.support.AbstractPostgresIntegrationTest;

class OutboxRelayKafkaIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * Kafka 3.9.0 coi listener CONTROLLER bind trên 0.0.0.0 là địa chỉ advertise
     * (lỗi KAFKA-18281), làm bước format storage của image apache/kafka chết ngay khi khởi động.
     * Bind implicit (bỏ host) để né lỗi; gỡ override khi nâng lên 3.9.1+.
     */
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.0")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("nvest.kafka.enabled", () -> "true");
    }

    @Autowired private OutboxRelay relay;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private DepositService depositService;
    @Autowired private MockPaymentGateway gateway;

    /** Đọc topic từ đầu, chỉ giữ các bản ghi có đúng key (DB dùng chung nên topic còn sự kiện của test khác). */
    private List<ConsumerRecord<String, String>> consume(String topic, String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (found.isEmpty() && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

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