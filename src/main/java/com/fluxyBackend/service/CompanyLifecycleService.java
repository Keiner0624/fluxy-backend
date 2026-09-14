package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Status;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.ForbiddenException;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.Hashing;
import com.fluxyBackend.security.SecurityMonitor;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.MemberRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ciclo de vida de los negocios FREE sin actividad real, y la eliminación a
 * pedido del dueño.
 *
 * ACTIVE → INACTIVE (30 días) → SUSPENDED (60) → ARCHIVED (90) →
 * DELETION_PENDING (180) → ANONYMIZED. Cada paso exige además un tiempo mínimo
 * en el estado anterior, para que el aviso llegue antes que la consecuencia
 * aunque el job haya estado detenido. Los planes pagos vigentes no avanzan.
 */
@Service
public class CompanyLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CompanyLifecycleService.class);
    static final String REASON_INACTIVITY = "INACTIVITY";
    static final String REASON_OWNER = "OWNER_REQUEST";
    private static final Duration TOUCH_DEBOUNCE = Duration.ofMinutes(30);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-PE"));

    private final CompanyRepository companyRepository;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final EmailService emailService;
    private final SecurityMonitor monitor;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    private final Map<Long, Long> lastTouch = new ConcurrentHashMap<>();

    @Value("${app.lifecycle.inactive_days:30}") int inactiveDays;
    @Value("${app.lifecycle.suspend_days:60}") int suspendDays;
    @Value("${app.lifecycle.archive_days:90}") int archiveDays;
    @Value("${app.lifecycle.deletion_days:180}") int deletionDays;
    @Value("${app.lifecycle.deletion_grace_days:30}") int deletionGraceDays;
    @Value("${app.lifecycle.owner_deletion_grace_days:14}") int ownerDeletionGraceDays;
    /** Apagado por defecto: la anonimización es irreversible y se habilita a conciencia. */
    @Value("${app.lifecycle.purge_enabled:false}") boolean purgeEnabled;

    public CompanyLifecycleService(CompanyRepository companyRepository, MembershipRepository membershipRepository,
                                   UserRepository userRepository, SessionService sessionService,
                                   AuditService auditService, EmailService emailService, SecurityMonitor monitor,
                                   JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.companyRepository = companyRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.emailService = emailService;
        this.monitor = monitor;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public record LifecycleView(String status, String reason, LocalDateTime lastBusinessActivityAt,
                                LocalDateTime deletionScheduledAt, boolean paidPlan, boolean canReactivate,
                                boolean storeAcceptsOrders, boolean storeOnline) {}

    public record DeletionRequest(String confirmation) {}

    // ─── Actividad real ───────────────────────────────────────────────────────

    /**
     * Registra actividad de negocio (un cambio hecho en el panel o un pedido
     * recibido). Reactiva sola a una empresa INACTIVE; SUSPENDED en adelante
     * exige reactivarla a propósito.
     */
    public void recordActivity(Long companyId) {
        if (companyId == null) return;
        long now = System.currentTimeMillis();
        Long previous = lastTouch.get(companyId);
        if (previous != null && now - previous < TOUCH_DEBOUNCE.toMillis()) return;
        lastTouch.put(companyId, now);
        try {
            jdbc.update("UPDATE company SET last_business_activity_at = ? WHERE id = ?", LocalDateTime.now(), companyId);
            int reactivated = jdbc.update("UPDATE company SET status = 'ACTIVE', inactive_at = NULL, suspension_reason = NULL "
                    + "WHERE id = ? AND status = 'INACTIVE'", companyId);
            if (reactivated > 0) monitor.recordLifecycle("REACTIVATED");
        } catch (RuntimeException e) {
            lastTouch.remove(companyId);
            log.warn("No se pudo registrar actividad de la empresa {}: {}", companyId, e.getMessage());
        }
    }

    /** Operaciones del panel que modifican datos: bloqueadas mientras la tienda está archivada o por eliminarse. */
    public void ensureWritable(Company company) {
        Status status = company.getStatus();
        if (status == Status.ARCHIVED || status == Status.DELETION_PENDING || status == Status.ANONYMIZED) {
            throw new BusinessException(HttpStatus.LOCKED, "COMPANY_" + status.name(),
                    status == Status.DELETION_PENDING
                            ? "El negocio tiene la eliminación programada. Cancelala para seguir operando."
                            : "El negocio está archivado. Reactivalo para seguir operando.");
        }
    }

    /** Pedidos desde la tienda pública. */
    public void ensureStoreAcceptsOrders(Company company) {
        ensureStoreOnline(company);
        if (company.getStatus() == Status.SUSPENDED) {
            throw new BusinessException(HttpStatus.LOCKED, "STORE_SUSPENDED",
                    "Esta tienda no está recibiendo pedidos por el momento.");
        }
    }

    public void ensureStoreOnline(Company company) {
        Status status = company.getStatus();
        if (status == Status.ARCHIVED || status == Status.DELETION_PENDING || status == Status.ANONYMIZED) {
            throw new BusinessException(HttpStatus.GONE, "STORE_OFFLINE", "Esta tienda no está disponible.");
        }
    }

    public LifecycleView view(Company company) {
        Status status = company.getStatus();
        return new LifecycleView(status.name(), company.getSuspensionReason(), company.getLastBusinessActivityAt(),
                company.getDeletionScheduledAt(), company.hasActivePaidPlan(),
                status == Status.INACTIVE || status == Status.SUSPENDED || status == Status.ARCHIVED
                        || (status == Status.DELETION_PENDING && REASON_INACTIVITY.equals(company.getSuspensionReason())),
                status == Status.ACTIVE || status == Status.INACTIVE,
                status == Status.ACTIVE || status == Status.INACTIVE || status == Status.SUSPENDED);
    }

    // ─── Acciones del dueño ───────────────────────────────────────────────────

    @Transactional
    public LifecycleView reactivate(Member member) {
        Company company = companyRepository.findByIdForUpdate(member.companyId()).orElseThrow();
        Status status = company.getStatus();
        if (status == Status.ANONYMIZED) throw new BusinessException(HttpStatus.GONE, "COMPANY_ANONYMIZED", "Este negocio fue eliminado.");
        if (status == Status.DELETION_PENDING && REASON_OWNER.equals(company.getSuspensionReason())) {
            throw new BusinessException(HttpStatus.CONFLICT, "DELETION_REQUESTED", "Cancelá la eliminación para reactivar el negocio.");
        }
        if (status != Status.ACTIVE) {
            activate(company);
            companyRepository.save(company);
            auditService.record(member, AuditAction.COMPANY_STATUS_CHANGED, "COMPANY", company.getId(),
                    Map.of("from", status.name(), "to", Status.ACTIVE.name(), "by", "REACTIVATION"));
            monitor.recordLifecycle("REACTIVATED");
        }
        lastTouch.remove(company.getId());
        return view(company);
    }

    /** Solo el dueño, con identidad reciente y escribiendo el nombre del negocio. */
    @Transactional
    public LifecycleView requestDeletion(Member member, String sessionId, DeletionRequest request) {
        requireOwner(member);
        sessionService.requireRecentAuth(sessionId, Duration.ofMinutes(10));
        Company company = companyRepository.findByIdForUpdate(member.companyId()).orElseThrow();
        String expected = company.getName() == null ? "" : company.getName().strip();
        String typed = request == null || request.confirmation() == null ? "" : request.confirmation().strip();
        if (expected.isEmpty() || !expected.equalsIgnoreCase(typed)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CONFIRMATION_MISMATCH",
                    "Escribí el nombre exacto del negocio para confirmar.");
        }
        if (company.getStatus() == Status.ANONYMIZED) throw new BusinessException(HttpStatus.GONE, "COMPANY_ANONYMIZED", "Este negocio fue eliminado.");
        if (company.hasActivePaidPlan()) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAID_PLAN_ACTIVE",
                    "Tenés un plan pago vigente. Esperá a que venza o escribinos para darlo de baja.");
        }
        Status previous = company.getStatus();
        LocalDateTime when = LocalDateTime.now().plusDays(ownerDeletionGraceDays);
        company.setStatus(Status.DELETION_PENDING);
        company.setSuspensionReason(REASON_OWNER);
        company.setDeletionScheduledAt(when);
        companyRepository.save(company);
        auditService.record(member, AuditAction.COMPANY_DELETION_REQUESTED, "COMPANY", company.getId(),
                Map.of("from", previous.name(), "scheduledFor", when.toString()));
        notifyOwner(company, "Programaste la eliminación de tu negocio",
                "Tu tienda salió de línea y el negocio se eliminará el " + DATE.format(when)
                        + ". Hasta esa fecha podés cancelarlo desde Configuración.");
        return view(company);
    }

    @Transactional
    public LifecycleView cancelDeletion(Member member) {
        requireOwner(member);
        Company company = companyRepository.findByIdForUpdate(member.companyId()).orElseThrow();
        if (company.getStatus() != Status.DELETION_PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "NO_DELETION_PENDING", "El negocio no tiene una eliminación programada.");
        }
        activate(company);
        companyRepository.save(company);
        auditService.record(member, AuditAction.COMPANY_DELETION_CANCELLED, "COMPANY", company.getId(), null);
        lastTouch.remove(company.getId());
        return view(company);
    }

    // ─── Job diario ───────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 5 * * *", zone = "America/Lima")
    public void runDaily() {
        int changed = 0;
        for (Company company : companyRepository.findAll()) {
            try {
                Boolean result = transactions.execute(tx -> evaluate(company.getId(), LocalDateTime.now()));
                if (Boolean.TRUE.equals(result)) changed++;
            } catch (RuntimeException e) {
                log.error("Ciclo de vida: falló la empresa {}: {}", company.getId(), e.getMessage());
            }
        }
        log.info("Ciclo de vida: {} empresas cambiaron de estado", changed);
    }

    /** Evalúa una empresa. Público para las pruebas, que fijan la fecha. */
    public boolean evaluate(Long companyId, LocalDateTime now) {
        Company company = companyRepository.findByIdForUpdate(companyId).orElse(null);
        if (company == null) return false;
        Status status = company.getStatus();
        if (status == Status.ANONYMIZED) return false;

        if (status == Status.DELETION_PENDING) {
            if (company.getDeletionScheduledAt() != null && company.getDeletionScheduledAt().isBefore(now)) {
                if (!purgeEnabled) {
                    log.warn("Empresa {} lista para anonimizar, pero app.lifecycle.purge_enabled=false", companyId);
                    return false;
                }
                anonymize(company);
                return true;
            }
            if (REASON_INACTIVITY.equals(company.getSuspensionReason()) && company.hasActivePaidPlan()) {
                return restore(company, status);
            }
            return false;
        }

        // Los planes pagos no se suspenden; si pagó estando inactiva, vuelve sola.
        if (company.hasActivePaidPlan()) {
            return status != Status.ACTIVE && restore(company, status);
        }

        LocalDateTime last = company.getLastBusinessActivityAt();
        if (last == null) {
            // Empresas previas al ciclo de vida: el reloj arranca hoy.
            company.setLastBusinessActivityAt(now);
            companyRepository.save(company);
            return false;
        }
        long idle = Duration.between(last, now).toDays();

        switch (status) {
            case ACTIVE -> {
                if (idle >= inactiveDays) {
                    company.setInactiveAt(now);
                    return move(company, Status.INACTIVE, now, "Tu tienda está sin actividad",
                            "Hace " + idle + " días que no hay movimiento en tu negocio. La tienda sigue abierta. "
                                    + "Si no hay actividad, dejará de recibir pedidos en " + (suspendDays - idle) + " días.");
                }
            }
            case INACTIVE -> {
                if (idle >= suspendDays && since(company.getInactiveAt(), now) >= 7) {
                    company.setSuspendedAt(now);
                    return move(company, Status.SUSPENDED, now, "Tu tienda dejó de recibir pedidos",
                            "Por " + idle + " días sin actividad, tu tienda muestra el catálogo pero no acepta pedidos. "
                                    + "Entrá a tu panel y reactivala con un clic.");
                }
            }
            case SUSPENDED -> {
                if (idle >= archiveDays && since(company.getSuspendedAt(), now) >= 14) {
                    company.setArchivedAt(now);
                    return move(company, Status.ARCHIVED, now, "Tu tienda fue archivada",
                            "Tu tienda salió de línea por inactividad. Tus datos siguen guardados: reactivala desde tu panel cuando quieras.");
                }
            }
            case ARCHIVED -> {
                if (idle >= deletionDays && since(company.getArchivedAt(), now) >= 30) {
                    LocalDateTime when = now.plusDays(deletionGraceDays);
                    company.setDeletionScheduledAt(when);
                    return move(company, Status.DELETION_PENDING, now, "Tu negocio se eliminará pronto",
                            "Por " + idle + " días sin actividad, el negocio y sus datos se eliminarán el " + DATE.format(when)
                                    + ". Para evitarlo, entrá a tu panel y reactivalo.");
                }
            }
            default -> { }
        }
        return false;
    }

    /** Cuentas que nunca terminaron el registro y empresas que nunca publicaron. */
    @Scheduled(cron = "0 20 5 * * *", zone = "America/Lima")
    public void reportAbandonedOnboarding() {
        try {
            Integer abandoned = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM company c
                    WHERE (c.status IS NULL OR c.status = 'ACTIVE') AND c.plan = 'FREE'
                      AND NOT EXISTS (SELECT 1 FROM products p WHERE p.company_id = c.id)
                      AND c.last_business_activity_at < ?
                    """, Integer.class, LocalDateTime.now().minusDays(inactiveDays));
            if (abandoned != null && abandoned > 0) {
                log.info("Onboarding: {} negocios sin productos y sin actividad en {} días (siguen el ciclo normal)", abandoned, inactiveDays);
            }
        } catch (RuntimeException e) {
            log.debug("No se pudo contar onboarding abandonado: {}", e.getMessage());
        }
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private boolean move(Company company, Status to, LocalDateTime now, String title, String text) {
        Status from = company.getStatus();
        company.setStatus(to);
        company.setSuspensionReason(REASON_INACTIVITY);
        companyRepository.save(company);
        auditService.record(company.getId(), null, AuditAction.COMPANY_STATUS_CHANGED, "COMPANY", company.getId(),
                Map.of("from", from.name(), "to", to.name(), "by", "INACTIVITY_JOB"));
        monitor.recordLifecycle(to.name());
        notifyOwner(company, title, text);
        log.info("Empresa {}: {} → {} (sin actividad desde {})", company.getId(), from, to, company.getLastBusinessActivityAt());
        return true;
    }

    private boolean restore(Company company, Status from) {
        activate(company);
        companyRepository.save(company);
        auditService.record(company.getId(), null, AuditAction.COMPANY_STATUS_CHANGED, "COMPANY", company.getId(),
                Map.of("from", from.name(), "to", Status.ACTIVE.name(), "by", "PAID_PLAN"));
        monitor.recordLifecycle("REACTIVATED");
        return true;
    }

    private static void activate(Company company) {
        company.setStatus(Status.ACTIVE);
        company.setSuspensionReason(null);
        company.setInactiveAt(null);
        company.setSuspendedAt(null);
        company.setArchivedAt(null);
        company.setDeletionScheduledAt(null);
        company.setLastBusinessActivityAt(LocalDateTime.now());
    }

    /**
     * Borra los datos personales y deja los montos: los pedidos quedan como
     * registro contable sin nombres, teléfonos ni direcciones.
     */
    private void anonymize(Company company) {
        Long id = company.getId();
        jdbc.update("UPDATE orders SET customer_name = 'Cliente eliminado', customer_phone = NULL, customer_address = NULL WHERE company_id = ?", id);
        jdbc.update("UPDATE customers SET name = 'Cliente eliminado', name_key = CONCAT('deleted-', id), phone = NULL, phone_key = NULL, "
                + "email = NULL, address = NULL, notes = NULL, tags = NULL WHERE company_id = ?", id);
        for (User user : userRepository.findByCompanyId(id)) {
            sessionService.revokeAllForUser(user.getId(), null, "COMPANY_ANONYMIZED");
            jdbc.update("DELETE FROM user_identities WHERE user_id = ?", user.getId());
            user.setEmail("deleted-" + user.getId() + "@deleted.fluxy.invalid");
            user.setFullName("Usuario eliminado");
            user.setPhone(null);
            user.setPassword(Hashing.randomToken());
            user.setPasswordEnabled(false);
            user.setStatus(User.Status.DISABLED);
            userRepository.save(user);
        }
        company.setName("Negocio eliminado");
        company.setEmail(null);
        company.setPhone(null);
        company.setAddress(null);
        company.setDescription(null);
        company.setLogoUrl(null);
        company.setCustomDomain(null);
        company.setSlug("deleted-" + id + "-" + Hashing.randomToken().substring(0, 6).toLowerCase(Locale.ROOT));
        company.setStatus(Status.ANONYMIZED);
        companyRepository.save(company);
        auditService.record(id, null, AuditAction.COMPANY_ANONYMIZED, "COMPANY", id, null);
        monitor.recordLifecycle(Status.ANONYMIZED.name());
        log.warn("Empresa {} anonimizada", id);
    }

    private void notifyOwner(Company company, String title, String text) {
        Optional<User> owner = owner(company.getId());
        if (owner.isEmpty()) return;
        String email = owner.get().getEmail();
        String name = owner.get().getFullName();
        String companyName = company.getName();
        CompletableFuture.runAsync(() -> emailService.sendLifecycleNotice(email, name, companyName, title, text));
    }

    private Optional<User> owner(Long companyId) {
        return membershipRepository.findByCompanyId(companyId).stream()
                .filter(m -> MemberRole.parse(m.getRole()) == MemberRole.OWNER)
                .map(Membership::getUserId)
                .findFirst()
                .flatMap(userRepository::findById);
    }

    private static void requireOwner(Member member) {
        if (!member.isOwner()) {
            throw new ForbiddenException(ForbiddenException.OWNER_ONLY, "Solo el dueño del negocio puede hacer esto.");
        }
    }

    private static long since(LocalDateTime from, LocalDateTime now) {
        return from == null ? Long.MAX_VALUE : Duration.between(from, now).toDays();
    }
}
