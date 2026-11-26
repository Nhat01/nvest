package vn.nvest.savings.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** Người dùng mới luôn là USER và ACTIVE. Quyền ADMIN chỉ cấp bằng cách khác, không qua đăng ký. */
    public static User register(String email, String passwordHash, String fullName) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.role = Role.USER;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    /** Chỉ ghi nhận lần xác thực đầu tiên; gọi lại không đổi mốc thời gian. */
    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
        }
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}