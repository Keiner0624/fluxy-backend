package com.fluxyBackend.repository;

import com.fluxyBackend.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String  token);
    void deleteByUser_Email(String email);
}
