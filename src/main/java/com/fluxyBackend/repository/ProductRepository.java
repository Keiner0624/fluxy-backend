package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Prodcut, Long> {
    List<Prodcut> findByOwner(User owner);
    List<Prodcut> findByCompany(Company company);
    Optional<Prodcut> findByIdAndOwner(Long id, Company owner);
    Optional<Prodcut> findByIdAndCompany(Long id, Company company);

}
