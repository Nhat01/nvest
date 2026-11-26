package vn.nvest.savings.outbox;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "nvest.kafka.ssl")
public class KafkaSslProperties {

    /** CA certificate (PEM hoặc Base64-encoded PEM). */
    private String caCert;

    /** Client/service certificate (PEM hoặc Base64-encoded PEM). */
    private String clientCert;

    /** Client/service private key (PEM hoặc Base64-encoded PEM). */
    private String clientKey;

    /** Mật khẩu giải mã private key (nếu key có đặt mật khẩu). */
    private String keyPassword;

    /** Thuật toán xác thực hostname (mặc định https; để trống để tắt). */
    private String endpointIdentificationAlgorithm = "https";

    public boolean hasSsl() {
        return (caCert != null && !caCert.isBlank())
                || (clientCert != null && !clientCert.isBlank());
    }

    public String getDecodedCaCert() {
        return normalizePem(caCert);
    }

    public String getDecodedClientCert() {
        return normalizePem(clientCert);
    }

    public String getDecodedClientKey() {
        return normalizePem(clientKey);
    }

    public Map<String, Object> toKafkaConfigs() {
        Map<String, Object> map = new HashMap<>();
        if (!hasSsl()) {
            return map;
        }

        map.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");

        String ca = getDecodedCaCert();
        if (ca != null) {
            map.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
            map.put(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, ca);
        }

        String cert = getDecodedClientCert();
        String key = getDecodedClientKey();
        if (cert != null && key != null) {
            map.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PEM");
            map.put(SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG, cert);
            map.put(SslConfigs.SSL_KEYSTORE_KEY_CONFIG, key);
            if (keyPassword != null && !keyPassword.isBlank()) {
                map.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, keyPassword);
            }
        }

        if (endpointIdentificationAlgorithm != null) {
            map.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, endpointIdentificationAlgorithm);
        }

        return map;
    }

    private static String normalizePem(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("-----BEGIN")) {
            return trimmed;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            String pem = new String(decoded, StandardCharsets.UTF_8).trim();
            if (pem.startsWith("-----BEGIN")) {
                return pem;
            }
            return trimmed;
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }
}
