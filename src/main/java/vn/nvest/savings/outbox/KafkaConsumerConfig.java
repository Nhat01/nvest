package vn.nvest.savings.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
@ConditionalOnProperty(name = "nvest.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    /**
     * Spring Boot tự gắn bean CommonErrorHandler này vào mọi @KafkaListener.
     * Lỗi tạm thời: thử lại tối đa 4 lần, chờ 0,5s, 1s, 2s, 4s. Hết lượt thì gửi bản ghi sang <topic>-dlt.
     * Lỗi vĩnh viễn (payload hỏng, thiếu header): sang DLT ngay, không thử lại.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);   // cả JSON hỏng lẫn thiếu header
        return handler;
    }
}