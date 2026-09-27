package com.fluxyBackend.invoicing.repository;

import com.fluxyBackend.invoicing.entity.TaxProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TaxProfileRepository extends JpaRepository<TaxProfile, Long> {

    Optional<TaxProfile> findByCompanyId(Long companyId);

    @Modifying
    @Query("DELETE FROM TaxProfile t WHERE t.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
