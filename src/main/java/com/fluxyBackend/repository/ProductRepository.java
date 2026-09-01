package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Prodcut;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Prodcut, Long> {
    List<Prodcut> findByCompany(Company company);
    Optional<Prodcut> findByIdAndCompany(Long id, Company company);
    int countByCompany(Company company);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Prodcut p WHERE p.id = :id AND p.company = :company")
    Optional<Prodcut> findByIdAndCompanyForUpdate(@Param("id") Long id,
                                                  @Param("company") Company company);

    @Modifying
     @Query("DELETE FROM Prodcut p WHERE p.company.id = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);

}
