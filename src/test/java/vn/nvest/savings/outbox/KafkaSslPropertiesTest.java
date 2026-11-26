package vn.nvest.savings.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaSslPropertiesTest {

    private static final String SAMPLE_CA = "-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----";
    private static final String SAMPLE_CERT = "-----BEGIN CERTIFICATE-----\nMIIC...\n-----END CERTIFICATE-----";
    private static final String SAMPLE_KEY = "-----BEGIN PRIVATE KEY-----\nMIIE...\n-----END PRIVATE KEY-----";

    @Test
    @DisplayName("hasSsl trả về false khi không cấu hình cert")
    void hasSsl_returnsFalse_whenEmpty() {
        KafkaSslProperties props = new KafkaSslProperties();
        assertThat(props.hasSsl()).isFalse();
        assertThat(props.toKafkaConfigs()).isEmpty();
    }

    @Test
    @DisplayName("Giải mã đúng khi cert là chuỗi Base64")
    void decodesBase64Certs() {
        KafkaSslProperties props = new KafkaSslProperties();
        props.setCaCert(Base64.getEncoder().encodeToString(SAMPLE_CA.getBytes(StandardCharsets.UTF_8)));
        props.setClientCert(Base64.getEncoder().encodeToString(SAMPLE_CERT.getBytes(StandardCharsets.UTF_8)));
        props.setClientKey(Base64.getEncoder().encodeToString(SAMPLE_KEY.getBytes(StandardCharsets.UTF_8)));
        props.setKeyPassword("secret123");

        assertThat(props.hasSsl()).isTrue();
        assertThat(props.getDecodedCaCert()).isEqualTo(SAMPLE_CA);
        assertThat(props.getDecodedClientCert()).isEqualTo(SAMPLE_CERT);
        assertThat(props.getDecodedClientKey()).isEqualTo(SAMPLE_KEY);

        Map<String, Object> configs = props.toKafkaConfigs();
        assertThat(configs).containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
        assertThat(configs).containsEntry(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
        assertThat(configs).containsEntry(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, SAMPLE_CA);
        assertThat(configs).containsEntry(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PEM");
        assertThat(configs).containsEntry(SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG, SAMPLE_CERT);
        assertThat(configs).containsEntry(SslConfigs.SSL_KEYSTORE_KEY_CONFIG, SAMPLE_KEY);
        assertThat(configs).containsEntry(SslConfigs.SSL_KEY_PASSWORD_CONFIG, "secret123");
        assertThat(configs).containsEntry(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
    }

    @Test
    @DisplayName("Giữ nguyên khi cert là chuỗi PEM thô (raw PEM)")
    void handlesRawPem() {
        KafkaSslProperties props = new KafkaSslProperties();
        props.setCaCert(SAMPLE_CA);
        props.setClientCert(SAMPLE_CERT);
        props.setClientKey(SAMPLE_KEY);

        assertThat(props.getDecodedCaCert()).isEqualTo(SAMPLE_CA);
        assertThat(props.getDecodedClientCert()).isEqualTo(SAMPLE_CERT);
        assertThat(props.getDecodedClientKey()).isEqualTo(SAMPLE_KEY);
    }
}
