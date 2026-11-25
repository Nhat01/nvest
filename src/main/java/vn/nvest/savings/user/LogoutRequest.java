package vn.nvest.savings.user;

import jakarta.validation.constraints.NotBlank;

/**
 * Body của POST /api/auth/logout.
 * allDevices vắng mặt thì Jackson gán false, tức chỉ thu hồi phiên hiện tại.
 */
public record LogoutRequest(@NotBlank(message = "refreshToken is required") String refreshToken,
                            boolean allDevices) {
}
