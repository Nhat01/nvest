package vn.nvest.savings.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason")
    @Enumerated(EnumType.STRING)
    private RevokedReason revokedReason;

    public static RefreshToken issue(Long userId, String tokenHash, UUID familyId, Instant now, Instant expiresAt) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.userId = userId;
        refreshToken.tokenHash = tokenHash;
        refreshToken.familyId = familyId;
        refreshToken.expiresAt = expiresAt;
        refreshToken.issuedAt = now;
        return refreshToken;
    }

    public void rotate(Instant now) {
        revoke(now, RevokedReason.ROTATED);
    }

    public void revoke(Instant now, RevokedReason revokedReason) {
        if(this.revokedAt != null) {
            return;
        }
        this.revokedAt = now;
        this.revokedReason = revokedReason;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }
}
