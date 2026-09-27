package com.fluxyBackend.invoicing.repository;

import com.fluxyBackend.invoicing.entity.InvoicingConfiguration;
import com.fluxyBackend.invoicing.enums.ConfigurationStatus;
import com.fluxyBackend.invoicing.enums.ProviderCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InvoicingConfigurationRepository extends JpaRepository<InvoicingConfiguration, Long> {

    Optional<InvoicingConfiguration> findByCompanyId(Long companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM InvoicingConfiguration c WHERE c.companyId = :companyId")
    Optional<InvoicingConfiguration> findByCompanyIdForUpdate(@Param("companyId") Long companyId);

    List<InvoicingConfiguration> findByStatusAndProviderNot(ConfigurationStatus status, ProviderCode provider);

    @Modifying
    @Query("DELETE FROM InvoicingConfiguration c WHERE c.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
