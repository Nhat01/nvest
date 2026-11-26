package vn.nvest.savings.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.InvalidEmailTokenException;

import java.time.Clock;

@Service
public class PasswordResetService {

    private final UserRepository userRepository;
    private final UserTokenService userTokenService;
    private final EmailSender emailSender;
    private final EmailTokenProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    public PasswordResetService(UserRepository userRepository, UserTokenService userTokenService,
                                EmailSender emailSender, EmailTokenProperties properties,
                                PasswordEncoder passwordEncoder,
                                RefreshTokenService refreshTokenService, Clock clock) {
        this.userRepository = userRepository;
        this.userTokenService = userTokenService;
        this.emailSender = emailSender;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    // Luôn im lặng: không tiết lộ email có tồn tại, đang bị khóa hay đang trong cooldown.
    public void request(String email) {
        userRepository.findByEmailIgnoreCase(email.trim())
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .filter(user -> userTokenService.canIssue(user.getId(), UserTokenPurpose.PASSWORD_RESET))
                .ifPresent(this::sendResetEmail);
    }

    // issue() có transaction riêng nên token đã commit trước khi gửi mail.
    private void sendResetEmail(User user) {
        String rawToken = userTokenService.issue(
                user.getId(), UserTokenPurpose.PASSWORD_RESET, properties.resetTtl());
        try {
            emailSender.sendPasswordReset(user.getEmail(), user.getFullName(), rawToken);
        } catch (RuntimeException e) {
            // Mail không đi được thì token vô dụng; xoá để lần forgot sau không bị cooldown chặn.
            userTokenService.discard(rawToken);
            throw e;
        }
    }

    /**
     * consume + đổi mật khẩu + đánh dấu verified + thu hồi refresh token trong một
     * transaction: token không bị cháy nếu bước sau lỗi, và user không bị đăng xuất
     * giữa lúc mật khẩu chưa đổi.
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        UserToken token = userTokenService.consume(rawToken, UserTokenPurpose.PASSWORD_RESET)
                .orElseThrow(InvalidEmailTokenException::new);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(InvalidEmailTokenException::new);

        user.changePassword(passwordEncoder.encode(newPassword));
        // R81: bấm được link reset chứng minh quyền kiểm soát hộp thư.
        user.markEmailVerified(clock.instant());

        // revokeAllByUser dùng bulk update có clearAutomatically: phải flush mật khẩu
        // và email_verified_at xuống DB trước, nếu không chúng bị bỏ mất.
        userRepository.saveAndFlush(user);
        refreshTokenService.revokeAllForUser(user.getId(), RevokedReason.PASSWORD_RESET);
    }
}
