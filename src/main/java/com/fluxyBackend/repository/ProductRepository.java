package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Category;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Prodcut;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Prodcut, Long>, JpaSpecificationExecutor<Prodcut> {
    List<Prodcut> findByCompany(Company company);
    Optional<Prodcut> findByIdAndCompany(Long id, Company company);
    int countByCompany(Company company);
    List<Prodcut> findByCompanyIdAndIdIn(Long companyId, Collection<Long> ids);
    boolean existsByCompanyIdAndSkuIgnoreCase(Long companyId, String sku);
    boolean existsByCompanyIdAndSkuIgnoreCaseAndIdNot(Long companyId, String sku, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Prodcut p WHERE p.id = :id AND p.company = :company")
    Optional<Prodcut> findByIdAndCompanyForUpdate(@Param("id") Long id,
                                                  @Param("company") Company company);

    /** Catálogo visible en la tienda: sin los productos ocultos. */
    @Query("SELECT p FROM Prodcut p WHERE p.company = :company AND (p.status IS NULL OR p.status <> :hidden) ORDER BY p.id")
    List<Prodcut> findVisibleByCompany(@Param("company") Company company,
                                       @Param("hidden") Prodcut.Status hidden);

    @Query("""
            SELECT p FROM Prodcut p
            WHERE p.company.id = :companyId
              AND (p.status IS NULL OR p.status <> :hidden)
              AND p.stock <= COALESCE(p.minStock, 5)
            ORDER BY p.stock ASC, p.name ASC
            """)
    List<Prodcut> findLowStock(@Param("companyId") Long companyId,
                               @Param("hidden") Prodcut.Status hidden,
                               Pageable pageable);

    @Query("""
            SELECT COUNT(p) AS total,
                   SUM(CASE WHEN p.status = :hidden THEN 1 ELSE 0 END) AS hidden,
                   SUM(CASE WHEN p.stock <= 0 THEN 1 ELSE 0 END) AS outOfStock,
                   SUM(CASE WHEN p.stock > 0 AND p.stock <= COALESCE(p.minStock, 5) THEN 1 ELSE 0 END) AS lowStock,
                   SUM(CASE WHEN p.stock > 0 THEN p.stock * COALESCE(p.cost, p.price) ELSE 0.0 END) AS inventoryValue,
                   SUM(CASE WHEN p.stock > 0 AND p.cost IS NULL THEN 1 ELSE 0 END) AS withoutCost
            FROM Prodcut p WHERE p.company.id = :companyId
            """)
    Stats stats(@Param("companyId") Long companyId, @Param("hidden") Prodcut.Status hidden);

    @Query("SELECT p.category.id AS categoryId, COUNT(p) AS total FROM Prodcut p WHERE p.company.id = :companyId AND p.category IS NOT NULL GROUP BY p.category.id")
    List<CategoryCount> countByCategory(@Param("companyId") Long companyId);

    @Modifying
    @Query("UPDATE Prodcut p SET p.category = null WHERE p.category = :category")
    int clearCategory(@Param("category") Category category);

    @Modifying
     @Query("DELETE FROM Prodcut p WHERE p.company.id = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);

    interface Stats {
        Long getTotal();
        Long getHidden();
        Long getOutOfStock();
        Long getLowStock();
        Double getInventoryValue();
        Long getWithoutCost();
    }

    interface CategoryCount {
        Long getCategoryId();
        Long getTotal();
    }
}
