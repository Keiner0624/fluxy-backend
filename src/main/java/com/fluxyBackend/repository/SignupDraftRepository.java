package com.fluxyBackend.repository;

import com.fluxyBackend.entity.SignupDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SignupDraftRepository extends JpaRepository<SignupDraft, Long> {
    Optional<SignupDraft> findByTokenHash(String tokenHash);
}
