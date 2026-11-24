package vn.nvest.savings.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "nvest.kafka.enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic savingsEventsTopic() {
        return TopicBuilder.name(Topics.SAVINGS_EVENTS).partitions(3).replicas(1).build();
    }

    // Tên phải khớp đúng hậu tố mặc định của Spring Kafka DeadLetterPublishingRecoverer là "-dlt".
    // Nếu lệch (vd ".DLT"), recoverer sẽ tự tạo topic khác với 1 partition và mất ánh xạ partition.
    @Bean
    public NewTopic paymentEventsDlt() {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS + "-dlt").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic savingsEventsDlt() {
        return TopicBuilder.name(Topics.SAVINGS_EVENTS + "-dlt").partitions(3).replicas(1).build();
    }
}
