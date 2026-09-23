package com.fluxyBackend.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByCompanyId(Long companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.companyId = :companyId")
    Optional<Subscription> findByCompanyIdForUpdate(@Param("companyId") Long companyId);

    /** Vigentes cuyo periodo actual ya terminó: hay que aplicar el cambio programado o cerrarlas. */
    @Query("SELECT s.companyId FROM Subscription s WHERE s.status IN :statuses AND s.currentPeriodEnd <= :now")
    List<Long> findDueCompanyIds(@Param("statuses") Collection<Subscription.Status> statuses, @Param("now") LocalDateTime now);

    @Query("SELECT s FROM Subscription s WHERE s.status IN :statuses AND s.currentPeriodEnd > :now AND s.currentPeriodEnd <= :limit")
    List<Subscription> findEndingBetween(@Param("statuses") Collection<Subscription.Status> statuses,
                                         @Param("now") LocalDateTime now, @Param("limit") LocalDateTime limit);
}
