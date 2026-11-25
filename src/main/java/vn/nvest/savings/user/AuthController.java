package vn.nvest.savings.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfile register(@Valid @RequestBody RegisterRequest request) {
        return UserProfile.from(authService.register(request.email(), request.password(), request.fullName()));
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
}
