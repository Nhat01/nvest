package vn.nvest.savings.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nvest.mail.brevo")
public record BrevoProperties(String apiKey, String baseUrl) {

    public BrevoProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.brevo.com";
        }
    }
}
