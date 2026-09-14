package com.fluxyBackend.repository;

import com.fluxyBackend.entity.OwnershipTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OwnershipTransferRepository extends JpaRepository<OwnershipTransfer, Long> {
    List<OwnershipTransfer> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    @Modifying
    @Query("DELETE FROM OwnershipTransfer t WHERE t.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
