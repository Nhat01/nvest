package vn.nvest.savings.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rt from RefreshToken rt where rt.tokenHash = :sha256Hex")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("sha256Hex") String hash);

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        UPDATE RefreshToken rt
        SET rt.revokedAt = :now,
            rt.revokedReason = :reason
        WHERE rt.familyId = :familyId
        AND rt.revokedAt IS NULL
    """)
    int revokeFamily(
            @Param("familyId") UUID familyId,
            @Param("now") Instant now,
            @Param("reason") RevokedReason reason
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        UPDATE RefreshToken rt
        SET rt.revokedAt = :now,
            rt.revokedReason = :reason
        WHERE rt.userId = :userId
        AND rt.revokedAt IS NULL
    """)
    int revokeAllByUser(
            @Param("userId") Long userId,
            @Param("now") Instant now,
            @Param("reason") RevokedReason reason
    );
}
