package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company,Long> {
    Optional<Company> findBySlug(String slug);
    Optional<Company> findByCustomDomain(String customDomain);
    boolean existsBySlug(String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Company c WHERE c.id = :id")
    Optional<Company> findByIdForUpdate(@Param("id") Long id);

    //Planes vencidos - para el scheduler
    @Query("SELECT c FROM Company c WHERE c.plan <> :freePlan AND c.planExpiresAt IS NOT NULL AND c.planExpiresAt < :now ")
    List<Company> findExpiredPlans(@Param("freePlan")Company.Plan freePlan, @Param("now") java.time.LocalDateTime now);

    //Planes por vencer en x dias - para avisos
    @Query("SELECT c FROM Company c WHERE c.plan <> :freePlan AND c.planExpiresAt IS NOT NULL AND c.planExpiresAt BETWEEN :now AND :limit ")
    List<Company> findExpiringPlans(@Param("freePlan")Company.Plan freePlan, @Param("now") java.time.LocalDateTime now, @Param("limit") java.time.LocalDateTime limit);

}
