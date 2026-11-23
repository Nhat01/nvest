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
            TokenResponse response = TokenResponse.of(authService.login(request.email(), request.password()));
            loginRateLimiter.reset(key);
            return response;
        } catch (InvalidCredentialsException e) {
            loginRateLimiter.recordFailure(key);
            throw e;
        }
    }
}
