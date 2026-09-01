package com.fluxyBackend.repository;

import com.fluxyBackend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    List<User> findByCompanyId(Long companyId);
    Optional<User> findFirstByCompanyId(Long companyId);
}
