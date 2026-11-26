package vn.nvest.savings.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ut FROM UserToken ut WHERE ut.tokenHash = :tokenHash")
    Optional<UserToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<UserToken> findTopByUserIdAndPurposeOrderByIdDesc(
            Long userId, UserTokenPurpose purpose);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE UserToken t
           SET t.usedAt = :now
         WHERE t.userId = :userId
           AND t.purpose = :purpose
           AND t.usedAt is null
        """)
    int invalidateUnused(@Param("userId") Long userId,
                         @Param("purpose") UserTokenPurpose purpose,
                         @Param("now") Instant now);

    // Chỉ dùng để dọn token vừa tạo khi gửi mail thất bại: xoá hẳn để cooldown
    // không chặn lần gửi lại ngay sau đó.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UserToken t WHERE t.tokenHash = :tokenHash")
    int deleteByTokenHash(@Param("tokenHash") String tokenHash);

    // Job dọn dẹp: xoá token đã hết hạn từ lâu. Không đụng token còn hạn.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UserToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
