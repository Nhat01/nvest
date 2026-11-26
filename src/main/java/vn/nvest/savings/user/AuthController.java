package vn.nvest.savings.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.nvest.savings.common.InvalidCredentialsException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final Logger log =  LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, LoginRateLimiter loginRateLimiter,
                          EmailVerificationService emailVerificationService,
                          PasswordResetService passwordResetService) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfile register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(
                request.email(), request.password(), request.fullName());
        try {
            emailVerificationService.sendVerification(user);
        } catch (RuntimeException e) {
            // Không để lỗi gửi mail biến một đăng ký thành công thành 500.
            log.warn("Cannot send verification email for user {}", user.getId(), e);
        }
        return UserProfile.from(user);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String key = loginRateLimiter.key(httpRequest.getRemoteAddr(), request.email());
        loginRateLimiter.checkAllowed(key);
        try {
            TokenPair tokenPair = authService.login(request.email(), request.password());

            TokenResponse response = TokenResponse.of(tokenPair.accessToken(), tokenPair.refreshToken(), tokenPair.refreshExpiresIn());
            loginRateLimiter.reset(key);
            return response;
        } catch (InvalidCredentialsException e) {
            loginRateLimiter.recordFailure(key);
            throw e;
        }
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        TokenPair tokenPair = authService.refresh(request.refreshToken());
        return TokenResponse.of(tokenPair.accessToken(), tokenPair.refreshToken(), tokenPair.refreshExpiresIn());
    }

    /** Idempotent: token lạ hay đã thu hồi vẫn trả 204, không tiết lộ trạng thái token. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken(), request.allDevices());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.token());
    }

    /**
     * Luôn 204: không tiết lộ email có tồn tại, đã xác thực hay đang trong cooldown.
     * Lỗi SMTP cũng không được biến thành 500 vì client không thể xử lý.
     */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        try {
            emailVerificationService.resend(request.email());
        } catch (RuntimeException e) {
            log.warn("Cannot resend verification email", e);
        }
    }

    /**
     * Luôn 204: không tiết lộ email có tồn tại, đang bị khóa hay đang trong cooldown.
     * Lỗi SMTP cũng không biến thành 500 vì client không thể xử lý.
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        try {
            passwordResetService.request(request.email());
        } catch (RuntimeException e) {
            log.warn("Cannot send password reset email", e);
        }
    }

    /**
     * Đổi mật khẩu bằng token. Không tự đăng nhập (R80): client phải gọi /login
     * bằng mật khẩu mới. Token sai/hết hạn/đã dùng đều trả 400 chung.
     */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request.token(), request.newPassword());
    }
}
