package com.fluxyBackend.repository;

import com.fluxyBackend.entity.LegalAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LegalAcceptanceRepository extends JpaRepository<LegalAcceptance, Long> {
    Optional<LegalAcceptance> findFirstByUserIdOrderByAcceptedAtDesc(Long userId);
}
