package vn.nvest.savings.common;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Origin frontend được phép gọi API. Danh sách rỗng = không cho origin nào. */
@ConfigurationProperties(prefix = "nvest.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of()
                : allowedOrigins.stream()
                        .filter(origin -> origin != null && !origin.isBlank())
                        .map(String::trim)
                        .toList();
    }
}
