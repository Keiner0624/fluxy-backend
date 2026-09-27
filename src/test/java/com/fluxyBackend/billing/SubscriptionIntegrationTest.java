package com.fluxyBackend.billing;

import com.fluxyBackend.billing.PaymentProvider.ProviderPayment;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.RateLimitService;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Suscripciones: pagos, cambios de plan, cancelación al fin del periodo, fin de periodo y acceso por plan. */
@SpringBootTest
@AutoConfigureMockMvc
class SubscriptionIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private SessionService sessions;
    @Autowired private RateLimitService rateLimits;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subscriptions;
    @Autowired private BillingPaymentRepository billingPayments;
    @Autowired private SubscriptionEventRepository events;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ProductRepository productRepository;
    @MockitoBean private PaymentProvider paymentProvider;

    @BeforeEach
    void setUp() {
        rateLimits.reset();
        when(paymentProvider.name()).thenReturn("TEST");
        when(paymentProvider.createCheckout(any())).thenReturn(
                new PaymentProvider.CheckoutResult("pref-1", "https://pagos.test/checkout", null));
    }

    // ─── Pagos ───────────────────────────────────────────────────────────────

    @Test
    void elCheckoutNoActivaElPlanSoloElPagoConfirmado() throws Exception {
        Tenant t = tenant();
        mvc.perform(post("/billing/subscription/checkout").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\",\"months\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value("https://pagos.test/checkout"))
                .andExpect(jsonPath("$.quote.kind").value("NEW"))
                .andExpect(jsonPath("$.quote.amount").value(39.00));
        assertThat(plan(t)).isEqualTo(Plan.FREE);

        pay(t, "PRO", 1);
        assertThat(plan(t)).isEqualTo(Plan.PRO);
        mvc.perform(get("/billing/subscription").header("Authorization", t.token()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.renewal").value("MANUAL"))
                .andExpect(jsonPath("$.canCancel").value(true));
    }

    @Test
    void alVolverDeMercadoPagoElPlanSeActivaSinEsperarElWebhook() throws Exception {
        Tenant t = tenant();
        String id = String.valueOf(System.nanoTime() % 1_000_000_000L);
        when(paymentProvider.fetchPayment(id)).thenReturn(approved(id, t, "BUSINESS", 1));

        mvc.perform(post("/billing/subscription/confirm").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"" + id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.subscription.plan").value("BUSINESS"));
        assertThat(plan(t)).isEqualTo(Plan.BUSINESS);

        // El webhook (o una segunda vuelta) llega después y no suma nada.
        mvc.perform(post("/billing/subscription/confirm").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"" + id + "\"}"))
                .andExpect(jsonPath("$.status").value("ALREADY_APPLIED"));
        when(paymentProvider.verifyWebhook(any(), any(), any())).thenReturn(true);
        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payment\",\"data\":{\"id\":\"" + id + "\"}}"))
                .andExpect(status().isOk());
        assertThat(billingPayments.findByCompanyIdOrderByPaidAtDescIdDesc(t.company().getId(),
                org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(1);
    }

    @Test
    void laVueltaSoloConfirmaPagosPropiosYRespetaElEstado() throws Exception {
        Tenant t = tenant();
        Tenant other = tenant();
        String foreign = "71" + (System.nanoTime() % 1_000_000L);
        when(paymentProvider.fetchPayment(foreign)).thenReturn(approved(foreign, other, "PRO", 1));
        mvc.perform(post("/billing/subscription/confirm").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"" + foreign + "\"}"))
                .andExpect(status().isNotFound());
        assertThat(plan(other)).isEqualTo(Plan.FREE);

        String pending = "72" + (System.nanoTime() % 1_000_000L);
        when(paymentProvider.fetchPayment(pending)).thenReturn(
                new ProviderPayment(pending, "in_process", t.company().getId() + "|PRO|1", new BigDecimal("39.00"), "PEN"));
        mvc.perform(post("/billing/subscription/confirm").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"" + pending + "\"}"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(plan(t)).isEqualTo(Plan.FREE);

        mvc.perform(post("/billing/subscription/confirm").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unPagoRepetidoNoSumaMesesDosVeces() {
        Tenant t = tenant();
        String id = "pay-" + uid();
        subscriptionService.applyPayment(approved(id, t, "PRO", 1));
        LocalDateTime end = sub(t).getCurrentPeriodEnd();

        assertThat(subscriptionService.applyPayment(approved(id, t, "PRO", 1))).isEmpty();
        assertThat(sub(t).getCurrentPeriodEnd()).isEqualTo(end);
        assertThat(billingPayments.findByCompanyIdOrderByPaidAtDescIdDesc(t.company().getId(), org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(1);
    }

    @Test
    void unMontoManipuladoSeRechaza() {
        Tenant t = tenant();
        ProviderPayment cheap = new ProviderPayment("pay-" + uid(), "approved",
                t.company().getId() + "|BUSINESS|1", new BigDecimal("0.01"), "PEN");
        assertThatThrownBy(() -> subscriptionService.applyPayment(cheap)).isInstanceOf(IllegalArgumentException.class);
        assertThat(plan(t)).isEqualTo(Plan.FREE);
    }

    @Test
    void renovarSumaAlFinalSinPerderDias() {
        Tenant t = tenant();
        pay(t, "PRO", 1);
        LocalDateTime end = sub(t).getCurrentPeriodEnd();
        pay(t, "PRO", 2);
        assertThat(sub(t).getCurrentPeriodEnd()).isEqualTo(end.plusMonths(2));
    }

    @Test
    void subirDePlanEsInmediatoYConvierteLosDiasRestantes() {
        Tenant t = tenant();
        pay(t, "PRO", 1);
        LocalDateTime before = LocalDateTime.now();
        pay(t, "BUSINESS", 1);

        Subscription s = sub(t);
        assertThat(s.getPlan()).isEqualTo(Plan.BUSINESS);
        // ~30 días de Pro (39) valen ~19.8 días de Business (59): el periodo supera el mes pagado.
        long extraDays = Duration.between(before.plusMonths(1), s.getCurrentPeriodEnd()).toDays();
        assertThat(extraDays).isBetween(18L, 21L);
        assertThat(plan(t)).isEqualTo(Plan.BUSINESS);
    }

    @Test
    void bajarDePlanQuedaProgramadoYElFinDePeriodoLoAplicaUnaSolaVez() {
        Tenant t = tenant();
        pay(t, "BUSINESS", 1);
        pay(t, "PRO", 1);

        Subscription s = sub(t);
        assertThat(s.getPlan()).isEqualTo(Plan.BUSINESS);
        assertThat(s.getNextPlan()).isEqualTo(Plan.PRO);
        assertThat(s.getChangeEffectiveAt()).isEqualTo(s.getCurrentPeriodEnd());
        assertThat(plan(t)).isEqualTo(Plan.BUSINESS);

        endCurrentPeriod(t);
        assertThat(subscriptionService.applyDueTransitions()).isGreaterThanOrEqualTo(1);
        s = sub(t);
        assertThat(s.getPlan()).isEqualTo(Plan.PRO);
        assertThat(s.getNextPlan()).isNull();
        assertThat(plan(t)).isEqualTo(Plan.PRO);

        // Otra pasada no vuelve a aplicarlo.
        subscriptionService.applyDueTransitions();
        assertThat(events.countByCompanyIdAndType(t.company().getId(), SubscriptionEvent.Type.DOWNGRADE_APPLIED)).isEqualTo(1);
    }

    @Test
    void bajarConMasProductosQueElLimiteAvisaYNoBorraNada() throws Exception {
        Tenant t = tenant();
        pay(t, "BUSINESS", 1);
        for (int i = 0; i < 101; i++) {
            productRepository.save(Prodcut.builder().name("P" + i).price(1).stock(1).owner(t.owner()).company(t.company()).build());
        }
        mvc.perform(get("/billing/subscription/quote").param("plan", "PRO").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DOWNGRADE"))
                .andExpect(jsonPath("$.warnings[1]").value(org.hamcrest.Matchers.containsString("101 productos")));
        assertThat(productRepository.countByCompany(t.company())).isEqualTo(101);
    }

    // ─── Cancelar y reactivar ────────────────────────────────────────────────

    @Test
    void cancelarMantieneElPlanHastaElFinDelPeriodoYSeRepiteSinEfectos() throws Exception {
        Tenant t = tenant();
        pay(t, "PRO", 1);
        LocalDateTime end = sub(t).getCurrentPeriodEnd();

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/billing/subscription/cancel").header("Authorization", t.token())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"TOO_EXPENSIVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.plan").value("PRO"))
                    .andExpect(jsonPath("$.cancelAtPeriodEnd").value(true))
                    .andExpect(jsonPath("$.canReactivate").value(true));
        }
        assertThat(events.countByCompanyIdAndType(t.company().getId(), SubscriptionEvent.Type.CANCELLATION_REQUESTED)).isEqualTo(1);
        assertThat(sub(t).getCurrentPeriodEnd()).isEqualTo(end);
        assertThat(plan(t)).isEqualTo(Plan.PRO);

        endCurrentPeriod(t);
        subscriptionService.applyDueTransitions();
        assertThat(sub(t).getStatus()).isEqualTo(Subscription.Status.CANCELED);
        assertThat(plan(t)).isEqualTo(Plan.FREE);
    }

    @Test
    void reactivarAntesDelFinDeshaceLaCancelacionSinCrearOtraSuscripcion() throws Exception {
        Tenant t = tenant();
        pay(t, "PRO", 1);
        Long id = sub(t).getId();
        mvc.perform(post("/billing/subscription/cancel").header("Authorization", t.token())).andExpect(status().isOk());
        mvc.perform(post("/billing/subscription/reactivate").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false))
                .andExpect(jsonPath("$.canCancel").value(true));
        assertThat(sub(t).getId()).isEqualTo(id);
        assertThat(subscriptions.findAll().stream().filter(s -> s.getCompanyId().equals(t.company().getId())).count()).isEqualTo(1);
    }

    @Test
    void sinPlanPagoNoHayNadaQueCancelar() throws Exception {
        Tenant t = tenant();
        mvc.perform(post("/billing/subscription/cancel").header("Authorization", t.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_SUBSCRIPTION"));
    }

    @Test
    void soloElDuenoGestionaLaFacturacionYSoloLaDeSuNegocio() throws Exception {
        Tenant a = tenant();
        Tenant b = tenant();
        pay(a, "PRO", 1);
        pay(b, "PRO", 1);

        String admin = TestAuth.bearer(sessions, member(a, "ADMIN"));
        mvc.perform(post("/billing/subscription/cancel").header("Authorization", admin)).andExpect(status().isForbidden());
        mvc.perform(get("/billing/subscription").header("Authorization", admin)).andExpect(status().isForbidden());

        mvc.perform(post("/billing/subscription/cancel").header("Authorization", b.token())).andExpect(status().isOk());
        assertThat(sub(a).isCancelAtPeriodEnd()).isFalse();
        assertThat(sub(b).isCancelAtPeriodEnd()).isTrue();
    }

    // ─── Vencimiento y acceso ────────────────────────────────────────────────

    @Test
    void alVencerSinRenovarPasaAFreeAunqueLaTareaNoHayaCorrido() throws Exception {
        Tenant t = tenant();
        pay(t, "PRO", 1);
        mvc.perform(get("/dashboard/metrics").header("Authorization", t.token())).andExpect(status().isOk());

        endCurrentPeriod(t);
        // Sin esperar a la tarea: el acceso ya es de Free.
        mvc.perform(get("/dashboard/metrics").header("Authorization", t.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
        mvc.perform(get("/me").header("Authorization", t.token())).andExpect(jsonPath("$.planName").value("FREE"));

        subscriptionService.applyDueTransitions();
        assertThat(sub(t).getStatus()).isEqualTo(Subscription.Status.EXPIRED);
        assertThat(companyRepository.findById(t.company().getId()).orElseThrow().getPlan()).isEqualTo(Plan.FREE);
    }

    @Test
    void lasFuncionesPagasSeValidanEnElServidor() throws Exception {
        Tenant t = tenant();
        mvc.perform(get("/reports/summary").header("Authorization", t.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
        mvc.perform(put("/companies/config").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeStyle\":\"{\\\"primary\\\":\\\"#000000\\\"}\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));

        pay(t, "PRO", 1);
        mvc.perform(get("/reports/summary").header("Authorization", t.token())).andExpect(status().isOk());
        mvc.perform(put("/companies/config").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeStyle\":\"{\\\"primary\\\":\\\"#000000\\\"}\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void laPruebaGratuitaEsUnaSolaVezYVenceComoCualquierPlan() throws Exception {
        Tenant t = tenant();
        mvc.perform(post("/billing/subscription/trial").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIALING"))
                .andExpect(jsonPath("$.trial").value(true));
        endCurrentPeriod(t);
        subscriptionService.applyDueTransitions();
        mvc.perform(post("/billing/subscription/trial").header("Authorization", t.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TRIAL_USED"));
    }

    @Test
    void losContadoresDelMenuRespetanPermisos() throws Exception {
        Tenant t = tenant();
        JsonNode owner = json.readTree(mvc.perform(get("/dashboard/badges").param("customersSince", "0")
                .header("Authorization", t.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(owner.has("orders")).isTrue();
        assertThat(owner.has("customers")).isTrue();
        assertThat(owner.has("billing")).isTrue();

        String warehouse = TestAuth.bearer(sessions, member(t, "WAREHOUSE"));
        JsonNode limited = json.readTree(mvc.perform(get("/dashboard/badges").header("Authorization", warehouse))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(limited.has("inventory")).isTrue();
        assertThat(limited.has("billing")).isFalse();
        assertThat(limited.has("payments")).isFalse();
    }

    // ─── Apoyo ───────────────────────────────────────────────────────────────

    private record Tenant(Company company, User owner, String token) {}

    private Tenant tenant() {
        String id = uid();
        Company company = Company.builder().name("Tienda " + id).slug("sub-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company = companyRepository.save(company);
        User owner = User.builder().fullName("Dueño " + id).email("sub-" + id + "@fluxy.invalid")
                .password("x").role(Role.BUSINESS_OWNER).company(company).build();
        owner.setStatus(User.Status.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = userRepository.save(owner);
        membershipRepository.save(new Membership(owner.getId(), company.getId(), "OWNER"));
        return new Tenant(company, owner, TestAuth.bearer(sessions, owner));
    }

    private User member(Tenant t, String role) {
        String id = uid();
        User user = User.builder().fullName(role + " " + id).email(role.toLowerCase() + "-" + id + "@fluxy.invalid")
                .password("x").role(Role.TEAM_MEMBER).company(t.company()).build();
        user.setStatus(User.Status.ACTIVE);
        user = userRepository.save(user);
        membershipRepository.save(new Membership(user.getId(), t.company().getId(), role));
        return user;
    }

    private void pay(Tenant t, String plan, int months) {
        assertThat(subscriptionService.applyPayment(approved("pay-" + uid(), t, plan, months))).isPresent();
    }

    private ProviderPayment approved(String id, Tenant t, String plan, int months) {
        BigDecimal amount = PlanCatalog.info(Plan.valueOf(plan)).monthlyPrice().multiply(BigDecimal.valueOf(months));
        return new ProviderPayment(id, "approved", t.company().getId() + "|" + plan + "|" + months, amount, "PEN");
    }

    /** Simula que pasó el tiempo: el periodo actual (y la copia en Company) terminan ahora. */
    private void endCurrentPeriod(Tenant t) {
        Subscription s = sub(t);
        Duration shift = Duration.between(LocalDateTime.now().minusSeconds(1), s.getCurrentPeriodEnd());
        s.setCurrentPeriodEnd(s.getCurrentPeriodEnd().minus(shift));
        if (s.hasPendingChange()) {
            s.setChangeEffectiveAt(s.getCurrentPeriodEnd());
        }
        subscriptions.save(s);
        Company company = companyRepository.findById(t.company().getId()).orElseThrow();
        company.setPlanExpiresAt(s.paidUntil());
        companyRepository.save(company);
    }

    private Subscription sub(Tenant t) {
        return subscriptions.findByCompanyId(t.company().getId()).orElseThrow();
    }

    private Plan plan(Tenant t) {
        return PlanCatalog.effectivePlan(companyRepository.findById(t.company().getId()).orElseThrow());
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8) + SEQ.incrementAndGet();
    }
}
