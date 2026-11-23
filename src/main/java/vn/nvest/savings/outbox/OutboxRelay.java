package vn.nvest.savings.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Đọc sự kiện chưa gửi trong outbox và đẩy lên Kafka. Giao theo kiểu ít nhất một lần (at-least-once). */
@Component
@ConditionalOnProperty(name = "nvest.kafka.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_BATCHES_PER_RUN = 50;
    private static final String EVENT_VERSION = "1";

    public record RunResult(int published, boolean failed) {
    }

    private record Batch(int size, int published, boolean failed) {
    }

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxRelay(OutboxEventRepository repository,
                       KafkaTemplate<String, String> kafka,
                       PlatformTransactionManager transactionManager,
                       Clock clock,
                       @Value("${nvest.outbox.relay.batch-size:100}") int batchSize,
                       @Value("${nvest.outbox.relay.send-timeout-ms:5000}") long sendTimeoutMs) {
        this.repository = repository;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${nvest.outbox.relay.delay:PT1S}")
    public void runScheduled() {
        run();
    }

    /** Gọi thủ công được, thuận tiện cho test. */
    public RunResult run() {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            Batch batch = Objects.requireNonNull(tx.execute(status -> publishBatch()));
            total += batch.published();
            if (batch.failed()) {
                return new RunResult(total, true);
            }
            if (batch.size() < batchSize) {
                break;   // đã hết sự kiện chờ
            }
        }
        return new RunResult(total, false);
    }

    /** Một transaction ngắn: khóa lô, gửi lần lượt, đánh dấu những cái đã gửi xong. */
    private Batch publishBatch() {
        List<OutboxEvent> batch = repository.lockPending(batchSize);
        int published = 0;
        for (OutboxEvent event : batch) {
            if (!send(event)) {
                // Dừng ngay: gửi tiếp các sự kiện sau sẽ làm đảo thứ tự. Những cái đã gửi vẫn được commit.
                return new Batch(batch.size(), published, true);
            }
            event.markPublished(clock.instant());
            published++;
        }
        return new Batch(batch.size(), published, false);
    }

    private boolean send(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getAggregateKey(), event.getPayload());
        record.headers().add("event-id", event.getEventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", event.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-version", EVENT_VERSION.getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);   // chờ broker xác nhận (acks=all)
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while publishing outbox event {}", event.getEventId());
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Cannot publish outbox event {}: {}", event.getEventId(), e.toString());
            return false;
        }
    }
}