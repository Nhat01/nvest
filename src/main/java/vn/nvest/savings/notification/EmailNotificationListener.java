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
public class EmailNotificationListener {

    private final EmailNotificationService emailNotificationService;

    public EmailNotificationListener(EmailNotificationService emailNotificationService) {
        this.emailNotificationService = emailNotificationService;
    }

    @KafkaListener(
            id = "email-consumer",
            groupId = "nvest-email",
            topics = {Topics.SAVINGS_EVENTS},
            concurrency = "${nvest.kafka.listener.concurrency:1}")
    public void onEvent(ConsumerRecord<String, String> record) {
        String eventId = header(record, "event-id");
        String eventType = header(record, "event-type");
        if (eventId == null || eventType == null) {
            throw new IllegalArgumentException("Missing event-id or event-type header");
        }
        emailNotificationService.handle(eventId, eventType, record.value());
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
