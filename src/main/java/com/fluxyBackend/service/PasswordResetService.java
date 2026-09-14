package com.fluxyBackend.service;

import com.fluxyBackend.entity.PasswordResetToken;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.repository.PasswordResetTokenRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.ClientInfo;
import com.fluxyBackend.security.Hashing;
import com.fluxyBackend.security.PasswordPolicy;
import com.fluxyBackend.security.RateLimitService;
import com.fluxyBackend.security.SessionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Recuperación de contraseña: respuesta genérica, enlace de un solo uso con
 * hash en base, límites por correo e IP, y al completar se cierran todas las
 * sesiones y se avisa a la persona.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    static final long TOKEN_MINUTES = 30;
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final EmailService emailService;

    @Value("${app.frontend_url}")
    private String frontendUrl;

    /** Siempre termina igual para quien pregunta: no revela si el correo tiene cuenta. */
    @Transactional
    public void requestReset(String email) {
        rateLimitService.check(RateLimitService.Bucket.PASSWORD_RESET_IP, "ip:" + ClientInfo.currentIp());
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 254) return;
        // El límite por correo se aplica en silencio: un 429 acá confirmaría que la cuenta existe.
        if (!rateLimitService.hit(RateLimitService.Bucket.PASSWORD_RESET_DESTINATION, "email:" + Hashing.sha256(normalized)).allowed()) {
            return;
        }

        userRepository.findByEmailIgnoreCase(normalized)
                .filter(user -> user.getStatus() != User.Status.DISABLED)
                .ifPresent(user -> {
                    tokenRepository.deleteByUser_Email(user.getEmail());
                    String token = Hashing.randomToken();
                    tokenRepository.save(PasswordResetToken.builder()
                            .token(Hashing.sha256(token))
                            .user(user)
                            .expiresAt(LocalDateTime.now().plusMinutes(TOKEN_MINUTES))
                            .used(false)
                            .build());
                    auditService.record(companyId(user), user, AuditAction.PASSWORD_RESET_REQUESTED, "USER", user.getId(), null);

                    Long userId = user.getId();
                    String to = user.getEmail();
                    String name = user.getFullName();
                    String link = frontendUrl + "/reset-password?token=" + token;
                    afterCommit(() -> {
                        if (!emailService.sendPasswordReset(to, name, link, TOKEN_MINUTES)) {
                            log.warn("No se pudo enviar el correo de recuperación (usuario {})", userId);
                        }
                    });
                });
    }

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) return false;
        return tokenRepository.findByToken(Hashing.sha256(token))
                .map(t -> !t.isExpired() && !t.isUsed())
                .orElse(false);
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        rateLimitService.check(RateLimitService.Bucket.PASSWORD_RESET_IP, "ip:" + ClientInfo.currentIp());
        PasswordResetToken resetToken = (token == null || token.isBlank()) ? null
                : tokenRepository.findByToken(Hashing.sha256(token)).orElse(null);
        if (resetToken == null || resetToken.isExpired() || resetToken.isUsed()
                || resetToken.getUser().getStatus() == User.Status.DISABLED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RESET_TOKEN_INVALID",
                    "El enlace venció o ya fue usado. Pedí uno nuevo.");
        }
        User user = resetToken.getUser();
        PasswordPolicy.validate(newPassword, user.getEmail());

        // Primero se consume el enlace: dos envíos simultáneos no pueden usarlo dos veces.
        resetToken.setUsed(true);
        tokenRepository.saveAndFlush(resetToken);

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordEnabled(true);
        user.setPasswordChangedAt(LocalDateTime.now());
        // Abrir el enlace prueba que la persona controla el correo.
        if (user.getEmailVerifiedAt() == null) user.setEmailVerifiedAt(LocalDateTime.now());
        userRepository.save(user);

        int revoked = sessionService.revokeAllForUser(user.getId(), null, "PASSWORD_RESET");
        auditService.record(companyId(user), user, AuditAction.PASSWORD_RESET_COMPLETED, "USER", user.getId(),
                Map.of("sessionsRevoked", revoked));

        String to = user.getEmail();
        String name = user.getFullName();
        afterCommit(() -> emailService.sendSecurityNotice(to, name, "Tu contraseña cambió",
                "Se restableció la contraseña de tu cuenta y cerramos todas las sesiones abiertas."));
    }

    private static Long companyId(User user) {
        return user.getCompany() == null ? null : user.getCompany().getId();
    }

    /** El correo sale después de confirmar la transacción y sin frenar la respuesta. */
    private static void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(task);
                }
            });
        } else {
            CompletableFuture.runAsync(task);
        }
    }
}
