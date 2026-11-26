package vn.nvest.savings.outbox;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
@ConditionalOnProperty(name = "nvest.kafka.enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaSslProperties.class)
public class KafkaSslConfig {

    private final KafkaSslProperties sslProperties;

    public KafkaSslConfig(KafkaSslProperties sslProperties) {
        this.sslProperties = sslProperties;
    }

    @Bean
    public DefaultKafkaProducerFactoryCustomizer kafkaProducerSslCustomizer() {
        return factory -> {
            if (sslProperties.hasSsl()) {
                factory.updateConfigs(sslProperties.toKafkaConfigs());
            }
        };
    }

    @Bean
    public DefaultKafkaConsumerFactoryCustomizer kafkaConsumerSslCustomizer() {
        return factory -> {
            if (sslProperties.hasSsl()) {
                factory.updateConfigs(sslProperties.toKafkaConfigs());
            }
        };
    }

    @Bean
    public KafkaAdmin kafkaAdmin(KafkaProperties properties) {
        Map<String, Object> adminConfigs = properties.buildAdminProperties(null);
        if (sslProperties.hasSsl()) {
            adminConfigs.putAll(sslProperties.toKafkaConfigs());
        }
        KafkaAdmin admin = new KafkaAdmin(adminConfigs);
        KafkaProperties.Admin adminProp = properties.getAdmin();
        if (adminProp.getCloseTimeout() != null) {
            admin.setCloseTimeout((int) adminProp.getCloseTimeout().getSeconds());
        }
        if (adminProp.getOperationTimeout() != null) {
            admin.setOperationTimeout((int) adminProp.getOperationTimeout().getSeconds());
        }
        admin.setFatalIfBrokerNotAvailable(adminProp.isFailFast());
        admin.setModifyTopicConfigs(adminProp.isModifyTopicConfigs());
        admin.setAutoCreate(adminProp.isAutoCreate());
        return admin;
    }
}
