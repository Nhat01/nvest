package vn.nvest.savings.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** R39: "ngày" nghiệp vụ luôn tính theo giờ Việt Nam. Inject Clock thay vì gọi LocalDate.now() trực tiếp. */
@Configuration
public class ClockConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}