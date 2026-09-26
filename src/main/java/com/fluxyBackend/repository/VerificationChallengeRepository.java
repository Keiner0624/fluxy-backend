package com.fluxyBackend.repository;

import com.fluxyBackend.entity.VerificationChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VerificationChallengeRepository extends JpaRepository<VerificationChallenge, Long> {

    long countByDestinationHashAndCreatedAtAfter(String destinationHash, LocalDateTime since);

    long countByIpHashAndCreatedAtAfter(String ipHash, LocalDateTime since);

    Optional<VerificationChallenge> findFirstByDestinationHashAndPurposeOrderByCreatedAtDesc(
            String destinationHash, VerificationChallenge.Purpose purpose);

    @Query("""
            SELECT c FROM VerificationChallenge c
            WHERE c.userId = :userId AND c.purpose = :purpose AND c.type = :type
              AND c.consumedAt IS NULL AND c.invalidatedAt IS NULL
            ORDER BY c.createdAt DESC
            """)
    List<VerificationChallenge> findOpen(@Param("userId") Long userId,
                                         @Param("purpose") VerificationChallenge.Purpose purpose,
                                         @Param("type") VerificationChallenge.Type type);

    /** Solo esa columna: guardar la entidad entera pisaría intentos o consumos hechos mientras tanto. */
    // Se llama desde el envío asíncrono, fuera de toda transacción: abre la suya.
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("UPDATE VerificationChallenge c SET c.deliveryFailedAt = :at WHERE c.id = :id")
    int markDeliveryFailed(@Param("id") Long id, @Param("at") java.time.LocalDateTime at);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM VerificationChallenge c WHERE c.id = :id")
    Optional<VerificationChallenge> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query("DELETE FROM VerificationChallenge c WHERE c.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("DELETE FROM VerificationChallenge c WHERE c.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
