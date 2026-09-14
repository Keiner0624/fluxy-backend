package com.fluxyBackend.service;

import com.fluxyBackend.entity.VerificationChallenge;
import com.fluxyBackend.entity.VerificationChallenge.Purpose;
import com.fluxyBackend.entity.VerificationChallenge.Type;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.repository.VerificationChallengeRepository;
import com.fluxyBackend.security.ClientInfo;
import com.fluxyBackend.security.Hashing;
import com.fluxyBackend.security.JwtService;
import com.fluxyBackend.security.RateLimitedException;
import com.fluxyBackend.security.SecurityMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Códigos de un solo uso por correo o WhatsApp.
 *
 * 6 dígitos, 10 minutos, 5 intentos, reenvío cada 60 segundos, 5 por hora por
 * destino y 10 por hora por IP. El código se guarda como HMAC y se envía
 * después del commit, fuera del hilo de la petición.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationService {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 5;
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final int MAX_PER_DESTINATION_HOUR = 5;
    static final int MAX_PER_IP_HOUR = 10;

    private final VerificationChallengeRepository repository;
    private final EmailService emailService;
    private final WhatsAppOtpService whatsAppOtpService;
    private final JwtService jwtService;
    private final SecurityMonitor monitor;
    private final ApplicationEventPublisher events;

    /** Solo desarrollo local: escribe el código en el log. Nunca en producción. */
    @Value("${app.verification.log_codes:false}")
    private boolean logCodes;

    /** Solo pruebas automáticas: guarda el último código por destino. */
    @Value("${app.verification.test_capture:false}")
    private boolean testCapture;

    private final Map<String, String> capturedCodes = new ConcurrentHashMap<>();

    public record Issued(String channel, String destination, OffsetDateTime expiresAt, long resendInSeconds) {}

    record Delivery(Long challengeId, Type type, String destination, String code, String recipientName, Purpose purpose) {}

    public boolean phoneChannelAvailable() {
        return whatsAppOtpService.isConfigured();
    }

    // ─── Emisión ──────────────────────────────────────────────────────────────

    @Transactional
    public Issued issue(Long userId, Type type, Purpose purpose, String destination, String pendingValue,
                        String recipientName) {
        String normalized = normalize(type, destination);
        String destinationHash = destinationHash(type, normalized);
        String ipHash = ClientInfo.currentRequest() == null ? null : ClientInfo.ipHash(ClientInfo.currentIp());
        LocalDateTime now = LocalDateTime.now();

        repository.findFirstByDestinationHashAndPurposeOrderByCreatedAtDesc(destinationHash, purpose)
                .filter(last -> last.getCreatedAt().plus(RESEND_COOLDOWN).isAfter(now))
                .ifPresent(last -> {
                    long wait = Duration.between(now, last.getCreatedAt().plus(RESEND_COOLDOWN)).toSeconds() + 1;
                    throw new RateLimitedException(wait, "Esperá " + wait + " segundos para pedir otro código.");
                });
        if (repository.countByDestinationHashAndCreatedAtAfter(destinationHash, now.minusHours(1)) >= MAX_PER_DESTINATION_HOUR) {
            throw new RateLimitedException(3600, "Pediste demasiados códigos. Probá de nuevo en una hora.");
        }
        if (ipHash != null && repository.countByIpHashAndCreatedAtAfter(ipHash, now.minusHours(1)) >= MAX_PER_IP_HOUR) {
            throw new RateLimitedException(3600, "Demasiados códigos pedidos desde esta red. Probá de nuevo en una hora.");
        }

        // Un código nuevo invalida los anteriores del mismo propósito.
        if (userId != null) {
            repository.findOpen(userId, purpose, type).forEach(open -> open.setInvalidatedAt(now));
        }

        String code = Hashing.sixDigitCode();
        VerificationChallenge challenge = new VerificationChallenge();
        challenge.setUserId(userId);
        challenge.setType(type);
        challenge.setPurpose(purpose);
        challenge.setDestinationHash(destinationHash);
        challenge.setDestinationMasked(type == Type.EMAIL ? maskEmail(normalized) : maskPhone(normalized));
        challenge.setPendingValue(pendingValue);
        challenge.setCodeHash(codeHash(destinationHash, code));
        challenge.setExpiresAt(now.plus(CODE_TTL));
        challenge.setMaxAttempts(MAX_ATTEMPTS);
        challenge.setIpHash(ipHash);
        repository.save(challenge);

        if (logCodes) log.info("[DESARROLLO] Código {} para {}: {}", type, challenge.getDestinationMasked(), code);
        if (testCapture) capturedCodes.put(destinationHash, code);
        events.publishEvent(new Delivery(challenge.getId(), type, normalized, code, recipientName, purpose));

        return new Issued(type.name(), challenge.getDestinationMasked(), com.fluxyBackend.service.BusinessClock.withOffset(challenge.getExpiresAt()),
                RESEND_COOLDOWN.toSeconds());
    }

    /** Envío después del commit y en otro hilo: el registro no espera al proveedor. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliver(Delivery delivery) {
        boolean delivered = delivery.type() == Type.EMAIL
                ? emailService.sendVerificationCode(delivery.destination(), delivery.recipientName(), delivery.code(),
                purposeLabel(delivery.purpose()), CODE_TTL.toMinutes())
                : whatsAppOtpService.sendCode(delivery.destination(), delivery.code());
        monitor.recordOtpDelivery(delivery.type().name(), delivered);
        if (!delivered) markDeliveryFailed(delivery.challengeId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDeliveryFailed(Long challengeId) {
        repository.findById(challengeId).ifPresent(c -> {
            c.setDeliveryFailedAt(LocalDateTime.now());
            repository.save(c);
        });
    }

    // ─── Verificación ─────────────────────────────────────────────────────────

    /**
     * Consume el código si es correcto. noRollbackFor: el intento fallido tiene
     * que sumarse aunque la respuesta sea un error.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public VerificationChallenge verify(Long userId, Purpose purpose, Type type, String code) {
        if (code == null || !code.strip().matches("\\d{6}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "El código tiene 6 dígitos.");
        }
        VerificationChallenge open = repository.findOpen(userId, purpose, type).stream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "CODE_NOT_FOUND",
                        "No hay un código vigente. Pedí uno nuevo."));
        VerificationChallenge challenge = repository.findByIdForUpdate(open.getId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();

        if (!challenge.isOpen()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_NOT_FOUND", "Ese código ya se usó. Pedí uno nuevo.");
        }
        if (challenge.getExpiresAt().isBefore(now)) {
            challenge.setInvalidatedAt(now);
            throw new BusinessException(HttpStatus.GONE, "CODE_EXPIRED", "El código venció. Pedí uno nuevo.");
        }
        if (!Hashing.constantTimeEquals(codeHash(challenge.getDestinationHash(), code.strip()), challenge.getCodeHash())) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            int remaining = challenge.getMaxAttempts() - challenge.getAttempts();
            if (remaining <= 0) {
                challenge.setInvalidatedAt(now);
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "CODE_LOCKED",
                        "Demasiados intentos con este código. Pedí uno nuevo.");
            }
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_INVALID",
                    "Código incorrecto. Te " + (remaining == 1 ? "queda 1 intento." : "quedan " + remaining + " intentos."));
        }
        challenge.setConsumedAt(now);
        return challenge;
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "America/Lima")
    @Transactional
    public void purgeOld() {
        repository.deleteOlderThan(LocalDateTime.now().minusDays(7));
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    /** Solo pruebas: último código emitido para un destino. */
    public String capturedCode(Type type, String destination) {
        return capturedCodes.get(destinationHash(type, normalize(type, destination)));
    }

    public static String normalize(Type type, String destination) {
        if (destination == null) throw new BusinessException("Falta el destino del código.");
        return type == Type.EMAIL
                ? destination.strip().toLowerCase(Locale.ROOT)
                : destination.replaceAll("\\D", "");
    }

    static String destinationHash(Type type, String normalized) {
        return Hashing.sha256(type.name() + ":" + normalized);
    }

    private String codeHash(String destinationHash, String code) {
        return Hashing.hmacSha256(jwtService.derivedKey("otp"), destinationHash + ":" + code);
    }

    public static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(at, 0));
        return email.charAt(0) + "***" + email.charAt(at - 1) + email.substring(at);
    }

    public static String maskPhone(String digits) {
        if (digits.length() < 6) return "***";
        String local = digits.startsWith("51") && digits.length() == 11 ? digits.substring(2) : digits;
        return "+51 " + local.charAt(0) + "** *** " + local.substring(local.length() - 3);
    }

    private static String purposeLabel(Purpose purpose) {
        return switch (purpose) {
            case SIGN_UP -> "crear tu cuenta";
            case VERIFY_EMAIL -> "verificar tu correo";
            case CHANGE_EMAIL -> "confirmar tu nuevo correo";
            case CHANGE_PHONE -> "confirmar tu nuevo WhatsApp";
        };
    }
}
