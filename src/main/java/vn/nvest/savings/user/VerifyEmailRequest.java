package vn.nvest.savings.user;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(@NotBlank(message = "token is required") String token) {
}
