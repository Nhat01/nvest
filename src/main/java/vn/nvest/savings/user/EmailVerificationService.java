package vn.nvest.savings.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.InvalidEmailTokenException;

import java.time.Clock;

@Service
public class EmailVerificationService {

    private final UserRepository userRepository;
    private final UserTokenService userTokenService;
    private final EmailSender emailSender;
    private final EmailTokenProperties properties;
    private final Clock clock;

    public EmailVerificationService(UserRepository userRepository, UserTokenService userTokenService,
                                    EmailSender emailSender, EmailTokenProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.userTokenService = userTokenService;
        this.emailSender = emailSender;
        this.properties = properties;
        this.clock = clock;
    }

    // issue() có transaction riêng nên token đã commit trước khi gửi mail.
    // Cố ý không bọc @Transactional: lỗi SMTP không được rollback token vừa tạo.
    public void sendVerification(User user) {
        String rawToken = userTokenService.issue(
                user.getId(), UserTokenPurpose.EMAIL_VERIFICATION, properties.verificationTtl());
        try {
            emailSender.sendVerification(user.getEmail(), user.getFullName(), rawToken);
        } catch (RuntimeException e) {
            // Mail không đi được thì token vô dụng; xoá để lần resend sau không bị cooldown chặn.
            userTokenService.discard(rawToken);
            throw e;
        }
    }

    // consume() phải nằm chung transaction với markEmailVerified(): nếu bước sau lỗi
    // thì used_at cũng rollback, token không bị cháy oan.
    @Transactional
    public void verify(String rawToken) {
        UserToken token = userTokenService.consume(rawToken, UserTokenPurpose.EMAIL_VERIFICATION)
                .orElseThrow(InvalidEmailTokenException::new);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(InvalidEmailTokenException::new);
        user.markEmailVerified(clock.instant());
    }

    // Luôn im lặng: không tiết lộ email có tồn tại, đã xác thực hay đang trong cooldown.
    public void resend(String email) {
        userRepository.findByEmailIgnoreCase(email.trim())
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .filter(user -> !user.isEmailVerified())
                .filter(user -> userTokenService.canIssue(user.getId(), UserTokenPurpose.EMAIL_VERIFICATION))
                .ifPresent(this::sendVerification);
    }
}
