package com.fluxyBackend.billing;

import com.fluxyBackend.billing.BillingPayment.Kind;
import com.fluxyBackend.billing.PaymentProvider.ProviderPayment;
import com.fluxyBackend.billing.Subscription.Status;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.ProcessedPaymentRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import com.fluxyBackend.service.BusinessClock;
import com.fluxyBackend.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Suscripciones con renovación manual: cada pago compra meses de un plan.
 *
 * - Mismo plan: se suman meses al final del periodo pagado (no se pierden días).
 * - Plan superior: se aplica al instante; los días que quedaban del plan anterior se convierten
 *   en días del nuevo plan en proporción al precio.
 * - Plan inferior: queda programado para cuando termina lo ya pagado.
 * - Cancelar: el plan sigue hasta el fin del último periodo pagado y después pasa a FREE.
 *   Se puede deshacer hasta esa fecha. Nunca se borran datos.
 *
 * Company.plan y Company.planExpiresAt son una copia para lecturas rápidas; se escriben solo acá.
 */
@Slf4j
@Service
public class SubscriptionService {

    public static final int MAX_MONTHS = 12;
    static final Set<Status> LIVE = EnumSet.of(Status.ACTIVE, Status.TRIALING);
    private static final Set<String> CANCEL_REASONS = Set.of(
            "TOO_EXPENSIVE", "NOT_USING", "MISSING_FEATURES", "SWITCHING", "TEMPORARY", "OTHER");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-PE"));
    private static final double SECONDS_PER_DAY = 86_400d;

    private final SubscriptionRepository subscriptions;
    private final SubscriptionEventRepository events;
    private final BillingPaymentRepository payments;
    private final CompanyRepository companies;
    private final UserRepository users;
    private final ProductRepository products;
    private final ProcessedPaymentRepository processedPayments;
    private final PaymentProvider provider;
    private final AuditService audit;
    private final EmailService email;
    private final TransactionTemplate transactions;

    public SubscriptionService(SubscriptionRepository subscriptions, SubscriptionEventRepository events,
                               BillingPaymentRepository payments, CompanyRepository companies, UserRepository users,
                               ProductRepository products, ProcessedPaymentRepository processedPayments,
                               PaymentProvider provider, AuditService audit, EmailService email,
                               TransactionTemplate transactions) {
        this.subscriptions = subscriptions;
        this.events = events;
        this.payments = payments;
        this.companies = companies;
        this.users = users;
        this.products = products;
        this.processedPayments = processedPayments;
        this.provider = provider;
        this.audit = audit;
        this.email = email;
        this.transactions = transactions;
    }

    // ─── Vistas ──────────────────────────────────────────────────────────────

    public record PlanView(String code, String name, BigDecimal monthlyPrice, String currency, String interval,
                           int productLimit, List<String> features, int version) {}

    public record PendingChange(String plan, String planName, OffsetDateTime effectiveAt, OffsetDateTime paidUntil) {}

    public record Usage(long products, int productLimit) {}

    public record LastSubscription(String plan, String planName, String status, OffsetDateTime endedAt) {}

    public record View(String plan, String planName, String status, String renewal, boolean trial,
                       OffsetDateTime currentPeriodStart, OffsetDateTime currentPeriodEnd, OffsetDateTime paidUntil,
                       long daysLeft, BigDecimal monthlyPrice, String currency, boolean cancelAtPeriodEnd,
                       OffsetDateTime cancellationRequestedAt, String cancellationReason, PendingChange pendingChange,
                       boolean canCancel, boolean canReactivate, boolean trialAvailable, Usage usage,
                       LastSubscription last) {}

    public record Quote(String kind, String plan, String planName, int months, BigDecimal amount, String currency,
                        OffsetDateTime periodStart, OffsetDateTime periodEnd, double creditDays,
                        List<String> notes, List<String> warnings) {}

    public record CheckoutResponse(String checkoutUrl, String sandboxUrl, Quote quote) {}

    public record EventView(String type, String fromPlan, String toPlan, OffsetDateTime effectiveAt, String actor,
                            String detail, OffsetDateTime createdAt) {}

    public record PaymentView(String kind, String plan, int months, BigDecimal amount, String currency,
                              OffsetDateTime periodStart, OffsetDateTime periodEnd, Double creditDays,
                              OffsetDateTime paidAt) {}

    public record History(List<EventView> events, List<PaymentView> payments) {}

    public record ActivationResult(Long companyId, Plan plan, Kind kind, LocalDateTime paidUntil,
                                   LocalDateTime effectiveAt, String ownerEmail, String ownerName) {}

    public List<PlanView> plans() {
        return PlanCatalog.all().stream().map(p -> new PlanView(p.code().name(), p.name(), p.monthlyPrice(),
                PlanCatalog.CURRENCY, PlanCatalog.INTERVAL, p.productLimit(),
                p.features().stream().map(Enum::name).toList(), PlanCatalog.VERSION)).toList();
    }

    @Transactional(readOnly = true)
    public View view(Company company) {
        return view(company, subscriptions.findByCompanyId(company.getId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public Usage usage(Company company) {
        Plan plan = PlanCatalog.effectivePlan(company);
        return new Usage(products.countByCompany(company), PlanCatalog.info(plan).productLimit());
    }

    @Transactional(readOnly = true)
    public History history(Company company) {
        PageRequest last50 = PageRequest.of(0, 50);
        List<EventView> eventViews = events.findByCompanyIdOrderByCreatedAtDescIdDesc(company.getId(), last50).stream()
                .map(e -> new EventView(e.getType().name(), name(e.getFromPlan()), name(e.getToPlan()),
                        at(e.getEffectiveAt()), e.getActorLabel(), e.getDetail(), at(e.getCreatedAt())))
                .toList();
        List<PaymentView> paymentViews = payments.findByCompanyIdOrderByPaidAtDescIdDesc(company.getId(), last50).stream()
                .map(p -> new PaymentView(p.getKind().name(), p.getPlan().name(), p.getMonths(), p.getAmount(),
                        p.getCurrency(), at(p.getPeriodStart()), at(p.getPeriodEnd()), p.getCreditDays(), at(p.getPaidAt())))
                .toList();
        return new History(eventViews, paymentViews);
    }

    // ─── Cotización y checkout ───────────────────────────────────────────────

    /** Qué pasaría al pagar el plan y los meses indicados, antes de ir al proveedor. */
    @Transactional(readOnly = true)
    public Quote quote(Company company, String rawPlan, int months) {
        Plan target = parsePaidPlan(rawPlan);
        validateMonths(months);
        Subscription s = subscriptions.findByCompanyId(company.getId()).orElse(null);
        Transition t = transition(s, target, months, LocalDateTime.now());
        return toQuote(company, s, t);
    }

    @Transactional(readOnly = true)
    public CheckoutResponse checkout(Member member, String rawPlan, int months) {
        Quote quote = quote(member.company(), rawPlan, months);
        Plan plan = Plan.valueOf(quote.plan());
        String title = "Plan " + quote.planName() + " — " + months + (months == 1 ? " mes" : " meses");
        PaymentProvider.CheckoutResult result = provider.createCheckout(new PaymentProvider.CheckoutCommand(
                member.companyId(), plan, months, quote.amount(), PlanCatalog.CURRENCY, title,
                externalReference(member.companyId(), plan, months)));
        return new CheckoutResponse(result.checkoutUrl(), result.sandboxUrl(), quote);
    }

    // ─── Pago confirmado ─────────────────────────────────────────────────────

    /**
     * Aplica un pago aprobado. Idempotente: un pago ya procesado no vuelve a sumar meses.
     * El tipo de cambio se decide al confirmar el pago con el estado de ese momento.
     */
    @Transactional
    public Optional<ActivationResult> applyPayment(ProviderPayment payment) {
        if (payment == null || payment.id() == null || !payment.approved()) return Optional.empty();

        Reference ref = parseReference(payment.externalReference());
        BigDecimal expected = PlanCatalog.info(ref.plan()).monthlyPrice().multiply(BigDecimal.valueOf(ref.months()));
        if (payment.amount() == null || expected.compareTo(payment.amount()) != 0
                || !PlanCatalog.CURRENCY.equalsIgnoreCase(payment.currency())) {
            throw new IllegalArgumentException("El monto o la moneda del pago no coincide con el plan");
        }

        Company company = companies.findByIdForUpdate(ref.companyId())
                .orElseThrow(() -> new IllegalArgumentException("Empresa no encontrada"));
        // Con la empresa bloqueada, dos avisos del mismo pago no pueden pasar a la vez.
        if (payments.existsByProviderPaymentId(payment.id()) || processedPayments.existsByPaymentId(payment.id())) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        Subscription s = lockOrCreate(company.getId());
        Plan before = PlanCatalog.effectivePlan(company);
        Transition t = transition(s, ref.plan(), ref.months(), now);

        switch (t.kind()) {
            case NEW -> {
                s.setPlan(ref.plan());
                s.setStatus(Status.ACTIVE);
                s.setCurrentPeriodStart(now);
                s.setCurrentPeriodEnd(t.periodEnd());
                s.clearPendingChange();
                s.setCanceledAt(null);
                company.setPlanActivatedAt(now);
            }
            case RENEWAL -> {
                if (t.queued()) {
                    s.setNextPeriodEnd(t.periodEnd());
                } else {
                    // Meses del plan actual antes de un cambio programado: el cambio se corre la misma cantidad.
                    Duration added = Duration.between(s.getCurrentPeriodEnd(), t.periodEnd());
                    s.setCurrentPeriodEnd(t.periodEnd());
                    if (s.hasPendingChange()) {
                        s.setChangeEffectiveAt(t.periodEnd());
                        s.setNextPeriodEnd(s.getNextPeriodEnd().plus(added));
                    }
                    s.setStatus(Status.ACTIVE);
                }
            }
            case UPGRADE -> {
                s.setPlan(ref.plan());
                s.setStatus(Status.ACTIVE);
                s.setCurrentPeriodStart(now);
                s.setCurrentPeriodEnd(t.periodEnd());
                s.clearPendingChange();
                company.setPlanActivatedAt(now);
            }
            case DOWNGRADE -> {
                s.setNextPlan(ref.plan());
                s.setChangeEffectiveAt(t.periodStart());
                s.setNextPeriodEnd(t.periodEnd());
            }
        }
        s.setProvider(provider.name());
        boolean wasCanceling = s.isCancelAtPeriodEnd();
        s.clearCancellation();
        syncCompany(company, s, now);
        reopenStore(company, now);

        payments.save(new BillingPayment(company.getId(), s.getId(), provider.name(), payment.id(), ref.plan(),
                ref.months(), payment.amount(), PlanCatalog.CURRENCY, t.kind(), t.periodStart(), t.periodEnd(),
                t.creditSeconds() > 0 ? round1(t.creditSeconds() / SECONDS_PER_DAY) : null));
        SubscriptionEvent.Type type = switch (t.kind()) {
            case NEW -> SubscriptionEvent.Type.SUBSCRIPTION_STARTED;
            case RENEWAL -> SubscriptionEvent.Type.RENEWED;
            case UPGRADE -> SubscriptionEvent.Type.PLAN_UPGRADED;
            case DOWNGRADE -> SubscriptionEvent.Type.DOWNGRADE_SCHEDULED;
        };
        events.save(new SubscriptionEvent(company.getId(), type, before, ref.plan(), t.periodStart(), null,
                "Pago " + payment.id(), ref.months() + (ref.months() == 1 ? " mes" : " meses")));
        if (wasCanceling) {
            events.save(new SubscriptionEvent(company.getId(), SubscriptionEvent.Type.CANCELLATION_REVOKED,
                    s.getPlan(), s.getPlan(), now, null, "Pago " + payment.id(), "Se pagó un nuevo periodo"));
        }
        audit.record(company.getId(), null, AuditAction.PLAN_CHANGED, "COMPANY", company.getId(),
                Map.of("from", before.name(), "to", ref.plan().name(), "months", ref.months(),
                        "flow", t.kind().name(), "paymentId", payment.id()));

        User owner = owner(company.getId());
        return Optional.of(new ActivationResult(company.getId(), ref.plan(), t.kind(), s.paidUntil(), t.periodStart(),
                owner == null ? null : owner.getEmail(), owner == null ? null : owner.getFullName()));
    }

    // ─── Cancelar y reactivar ────────────────────────────────────────────────

    @Transactional
    public View cancel(Member member, String reason, String comment) {
        LocalDateTime now = LocalDateTime.now();
        Subscription s = subscriptions.findByCompanyIdForUpdate(member.companyId()).orElse(null);
        if (s == null || !s.isLive(now) || s.getPlan() == Plan.FREE) {
            throw BusinessException.conflict("NO_ACTIVE_SUBSCRIPTION", "No tenés una suscripción paga activa para cancelar.");
        }
        if (s.isCancelAtPeriodEnd()) return view(member.company(), s);

        s.setCancelAtPeriodEnd(true);
        s.setCancellationRequestedAt(now);
        s.setCancellationReason(reason != null && CANCEL_REASONS.contains(reason) ? reason : null);
        s.setCancellationComment(comment == null || comment.isBlank() ? null : comment.strip().substring(0, Math.min(comment.strip().length(), 500)));
        LocalDateTime endsAt = s.paidUntil();
        events.save(new SubscriptionEvent(member.companyId(), SubscriptionEvent.Type.CANCELLATION_REQUESTED, s.getPlan(),
                Plan.FREE, endsAt, member.user().getId(), member.displayName(), s.getCancellationReason()));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("plan", s.getPlan().name());
        meta.put("effectiveAt", endsAt.toString());
        if (s.getCancellationReason() != null) meta.put("reason", s.getCancellationReason());
        audit.record(member, AuditAction.SUBSCRIPTION_CANCEL_REQUESTED, "SUBSCRIPTION", s.getId(), meta);

        String planName = PlanCatalog.info(s.getPlan()).name();
        notifyOwner(member.companyId(), "Cancelaste tu suscripción",
                "Tu plan " + planName + " sigue activo hasta el " + day(endsAt) + ". Después tu tienda pasa al plan Free: "
                        + "no se borra nada y no se hace ningún cobro. Podés reactivarla desde Plan y facturación antes de esa fecha.");
        return view(member.company(), s);
    }

    @Transactional
    public View reactivate(Member member) {
        LocalDateTime now = LocalDateTime.now();
        Subscription s = subscriptions.findByCompanyIdForUpdate(member.companyId()).orElse(null);
        if (s == null || !s.isLive(now) || s.getPlan() == Plan.FREE) {
            throw BusinessException.conflict("SUBSCRIPTION_ENDED", "Tu suscripción ya terminó. Elegí un plan para volver a suscribirte.");
        }
        if (!s.isCancelAtPeriodEnd()) return view(member.company(), s);

        s.clearCancellation();
        events.save(new SubscriptionEvent(member.companyId(), SubscriptionEvent.Type.CANCELLATION_REVOKED, s.getPlan(),
                s.getPlan(), now, member.user().getId(), member.displayName(), null));
        audit.record(member, AuditAction.SUBSCRIPTION_REACTIVATED, "SUBSCRIPTION", s.getId(), Map.of("plan", s.getPlan().name()));
        return view(member.company(), s);
    }

    // ─── Prueba y administración ─────────────────────────────────────────────

    @Transactional
    public View startTrial(Member member) {
        Company company = companies.findByIdForUpdate(member.companyId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        if (company.isTrialUsed()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TRIAL_USED", "Ya utilizaste tu período de prueba gratuito.");
        }
        Subscription s = lockOrCreate(company.getId());
        if (s.isLive(now)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PLAN_ACTIVE", "Ya tenés un plan activo.");
        }
        s.setPlan(Plan.PRO);
        s.setStatus(Status.TRIALING);
        s.setCurrentPeriodStart(now);
        s.setCurrentPeriodEnd(now.plusMonths(1));
        s.clearPendingChange();
        s.clearCancellation();
        s.setCanceledAt(null);
        company.setTrialUsed(true);
        company.setPlanActivatedAt(now);
        syncCompany(company, s, now);
        events.save(new SubscriptionEvent(company.getId(), SubscriptionEvent.Type.TRIAL_STARTED, Plan.FREE, Plan.PRO, now,
                member.user().getId(), member.displayName(), "1 mes"));
        audit.record(member, AuditAction.PLAN_CHANGED, "COMPANY", company.getId(), Map.of("from", "FREE", "to", "PRO", "by", "TRIAL"));
        LocalDateTime endsAt = s.getCurrentPeriodEnd();
        String ownerEmail = member.user().getEmail();
        String ownerName = member.user().getFullName();
        CompletableFuture.runAsync(() -> email.sendTrialActivatedEmail(ownerEmail, ownerName, endsAt));
        return view(company, s);
    }

    /** Cambio hecho por el administrador de Fluxy (sin cobro). FREE termina la suscripción al instante. */
    @Transactional
    public void adminGrant(Long companyId, Plan plan, int months, String adminLabel) {
        Company company = companies.findByIdForUpdate(companyId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "Empresa no encontrada"));
        LocalDateTime now = LocalDateTime.now();
        Subscription s = lockOrCreate(companyId);
        Plan before = PlanCatalog.effectivePlan(company);
        s.clearPendingChange();
        s.clearCancellation();
        if (plan == Plan.FREE) {
            if (s.isLive(now)) s.setCurrentPeriodEnd(now);
            s.setStatus(Status.CANCELED);
            s.setCanceledAt(now);
        } else {
            validateMonths(months);
            s.setPlan(plan);
            s.setStatus(Status.ACTIVE);
            s.setCurrentPeriodStart(now);
            s.setCurrentPeriodEnd(now.plusMonths(months));
            s.setCanceledAt(null);
            company.setPlanActivatedAt(now);
        }
        syncCompany(company, s, now);
        events.save(new SubscriptionEvent(companyId, SubscriptionEvent.Type.ADMIN_GRANTED, before, plan, now, null,
                adminLabel, plan == Plan.FREE ? null : months + (months == 1 ? " mes" : " meses")));
        audit.record(companyId, null, AuditAction.PLAN_CHANGED, "COMPANY", companyId,
                Map.of("from", before.name(), "to", plan.name(), "months", months, "by", "ADMIN"));
    }

    // ─── Fin de periodo ──────────────────────────────────────────────────────

    /** Aplica los cambios programados y cierra las suscripciones cuyo periodo pagado terminó. */
    public int applyDueTransitions() {
        List<Long> due = subscriptions.findDueCompanyIds(LIVE, LocalDateTime.now());
        int applied = 0;
        for (Long companyId : due) {
            try {
                Boolean done = transactions.execute(status -> applyTransition(companyId));
                if (Boolean.TRUE.equals(done)) applied++;
            } catch (RuntimeException e) {
                // Se reintenta en la próxima pasada; un error no frena a las demás empresas.
                log.error("No se pudo cerrar el periodo de la empresa {}", companyId, e);
            }
        }
        return applied;
    }

    boolean applyTransition(Long companyId) {
        Company company = companies.findByIdForUpdate(companyId).orElse(null);
        Subscription s = subscriptions.findByCompanyIdForUpdate(companyId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (company == null || s == null || !LIVE.contains(s.getStatus()) || s.getCurrentPeriodEnd().isAfter(now)) {
            return false;
        }

        if (s.hasPendingChange() && !s.getChangeEffectiveAt().isAfter(now)) {
            Plan from = s.getPlan();
            s.setPlan(s.getNextPlan());
            s.setCurrentPeriodStart(s.getChangeEffectiveAt());
            s.setCurrentPeriodEnd(s.getNextPeriodEnd());
            s.clearPendingChange();
            s.setStatus(Status.ACTIVE);
            company.setPlanActivatedAt(now);
            syncCompany(company, s, now);
            events.save(new SubscriptionEvent(companyId, SubscriptionEvent.Type.DOWNGRADE_APPLIED, from, s.getPlan(),
                    s.getCurrentPeriodStart(), null, "Sistema", null));
            audit.record(companyId, null, AuditAction.PLAN_CHANGED, "COMPANY", companyId,
                    Map.of("from", from.name(), "to", s.getPlan().name(), "by", "SCHEDULED_CHANGE"));
            notifyOwner(companyId, "Tu plan ahora es " + PlanCatalog.info(s.getPlan()).name(),
                    "Empezó el periodo que ya habías pagado. Está activo hasta el " + day(s.paidUntil()) + ".");
            if (s.getCurrentPeriodEnd().isAfter(now)) return true;
        }

        Plan from = s.getPlan();
        boolean canceled = s.isCancelAtPeriodEnd();
        s.setStatus(canceled ? Status.CANCELED : Status.EXPIRED);
        s.setCanceledAt(canceled ? now : null);
        company.setPlan(Plan.FREE);
        company.setPlanActivatedAt(null);
        company.setPlanExpiresAt(null);
        events.save(new SubscriptionEvent(companyId,
                canceled ? SubscriptionEvent.Type.SUBSCRIPTION_CANCELED : SubscriptionEvent.Type.SUBSCRIPTION_EXPIRED,
                from, Plan.FREE, s.getCurrentPeriodEnd(), null, "Sistema", null));
        audit.record(companyId, null, AuditAction.PLAN_CHANGED, "COMPANY", companyId,
                Map.of("from", from.name(), "to", "FREE", "by", canceled ? "CANCELLATION" : "EXPIRATION"));
        User owner = owner(companyId);
        if (owner != null) {
            String ownerEmail = owner.getEmail();
            String ownerName = owner.getFullName();
            CompletableFuture.runAsync(() -> email.sendPlanExpiredEmail(ownerEmail, ownerName, from.name()));
        }
        return true;
    }

    /** Avisos antes del fin: a 7, 3 y 1 días, uno por etapa (antes salía uno por día). */
    public int sendReminders() {
        LocalDateTime now = LocalDateTime.now();
        int sent = 0;
        for (Subscription candidate : subscriptions.findEndingBetween(LIVE, now, now.plusDays(7))) {
            if (candidate.hasPendingChange()) continue; // el periodo que sigue ya está pagado
            Boolean done = transactions.execute(status -> remind(candidate.getCompanyId(), LocalDateTime.now()));
            if (Boolean.TRUE.equals(done)) sent++;
        }
        return sent;
    }

    boolean remind(Long companyId, LocalDateTime now) {
        Subscription s = subscriptions.findByCompanyIdForUpdate(companyId).orElse(null);
        if (s == null || !s.isLive(now) || s.hasPendingChange()) return false;
        long hoursLeft = Duration.between(now, s.getCurrentPeriodEnd()).toHours();
        int daysLeft = (int) Math.max(1, Math.ceil(hoursLeft / 24d));
        if (daysLeft > 7) return false;
        int stage = daysLeft <= 1 ? 1 : daysLeft <= 3 ? 3 : 7;
        boolean sameEnd = s.getCurrentPeriodEnd().equals(s.getReminderFor());
        if (sameEnd && s.getReminderStage() != null && s.getReminderStage() <= stage) return false;
        // Con la cancelación pedida no se insiste con renovar: un solo aviso cerca del final.
        if (s.isCancelAtPeriodEnd() && stage == 7) return false;

        s.setReminderStage(stage);
        s.setReminderFor(s.getCurrentPeriodEnd());
        User owner = owner(companyId);
        if (owner == null) return true;
        String planName = PlanCatalog.info(s.getPlan()).name();
        String ownerEmail = owner.getEmail();
        String ownerName = owner.getFullName();
        if (s.isCancelAtPeriodEnd()) {
            String text = "Tu plan " + planName + " termina el " + day(s.getCurrentPeriodEnd())
                    + " y tu tienda pasa al plan Free. Si cambiaste de idea, reactivalo desde Plan y facturación.";
            CompletableFuture.runAsync(() -> email.sendBillingNotice(ownerEmail, ownerName,
                    "Tu plan termina en " + daysLeft + (daysLeft == 1 ? " día" : " días"), text));
        } else {
            String planCode = s.getPlan().name();
            CompletableFuture.runAsync(() -> email.sendPlanExpiringEmail(ownerEmail, ownerName, planCode, daysLeft));
        }
        return true;
    }

    // ─── Reglas ──────────────────────────────────────────────────────────────

    record Transition(Kind kind, Plan plan, int months, boolean queued, LocalDateTime periodStart, LocalDateTime periodEnd,
                      long creditSeconds) {}

    Transition transition(Subscription s, Plan target, int months, LocalDateTime now) {
        if (s == null || !s.isLive(now) || s.getPlan() == Plan.FREE) {
            return new Transition(Kind.NEW, target, months, false, now, now.plusMonths(months), 0);
        }
        if (s.hasPendingChange() && target == s.getNextPlan()) {
            return new Transition(Kind.RENEWAL, target, months, true, s.getNextPeriodEnd(), s.getNextPeriodEnd().plusMonths(months), 0);
        }
        if (target == s.getPlan()) {
            return new Transition(Kind.RENEWAL, target, months, false, s.getCurrentPeriodEnd(), s.getCurrentPeriodEnd().plusMonths(months), 0);
        }
        int targetRank = PlanCatalog.info(target).rank();
        int currentRank = PlanCatalog.info(s.getPlan()).rank();
        if (targetRank > currentRank) {
            long remaining = Math.max(0, Duration.between(now, s.getCurrentPeriodEnd()).toSeconds());
            long credit = 0;
            // Los días de prueba no se pagaron: no se convierten.
            if (s.getStatus() != Status.TRIALING) {
                BigDecimal ratio = PlanCatalog.info(s.getPlan()).monthlyPrice()
                        .divide(PlanCatalog.info(target).monthlyPrice(), 8, RoundingMode.DOWN);
                credit = BigDecimal.valueOf(remaining).multiply(ratio).longValue();
            }
            return new Transition(Kind.UPGRADE, target, months, false, now, now.plusMonths(months).plusSeconds(credit), credit);
        }
        LocalDateTime start = s.getCurrentPeriodEnd();
        return new Transition(Kind.DOWNGRADE, target, months, true, start, start.plusMonths(months), 0);
    }

    private Quote toQuote(Company company, Subscription s, Transition t) {
        Plan target = t.plan();
        PlanCatalog.PlanInfo info = PlanCatalog.info(target);
        int months = t.months();
        List<String> notes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        switch (t.kind()) {
            case NEW -> notes.add("Tu plan " + info.name() + " se activa apenas se confirme el pago y dura hasta el " + day(t.periodEnd()) + ".");
            case RENEWAL -> notes.add(t.queued()
                    ? "Se suman meses al plan " + info.name() + " que ya tenés programado: queda pagado hasta el " + day(t.periodEnd()) + "."
                    : "Se suman al final de tu periodo actual, sin perder días: tu plan " + info.name() + " queda pagado hasta el " + day(t.periodEnd()) + ".");
            case UPGRADE -> {
                notes.add("Pasás a " + info.name() + " apenas se confirme el pago, hasta el " + day(t.periodEnd()) + ".");
                if (t.creditSeconds() > 0) {
                    notes.add("Los días que te quedaban de " + PlanCatalog.info(s.getPlan()).name() + " se convierten en "
                            + round1(t.creditSeconds() / SECONDS_PER_DAY) + " días de " + info.name() + " (en proporción al precio).");
                } else if (s != null && s.getStatus() == Status.TRIALING) {
                    notes.add("Tu prueba gratuita termina al activar " + info.name() + ".");
                }
            }
            case DOWNGRADE -> {
                notes.add("Seguís con " + PlanCatalog.info(s.getPlan()).name() + " hasta el " + day(t.periodStart())
                        + ". Ese día empieza " + info.name() + ", pagado hasta el " + day(t.periodEnd()) + ".");
                warnings.addAll(downgradeWarnings(company, s.getPlan(), target));
            }
        }
        if (s != null && s.isLive(LocalDateTime.now()) && s.isCancelAtPeriodEnd()) {
            notes.add("Pagar deja sin efecto la cancelación que habías pedido.");
        }
        BigDecimal amount = info.monthlyPrice().multiply(BigDecimal.valueOf(months));
        return new Quote(t.kind().name(), target.name(), info.name(), months, amount, PlanCatalog.CURRENCY,
                at(t.periodStart()), at(t.periodEnd()), round1(t.creditSeconds() / SECONDS_PER_DAY), notes, warnings);
    }

    /** Qué deja de estar incluido al bajar de plan, y si el uso actual excede el nuevo límite. */
    List<String> downgradeWarnings(Company company, Plan from, Plan to) {
        List<String> warnings = new ArrayList<>();
        Set<Feature> lost = EnumSet.copyOf(PlanCatalog.info(from).features());
        lost.removeAll(PlanCatalog.info(to).features());
        if (!lost.isEmpty()) {
            warnings.add("Dejan de estar incluidos: " + String.join(", ", lost.stream().map(Feature::label).toList())
                    + ". La configuración se conserva por si volvés.");
        }
        int limit = PlanCatalog.info(to).productLimit();
        long current = products.countByCompany(company);
        if (limit != PlanCatalog.UNLIMITED && current > limit) {
            warnings.add("Tenés " + current + " productos y " + PlanCatalog.info(to).name() + " permite " + limit
                    + ". No se borra ninguno, pero no vas a poder crear productos nuevos hasta tener menos de " + limit + ".");
        }
        return warnings;
    }

    // ─── Apoyo ───────────────────────────────────────────────────────────────

    View view(Company company, Subscription s) {
        LocalDateTime now = LocalDateTime.now();
        boolean live = s != null && s.isLive(now) && s.getPlan() != Plan.FREE;
        Plan plan = live ? s.getPlan() : Plan.FREE;
        PlanCatalog.PlanInfo info = PlanCatalog.info(plan);
        PendingChange pending = live && s.hasPendingChange()
                ? new PendingChange(s.getNextPlan().name(), PlanCatalog.info(s.getNextPlan()).name(),
                at(s.getChangeEffectiveAt()), at(s.getNextPeriodEnd()))
                : null;
        long daysLeft = live ? (long) Math.ceil(Duration.between(now, s.paidUntil()).toHours() / 24d) : 0;
        LastSubscription last = !live && s != null && s.getPlan() != Plan.FREE && s.getCurrentPeriodEnd() != null
                ? new LastSubscription(s.getPlan().name(), PlanCatalog.info(s.getPlan()).name(), s.getStatus().name(),
                at(s.getCurrentPeriodEnd()))
                : null;
        return new View(plan.name(), info.name(),
                live ? s.getStatus().name() : "FREE",
                live ? s.getRenewal().name() : null,
                live && s.getStatus() == Status.TRIALING,
                live ? at(s.getCurrentPeriodStart()) : null,
                live ? at(s.getCurrentPeriodEnd()) : null,
                live ? at(s.paidUntil()) : null,
                daysLeft, info.monthlyPrice(), PlanCatalog.CURRENCY,
                live && s.isCancelAtPeriodEnd(),
                live ? at(s.getCancellationRequestedAt()) : null,
                live ? s.getCancellationReason() : null,
                pending,
                live && !s.isCancelAtPeriodEnd(),
                live && s.isCancelAtPeriodEnd(),
                !company.isTrialUsed() && !live,
                new Usage(products.countByCompany(company), info.productLimit()),
                last);
    }

    private Subscription lockOrCreate(Long companyId) {
        return subscriptions.findByCompanyIdForUpdate(companyId)
                .orElseGet(() -> subscriptions.saveAndFlush(new Subscription(companyId)));
    }

    /** Copia en Company para lecturas rápidas: plan actual y fin del último periodo pagado. */
    private static void syncCompany(Company company, Subscription s, LocalDateTime now) {
        if (s.isLive(now) && s.getPlan() != Plan.FREE) {
            company.setPlan(s.getPlan());
            company.setPlanExpiresAt(s.paidUntil());
        } else {
            company.setPlan(Plan.FREE);
            company.setPlanActivatedAt(null);
            company.setPlanExpiresAt(null);
        }
    }

    /** Pagar reactiva una tienda frenada por inactividad; una eliminación pedida por el dueño se respeta. */
    private static void reopenStore(Company company, LocalDateTime now) {
        if (company.getStatus() != Company.Status.ACTIVE && company.getStatus() != Company.Status.ANONYMIZED
                && !"OWNER_REQUEST".equals(company.getSuspensionReason())) {
            company.setStatus(Company.Status.ACTIVE);
            company.setSuspensionReason(null);
            company.setInactiveAt(null);
            company.setSuspendedAt(null);
            company.setArchivedAt(null);
            company.setDeletionScheduledAt(null);
        }
        company.setLastBusinessActivityAt(now);
    }

    private void notifyOwner(Long companyId, String title, String text) {
        User owner = owner(companyId);
        if (owner == null) return;
        String ownerEmail = owner.getEmail();
        String ownerName = owner.getFullName();
        CompletableFuture.runAsync(() -> email.sendBillingNotice(ownerEmail, ownerName, title, text));
    }

    private User owner(Long companyId) {
        return users.findFirstByCompanyIdAndRoleOrderByIdAsc(companyId, Role.BUSINESS_OWNER).orElse(null);
    }

    public static Plan parsePaidPlan(String raw) {
        try {
            Plan plan = Plan.valueOf(raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT));
            if (!PlanCatalog.info(plan).paid()) throw new IllegalArgumentException();
            return plan;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PLAN_INVALID", "Elegí el plan Pro o Business.");
        }
    }

    public static void validateMonths(int months) {
        if (months < 1 || months > MAX_MONTHS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MONTHS_INVALID", "La cantidad de meses debe estar entre 1 y 12.");
        }
    }

    public static String externalReference(Long companyId, Plan plan, int months) {
        return companyId + "|" + plan.name() + "|" + months;
    }

    record Reference(Long companyId, Plan plan, int months) {}

    static Reference parseReference(String externalReference) {
        if (externalReference == null) throw new IllegalArgumentException("Referencia de pago ausente");
        String[] parts = externalReference.split("\\|", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Referencia de pago inválida");
        try {
            Plan plan = Plan.valueOf(parts[1]);
            int months = Integer.parseInt(parts[2]);
            if (!PlanCatalog.info(plan).paid() || months < 1 || months > MAX_MONTHS) throw new IllegalArgumentException();
            return new Reference(Long.valueOf(parts[0]), plan, months);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Referencia de pago inválida", e);
        }
    }

    private static String name(Plan plan) {
        return plan == null ? null : plan.name();
    }

    private static OffsetDateTime at(LocalDateTime time) {
        return time == null ? null : BusinessClock.withOffset(time);
    }

    private static String day(LocalDateTime time) {
        return BusinessClock.withOffset(time).format(DAY);
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10d;
    }
}
