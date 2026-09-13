package com.fluxyBackend.repository;

import com.fluxyBackend.entity.InventoryMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InventoryMovementRepository
        extends JpaRepository<InventoryMovement, Long>, JpaSpecificationExecutor<InventoryMovement> {

    @Query("SELECT DISTINCT m.productId FROM InventoryMovement m WHERE m.companyId = :companyId")
    List<Long> findProductIdsWithMovements(@Param("companyId") Long companyId);

    @Modifying
    @Query("DELETE FROM InventoryMovement m WHERE m.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
