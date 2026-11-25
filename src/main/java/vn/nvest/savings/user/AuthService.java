package vn.nvest.savings.user;

import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.EmailAlreadyUsedException;
import vn.nvest.savings.common.InvalidCredentialsException;
import vn.nvest.savings.common.InvalidRefreshTokenException;
import vn.nvest.savings.wallet.WalletService;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WalletService walletService;
    private final RefreshTokenService refreshTokenService;
    private final String dummyHash;   // dùng để làm thời gian phản hồi bằng nhau khi email không tồn tại (R63)

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       WalletService walletService, RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.walletService = walletService;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
        this.refreshTokenService = refreshTokenService;
    }

    /** R61: tạo user và ví của họ trong cùng một transaction. */
    @Transactional
    public User register(String email, String rawPassword, String fullName) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new EmailAlreadyUsedException("Email is already registered");
        }

        User user;
        try {
            // Hai request cùng email đến cùng lúc: index duy nhất ở DB chặn bên chậm hơn
            user = userRepository.saveAndFlush(User.register(
                    normalizedEmail, passwordEncoder.encode(rawPassword), fullName.trim()));
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyUsedException("Email is already registered");
        }
        walletService.createUserWallet(user.getId());
        return user;
    }

    /** R62, R63: mọi lý do thất bại đều cho cùng một lỗi và tốn thời gian tương đương. */
    @Transactional
    public TokenPair login(String email, String rawPassword) {
        Optional<User> user = userRepository.findByEmailIgnoreCase(email.trim());

        // Luôn băm một lần dù có tìm thấy user hay không
        String hashToCheck = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCheck);

        if (user.isEmpty() || !passwordMatches || user.get().getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }
        return refreshTokenService.issue(user.get());
    }

    // Không đặt @Transactional ở đây: rotate() tự mở transaction riêng.
    // Nếu bọc ngoài, exception ném ra sẽ rollback luôn việc thu hồi family
    // khi phát hiện dùng lại token.
    public TokenPair refresh(String rawRefreshToken) {
        return refreshTokenService.rotate(rawRefreshToken)
                .orElseThrow(InvalidRefreshTokenException::new);
    }

    // revoke() tự mở transaction; token không tồn tại thì im lặng.
    public void logout(String rawRefreshToken, boolean allDevices) {
        refreshTokenService.revoke(rawRefreshToken, allDevices);
    }

    @Transactional(readOnly = true)
    public UserProfile getActiveProfile(long userId) {
        return userRepository.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(UserProfile::from)
                .orElseThrow(InvalidCredentialsException::new);
    }
}