package com.fluxyBackend.repository;

import com.fluxyBackend.entity.UserSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UserSession s WHERE s.refreshTokenHash = :hash")
    Optional<UserSession> findByRefreshHashForUpdate(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UserSession s WHERE s.previousRefreshTokenHash = :hash")
    Optional<UserSession> findByPreviousRefreshHashForUpdate(@Param("hash") String hash);

    @Query("SELECT s FROM UserSession s WHERE s.userId = :userId AND s.revokedAt IS NULL AND s.expiresAt > :now ORDER BY s.lastUsedAt DESC")
    List<UserSession> findActiveByUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    boolean existsByUserIdAndDeviceLabelAndIpPrefix(Long userId, String deviceLabel, String ipPrefix);

    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :now, s.revokeReason = :reason WHERE s.userId = :userId AND s.revokedAt IS NULL AND (:keepId IS NULL OR s.id <> :keepId)")
    int revokeAllForUser(@Param("userId") Long userId, @Param("keepId") String keepId,
                         @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :now, s.revokeReason = :reason WHERE s.revokedAt IS NULL AND s.userId IN (SELECT u.id FROM User u WHERE u.company.id = :companyId)")
    int revokeAllForCompany(@Param("companyId") Long companyId, @Param("reason") String reason,
                            @Param("now") LocalDateTime now);

    /** Retención: las sesiones vencidas o revocadas hace más de 30 días no aportan nada. */
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.expiresAt < :cutoff OR s.revokedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
