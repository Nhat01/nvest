package vn.nvest.savings.notification;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.nvest.savings.outbox.Topics;

@Component
@ConditionalOnProperty(name = "nvest.kafka.enabled", havingValue = "true")
public class NotificationListener {

    private final NotificationService notificationService;

    public NotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(
            id = "notification-consumer",
            groupId = "nvest-notification",
            topics = {Topics.PAYMENT_EVENTS, Topics.SAVINGS_EVENTS},
            concurrency = "3")
    public void onEvent(ConsumerRecord<String, String> record) {
        String eventId = header(record, "event-id");
        String eventType = header(record, "event-type");
        if (eventId == null || eventType == null) {
            throw new IllegalArgumentException("Missing event-id or event-type header");
        }
        notificationService.handle(eventId, eventType, record.value());
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}