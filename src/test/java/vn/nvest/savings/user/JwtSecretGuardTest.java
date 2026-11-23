package vn.nvest.savings.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtSecretGuardTest {

    @Test
    @DisplayName("Khóa mặc định mà không opt-in thì từ chối khởi động")
    void rejectsDefaultSecretWithoutOptIn() {
        assertThatThrownBy(() -> JwtSecretGuard.validate(TokenService.DEV_DEFAULT_SECRET, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NVEST_JWT_SECRET");
    }

    @Test
    @DisplayName("Bật rõ ràng allow-dev-secret thì được dùng khóa mặc định (kèm cảnh báo)")
    void allowsDefaultSecretWhenExplicitlyEnabled() {
        assertThatCode(() -> JwtSecretGuard.validate(TokenService.DEV_DEFAULT_SECRET, true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Khóa tự đặt thì không bị chặn")
    void allowsCustomSecret() {
        assertThatCode(() -> JwtSecretGuard.validate(
                        "a-random-production-secret-of-at-least-32-bytes", false))
                .doesNotThrowAnyException();
    }
}
