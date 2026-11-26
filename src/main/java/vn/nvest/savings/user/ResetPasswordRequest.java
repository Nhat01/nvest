package vn.nvest.savings.user;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

/** Cùng chính sách mật khẩu với RegisterRequest: 8–72 ký tự và ≤ 72 byte UTF-8. */
public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8, max = 72) String newPassword) {

    @AssertTrue(message = "newPassword must be at most 72 bytes in UTF-8")
    public boolean isPasswordWithinBcryptLimit() {
        return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
