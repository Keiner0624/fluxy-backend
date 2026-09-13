package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Category;
import com.fluxyBackend.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByCompanyOrderByNameAsc(Company company);
    Optional<Category> findByIdAndCompany(Long id, Company company);

    /** En el orden que eligió el vendedor; las que no tienen posición van al final. */
    @Query("SELECT c FROM Category c WHERE c.company = :company ORDER BY COALESCE(c.sortOrder, 2147483647), c.name")
    List<Category> findOrdered(@Param("company") Company company);
}
