package com.fluxyBackend.security;

import com.fluxyBackend.controller.AuthResponse;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.entity.UserSession;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.ForbiddenException;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.repository.UserSessionRepository;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import com.fluxyBackend.service.BusinessClock;
import com.fluxyBackend.service.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sesiones con refresh token rotatorio.
 *
 * Cada renovación emite un refresh token nuevo y guarda el hash del anterior.
 * Si alguien presenta ese anterior pasado el margen de gracia, el token fue
 * copiado: se revoca la sesión entera y se avisa.
 */
@Service
@Slf4j
public class SessionService {

    public static final String REQUEST_SESSION_ID = "fluxy.sessionId";

    /** Dos pestañas pueden renovar a la vez: la segunda llega con el token recién rotado. */
    private static final Duration ROTATION_GRACE = Duration.ofSeconds(30);
    /** Cuánto se confía en el estado de una sesión sin volver a consultar la base. */
    private static final Duration ACTIVE_CACHE = Duration.ofSeconds(15);

    private final UserSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final SecurityMonitor monitor;
    private final EmailService emailService;

    @Value("${app.session.remember_days:14}")
    private long rememberDays = 14;

    @Value("${app.session.default_hours:12}")
    private long defaultHours = 12;

    private record CacheEntry(boolean active, Long userId, long checkedAt) {}

    private final Map<String, CacheEntry> activeCache = new ConcurrentHashMap<>();

    public record SessionView(String id, String deviceLabel, String ipPrefix, String authMethod,
                              OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime expiresAt,
                              boolean current) {}

    public SessionService(UserSessionRepository sessionRepository, UserRepository userRepository, JwtService jwtService,
                          AuditService auditService, SecurityMonitor monitor, EmailService emailService) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.monitor = monitor;
        this.emailService = emailService;
    }

    // ─── Emisión ──────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse create(User user, UserSession.AuthMethod method, boolean rememberMe, HttpServletRequest request) {
        String ip = ClientInfo.ip(request);
        String agent = ClientInfo.userAgent(request);
        String device = ClientInfo.deviceLabel(agent);
        String ipPrefix = ClientInfo.ipPrefix(ip);
        boolean knownDevice = sessionRepository.existsByUserIdAndDeviceLabelAndIpPrefix(user.getId(), device, ipPrefix);
        boolean firstSession = user.getLastLoginAt() == null;

        String refreshToken = Hashing.randomToken();
        LocalDateTime now = LocalDateTime.now();
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        session.setRefreshTokenHash(Hashing.sha256(refreshToken));
        session.setAuthMethod(method);
        session.setRememberMe(rememberMe);
        session.setUserAgent(agent);
        session.setDeviceLabel(device);
        session.setIpPrefix(ipPrefix);
        session.setAuthenticatedAt(now);
        session.setCreatedAt(now);
        session.setLastUsedAt(now);
        session.setExpiresAt(rememberMe ? now.plusDays(rememberDays) : now.plusHours(defaultHours));
        sessionRepository.save(session);

        user.setLastLoginAt(now);
        userRepository.save(user);

        boolean interactiveLogin = method == UserSession.AuthMethod.PASSWORD
                || method == UserSession.AuthMethod.GOOGLE || method == UserSession.AuthMethod.APPLE;
        if (interactiveLogin && !knownDevice && !firstSession) {
            String when = BusinessClock.withOffset(now).toString();
            CompletableFuture.runAsync(() -> emailService.sendNewDeviceLogin(user.getEmail(), user.getFullName(),
                    device, ipPrefix, when));
        }
        return tokens(user, session, refreshToken);
    }

    /**
     * Renueva la sesión. noRollbackFor: la revocación por reutilización se
     * guarda en una transacción propia y tiene que sobrevivir al 401.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 200) {
            throw invalid();
        }
        String hash = Hashing.sha256(refreshToken);
        UserSession session = sessionRepository.findByRefreshHashForUpdate(hash).orElse(null);

        if (session == null) {
            UserSession rotated = sessionRepository.findByPreviousRefreshHashForUpdate(hash).orElse(null);
            if (rotated == null) throw invalid();
            if (rotated.getRotatedAt() != null && rotated.getRotatedAt().plus(ROTATION_GRACE).isAfter(LocalDateTime.now())
                    && rotated.isActive()) {
                throw new BusinessException(HttpStatus.CONFLICT, "REFRESH_ROTATED",
                        "La sesión se renovó en otra pestaña. Usá el token vigente.");
            }
            revokeForReuse(rotated);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED",
                    "Por seguridad cerramos esta sesión. Iniciá sesión de nuevo.");
        }

        if (!session.isActive()) throw invalid();
        User user = userRepository.findById(session.getUserId()).orElseThrow(SessionService::invalid);
        if (user.getStatus() != User.Status.ACTIVE) throw invalid();

        String next = Hashing.randomToken();
        session.setPreviousRefreshTokenHash(hash);
        session.setRefreshTokenHash(Hashing.sha256(next));
        session.setRotatedAt(LocalDateTime.now());
        session.setLastUsedAt(LocalDateTime.now());
        sessionRepository.save(session);
        return tokens(user, session, next);
    }

    /**
     * En la misma transacción que ya tiene bloqueada la fila: una transacción
     * aparte esperaría ese bloqueo. refresh() no revierte ante BusinessException,
     * así que la revocación queda guardada aunque la respuesta sea 401.
     */
    private void revokeForReuse(UserSession session) {
        if (session.getRevokedAt() == null) {
            session.setRevokedAt(LocalDateTime.now());
            session.setRevokeReason("REFRESH_REUSE");
            sessionRepository.saveAndFlush(session);
        }
        evict(session.getId());
        User user = userRepository.findById(session.getUserId()).orElse(null);
        auditService.recordSecurityEvent(user != null && user.getCompany() != null ? user.getCompany().getId() : null,
                user, null, AuditAction.REFRESH_REUSE_DETECTED,
                Map.of("sessionId", session.getId(), "device", String.valueOf(session.getDeviceLabel())));
        monitor.recordRefreshReuse(session.getUserId());
        log.warn("Reutilización de refresh token en la sesión {} del usuario {}", session.getId(), session.getUserId());
    }

    // ─── Validación ───────────────────────────────────────────────────────────

    /** Consulta cacheada unos segundos: una revocación en esta instancia se aplica al instante. */
    public boolean isActive(String sessionId, Long userId) {
        if (sessionId == null) return false;
        long now = System.currentTimeMillis();
        CacheEntry cached = activeCache.get(sessionId);
        if (cached != null && now - cached.checkedAt() < ACTIVE_CACHE.toMillis()) {
            return cached.active() && (userId == null || userId.equals(cached.userId()));
        }
        UserSession session = sessionRepository.findById(sessionId).orElse(null);
        boolean active = session != null && session.isActive();
        Long owner = session == null ? null : session.getUserId();
        activeCache.put(sessionId, new CacheEntry(active, owner, now));
        return active && (userId == null || userId.equals(owner));
    }

    // ─── Revocación ───────────────────────────────────────────────────────────

    @Transactional
    public void revoke(Long userId, String sessionId, String reason) {
        UserSession session = sessionRepository.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Sesión no encontrada."));
        if (session.getRevokedAt() == null) {
            session.setRevokedAt(LocalDateTime.now());
            session.setRevokeReason(reason);
            sessionRepository.save(session);
        }
        evict(sessionId);
    }

    @Transactional
    public int revokeAllForUser(Long userId, String keepSessionId, String reason) {
        int revoked = sessionRepository.revokeAllForUser(userId, keepSessionId, reason, LocalDateTime.now());
        activeCache.entrySet().removeIf(e -> userId.equals(e.getValue().userId()) && !e.getKey().equals(keepSessionId));
        return revoked;
    }

    @Transactional
    public int revokeAllForCompany(Long companyId, String reason) {
        int revoked = sessionRepository.revokeAllForCompany(companyId, reason, LocalDateTime.now());
        activeCache.clear();
        return revoked;
    }

    // ─── Consulta y step-up ───────────────────────────────────────────────────

    public List<SessionView> list(Long userId, String currentSessionId) {
        return sessionRepository.findActiveByUser(userId, LocalDateTime.now()).stream()
                .map(s -> new SessionView(s.getId(), s.getDeviceLabel(), s.getIpPrefix(),
                        s.getAuthMethod() == null ? null : s.getAuthMethod().name(),
                        BusinessClock.withOffset(s.getCreatedAt()), BusinessClock.withOffset(s.getLastUsedAt()),
                        BusinessClock.withOffset(s.getExpiresAt()), s.getId().equals(currentSessionId)))
                .toList();
    }

    @Transactional
    public void markReauthenticated(String sessionId) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setAuthenticatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        });
    }

    /** Acciones sensibles: exigen haber confirmado la identidad hace poco. */
    public void requireRecentAuth(String sessionId, Duration maxAge) {
        UserSession session = sessionId == null ? null : sessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getAuthenticatedAt() == null
                || session.getAuthenticatedAt().plus(maxAge).isBefore(LocalDateTime.now())) {
            throw new ForbiddenException(ForbiddenException.REAUTH_REQUIRED,
                    "Por seguridad, confirmá tu identidad para continuar.");
        }
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "America/Lima")
    @Transactional
    public void purgeOld() {
        int deleted = sessionRepository.deleteOlderThan(LocalDateTime.now().minusDays(30));
        if (deleted > 0) log.info("Sesiones vencidas eliminadas: {}", deleted);
        activeCache.clear();
    }

    private AuthResponse tokens(User user, UserSession session, String refreshToken) {
        return new AuthResponse(jwtService.generateAccessToken(user, session.getId()), refreshToken,
                jwtService.accessTtl().toSeconds(), BusinessClock.withOffset(session.getExpiresAt()), session.getId());
    }

    private void evict(String sessionId) {
        activeCache.remove(sessionId);
    }

    private static BusinessException invalid() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "La sesión venció. Iniciá sesión de nuevo.");
    }
}
