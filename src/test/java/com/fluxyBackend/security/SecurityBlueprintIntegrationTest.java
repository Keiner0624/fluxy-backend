package com.fluxyBackend.security;

import com.fluxyBackend.controller.AuthResponse;
import com.fluxyBackend.entity.*;
import com.fluxyBackend.entity.VerificationChallenge.Type;
import com.fluxyBackend.repository.*;
import com.fluxyBackend.service.CompanyLifecycleService;
import com.fluxyBackend.service.VerificationService;
import com.fluxyBackend.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas del documento de seguridad: registro verificado, códigos, sesiones,
 * aislamiento entre negocios, roles, límites, asignación masiva, idempotencia,
 * ciclo de vida y acciones sensibles.
 *
 * Sin @Transactional: varias piezas usan transacciones propias y bloqueos,
 * como en producción. Cada prueba crea datos con identificadores únicos.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityBlueprintIntegrationTest {

    private static final String PASSWORD = "Clave-Segura-2026";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private BCryptPasswordEncoder encoder;
    @Autowired private RateLimitService rateLimits;
    @Autowired private SessionService sessions;
    @Autowired private VerificationService verification;
    @Autowired private CompanyLifecycleService lifecycle;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private VerificationChallengeRepository challengeRepository;
    @Autowired private PasswordResetTokenRepository resetTokenRepository;

    @BeforeEach
    void resetLimits() {
        rateLimits.reset();
    }

    // ─── Registro y códigos ──────────────────────────────────────────────────

    @Test
    void elRegistroCreaLaTiendaRecienAlVerificarElCorreo() throws Exception {
        String email = "nuevo-" + uid() + "@fluxy.invalid";
        JsonNode state = body(mvc.perform(post("/auth/signup").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andReturn());
        String signupToken = state.path("signupToken").asString();
        assertThat(state.path("pending").toString()).contains("EMAIL");

        User pending = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(User.Status.PENDING_VERIFICATION);
        assertThat(pending.getCompany()).isNull();

        mvc.perform(post("/auth/login").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_REQUIRED"));

        String code = verification.capturedCode(Type.EMAIL, email);
        mvc.perform(verify(signupToken, wrong(code))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));

        mvc.perform(verify(signupToken, code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.session.token").isNotEmpty())
                .andExpect(jsonPath("$.session.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.company.slug").isNotEmpty());

        User active = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(active.getStatus()).isEqualTo(User.Status.ACTIVE);
        assertThat(active.getCompany()).isNotNull();
        assertThat(active.getEmailVerifiedAt()).isNotNull();

        mvc.perform(post("/auth/login").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void elCodigoSeBloqueaTrasCincoIntentosYNoSeReenviaAntesDeUnMinuto() throws Exception {
        String email = "intentos-" + uid() + "@fluxy.invalid";
        String signupToken = startSignup(email);
        String code = verification.capturedCode(Type.EMAIL, email);

        for (int i = 0; i < 4; i++) {
            mvc.perform(verify(signupToken, wrong(code))).andExpect(status().isBadRequest());
        }
        mvc.perform(verify(signupToken, wrong(code)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CODE_LOCKED"));
        // Ni el código correcto sirve una vez bloqueado.
        mvc.perform(verify(signupToken, code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_NOT_FOUND"));

        mvc.perform(post("/auth/signup/resend").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("signupToken", signupToken, "channel", "EMAIL"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void unCodigoVencidoNoSirve() throws Exception {
        String email = "vencido-" + uid() + "@fluxy.invalid";
        String signupToken = startSignup(email);
        String code = verification.capturedCode(Type.EMAIL, email);
        Long userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        challengeRepository.findAll().stream().filter(c -> userId.equals(c.getUserId())).forEach(c -> {
            c.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            challengeRepository.save(c);
        });

        mvc.perform(verify(signupToken, code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
    }

    // ─── Sesiones ─────────────────────────────────────────────────────────────

    @Test
    void elRefreshRotaYReutilizarUnoViejoCierraLaSesion() throws Exception {
        Tenant t = tenant();
        JsonNode first = login(t.owner().getEmail(), PASSWORD);
        String r1 = first.path("refreshToken").asString();

        JsonNode second = body(mvc.perform(refresh(r1)).andExpect(status().isOk()).andReturn());
        String r2 = second.path("refreshToken").asString();
        String t2 = second.path("token").asString();
        assertThat(r2).isNotEqualTo(r1);

        // Dentro de la gracia (otra pestaña): no se revoca.
        mvc.perform(refresh(r1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFRESH_ROTATED"));

        UserSession session = sessionRepository.findById(first.path("sessionId").asString()).orElseThrow();
        session.setRotatedAt(LocalDateTime.now().minusMinutes(5));
        sessionRepository.save(session);

        mvc.perform(refresh(r1)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        mvc.perform(get("/me").header("Authorization", "Bearer " + t2)).andExpect(status().isUnauthorized());
        mvc.perform(refresh(r2)).andExpect(status().isUnauthorized());

        assertThat(auditCount(t.company().getId(), "REFRESH_REUSE_DETECTED")).isEqualTo(1);
    }

    @Test
    void cerrarSesionYCerrarLasDemasCortaElAccesoAlInstante() throws Exception {
        Tenant t = tenant();
        String a = TestAuth.bearer(sessions, t.owner());
        String b = TestAuth.bearer(sessions, t.owner());

        mvc.perform(get("/me/sessions").header("Authorization", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.current == true)]").isNotEmpty());

        mvc.perform(post("/me/sessions/revoke-others").header("Authorization", a)).andExpect(status().isOk());
        mvc.perform(get("/me").header("Authorization", b)).andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", a)).andExpect(status().isOk());

        mvc.perform(post("/me/logout").header("Authorization", a)).andExpect(status().isOk());
        mvc.perform(get("/me").header("Authorization", a)).andExpect(status().isUnauthorized());
    }

    @Test
    void unTokenInvalidoOAjenoRecibe401() throws Exception {
        mvc.perform(get("/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        String forged = io.jsonwebtoken.Jwts.builder().subject("x@fluxy.invalid").claim("uid", 1).claim("sid", "s")
                .claim("typ", "access").signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(new byte[32])).compact();
        mvc.perform(get("/me").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
    }

    // ─── Aislamiento entre negocios ──────────────────────────────────────────

    @Test
    void unNegocioNoPuedeLeerNiModificarDatosDeOtro() throws Exception {
        Tenant a = tenant();
        Tenant b = tenant();
        JsonNode order = body(mvc.perform(storeOrder(b, "orden-ajena").with(ip()))
                .andExpect(status().isOk()).andReturn());
        long orderB = order.path("orderId").asLong();
        Long customerB = jdbc.queryForObject("SELECT customer_id FROM orders WHERE id = ?", Long.class, orderB);

        mvc.perform(get("/orders/" + orderB + "/detail").header("Authorization", a.token())).andExpect(status().isNotFound());
        mvc.perform(get("/customers/" + customerB).header("Authorization", a.token())).andExpect(status().isNotFound());
        mvc.perform(put("/products/" + b.product().getId()).header("Authorization", a.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hackeado\",\"price\":1,\"stock\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/products/" + b.product().getId()).header("Authorization", a.token()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/inventory/products/" + b.product().getId() + "/movements").header("Authorization", a.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"ENTRY\",\"quantity\":50,\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/orders/search").header("Authorization", a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        Prodcut untouched = productRepository.findById(b.product().getId()).orElseThrow();
        assertThat(untouched.getName()).isEqualTo("Café");
        assertThat(untouched.getStock()).isEqualTo(19);
    }

    @Test
    void laActividadDeUnNegocioNoApareceEnOtro() throws Exception {
        Tenant a = tenant();
        Tenant b = tenant();
        login(a.owner().getEmail(), PASSWORD);

        long ownEntries = auditCount(b.company().getId(), null);
        mvc.perform(get("/audit").header("Authorization", b.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(ownEntries));
        assertThat(auditCount(a.company().getId(), "LOGIN_SUCCESS")).isEqualTo(1);
    }

    // ─── Roles ────────────────────────────────────────────────────────────────

    @Test
    void losRolesSeAplicanEnElServidor() throws Exception {
        Tenant t = tenant();
        String warehouse = TestAuth.bearer(sessions, member(t, "WAREHOUSE"));
        String viewer = TestAuth.bearer(sessions, member(t, "VIEWER"));
        String manager = TestAuth.bearer(sessions, member(t, "MANAGER"));
        String seller = TestAuth.bearer(sessions, member(t, "SELLER"));
        String admin = TestAuth.bearer(sessions, member(t, "ADMIN"));

        mvc.perform(post("/inventory/products/" + t.product().getId() + "/movements").header("Authorization", warehouse)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"ENTRY\",\"quantity\":5,\"reason\":\"Compra\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/products").header("Authorization", warehouse)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"price\":1,\"stock\":0}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/products/search").header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(post("/products").header("Authorization", viewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"price\":1,\"stock\":0}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/team/invitations").header("Authorization", manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@fluxy.invalid\",\"role\":\"SELLER\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/audit").header("Authorization", manager)).andExpect(status().isForbidden());
        mvc.perform(get("/audit").header("Authorization", admin)).andExpect(status().isOk());

        mvc.perform(post("/payments/999999/refund").header("Authorization", seller)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MISSING_PERMISSION"));

        // Nadie modifica al dueño: para cambiarlo existe la transferencia.
        mvc.perform(patch("/team/members/" + t.owner().getId()).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(membershipRepository.findByUserIdAndCompanyId(t.owner().getId(), t.company().getId())
                .orElseThrow().isActive()).isTrue();
    }

    @Test
    void desactivarAUnIntegranteCierraSusSesiones() throws Exception {
        Tenant t = tenant();
        User seller = member(t, "SELLER");
        String sellerToken = TestAuth.bearer(sessions, seller);
        mvc.perform(get("/me").header("Authorization", sellerToken)).andExpect(status().isOk());

        mvc.perform(patch("/team/members/" + seller.getId()).header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/me").header("Authorization", sellerToken)).andExpect(status().is4xxClientError());
    }

    // ─── Límites, tamaño y asignación masiva ─────────────────────────────────

    @Test
    void laRecuperacionDeContrasenaTieneLimitePorIp() throws Exception {
        RequestPostProcessor sameIp = r -> { r.setRemoteAddr("203.0.113.77"); return r; };
        int status = 200;
        for (int i = 0; i < 12 && status == 200; i++) {
            status = mvc.perform(post("/auth/forgot-password").with(sameIp).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"nadie-" + i + "@fluxy.invalid\"}")).andReturn().getResponse().getStatus();
        }
        assertThat(status).isEqualTo(429);
        mvc.perform(post("/auth/forgot-password").with(sameIp).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"otra@fluxy.invalid\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void unaPeticionDemasiadoGrandeSeRechaza() throws Exception {
        String huge = "{\"email\":\"a@b.co\",\"password\":\"" + "x".repeat(1_100_000) + "\"}";
        mvc.perform(post("/auth/login").with(ip()).contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void losCamposProtegidosNoSeTomanDelCliente() throws Exception {
        Tenant a = tenant();
        Tenant b = tenant();
        String originalSlug = a.company().getSlug();

        mvc.perform(put("/companies/config").header("Authorization", a.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nombre nuevo\",\"plan\":\"BUSINESS\",\"slug\":\"robado\",\"status\":\"ACTIVE\","
                                + "\"planExpiresAt\":\"2099-01-01T00:00:00\",\"id\":" + b.company().getId() + "}"))
                .andExpect(status().isOk());
        Company company = companyRepository.findById(a.company().getId()).orElseThrow();
        assertThat(company.getName()).isEqualTo("Nombre nuevo");
        assertThat(company.getPlan()).isEqualTo(Company.Plan.FREE);
        assertThat(company.getSlug()).isEqualTo(originalSlug);
        assertThat(company.getPlanExpiresAt()).isNull();
        assertThat(companyRepository.findById(b.company().getId()).orElseThrow().getName()).isNotEqualTo("Nombre nuevo");

        JsonNode created = body(mvc.perform(post("/products").header("Authorization", a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Propio\",\"price\":5,\"stock\":0,\"company\":{\"id\":" + b.company().getId() + "},"
                                + "\"owner\":{\"id\":" + b.owner().getId() + "}}"))
                .andExpect(status().isOk()).andReturn());
        Long companyOfProduct = jdbc.queryForObject("SELECT company_id FROM products WHERE id = ?", Long.class,
                created.path("id").asLong());
        assertThat(companyOfProduct).isEqualTo(a.company().getId());
    }

    // ─── Idempotencia ─────────────────────────────────────────────────────────

    @Test
    void repetirUnPedidoConLaMismaClaveNoLoDuplica() throws Exception {
        Tenant t = tenant();
        RequestPostProcessor sameIp = r -> { r.setRemoteAddr("198.18.0.9"); return r; };
        String key = "pedido-" + uid();

        long first = body(mvc.perform(storeOrder(t, key).with(sameIp)).andExpect(status().isOk()).andReturn())
                .path("orderId").asLong();
        mvc.perform(storeOrder(t, key).with(sameIp))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.orderId").value(first));

        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE company_id = ?", Integer.class, t.company().getId());
        assertThat(orders).isEqualTo(1);

        mvc.perform(post("/store/slug/" + t.company().getSlug() + "/order").with(sameIp).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(t, 2)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    // ─── Ciclo de vida ────────────────────────────────────────────────────────

    @Test
    void unNegocioFreeInactivoAvanzaYSeReactiva() throws Exception {
        Tenant t = tenant();
        update(t.company(), c -> c.setLastBusinessActivityAt(LocalDateTime.now().minusDays(31)));
        assertThat(evaluate(t.company())).isTrue();
        assertThat(companyStatus(t.company())).isEqualTo(Company.Status.INACTIVE);

        update(t.company(), c -> {
            c.setLastBusinessActivityAt(LocalDateTime.now().minusDays(61));
            c.setInactiveAt(LocalDateTime.now().minusDays(8));
        });
        assertThat(evaluate(t.company())).isTrue();
        assertThat(companyStatus(t.company())).isEqualTo(Company.Status.SUSPENDED);

        mvc.perform(storeOrder(t, null).with(ip()))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("STORE_SUSPENDED"));
        mvc.perform(get("/store/slug/" + t.company().getSlug() + "/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingOrders").value(false));

        mvc.perform(post("/company-account/lifecycle/reactivate").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(storeOrder(t, null).with(ip())).andExpect(status().isOk());
    }

    @Test
    void unPlanPagoNoSeSuspendePorInactividad() {
        Tenant t = tenant();
        update(t.company(), c -> {
            c.setPlan(Company.Plan.PRO);
            c.setPlanExpiresAt(LocalDateTime.now().plusDays(20));
            c.setLastBusinessActivityAt(LocalDateTime.now().minusDays(200));
        });
        assertThat(evaluate(t.company())).isFalse();
        assertThat(companyStatus(t.company())).isEqualTo(Company.Status.ACTIVE);
    }

    @Test
    void unaTiendaArchivadaSaleDeLineaYNoAceptaCambios() throws Exception {
        Tenant t = tenant();
        update(t.company(), c -> c.setStatus(Company.Status.ARCHIVED));

        mvc.perform(get("/store/slug/" + t.company().getSlug() + "/info"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("STORE_OFFLINE"));
        mvc.perform(post("/products").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"price\":1,\"stock\":0}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("COMPANY_ARCHIVED"));
    }

    @Test
    void laActividadRealReactivaUnNegocioInactivo() throws Exception {
        Tenant t = tenant();
        update(t.company(), c -> {
            c.setStatus(Company.Status.INACTIVE);
            c.setLastBusinessActivityAt(LocalDateTime.now().minusDays(40));
        });
        mvc.perform(post("/products").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nuevo\",\"price\":2,\"stock\":0}"))
                .andExpect(status().isOk());
        assertThat(companyStatus(t.company())).isEqualTo(Company.Status.ACTIVE);
    }

    // ─── Acciones sensibles ──────────────────────────────────────────────────

    @Test
    void eliminarElNegocioExigeIdentidadRecienteYConfirmacion() throws Exception {
        Tenant t = tenant();
        AuthResponse session = TestAuth.session(sessions, t.owner());
        String bearer = "Bearer " + session.token;
        UserSession stored = sessionRepository.findById(session.sessionId).orElseThrow();
        stored.setAuthenticatedAt(LocalDateTime.now().minusHours(1));
        sessionRepository.save(stored);
        String name = t.company().getName();

        mvc.perform(post("/company-account/deletion").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("confirmation", name))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));

        mvc.perform(post("/me/reauth").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"no-es-la-clave-1\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/me/reauth").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isOk());

        mvc.perform(post("/company-account/deletion").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"otro negocio\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_MISMATCH"));

        String admin = TestAuth.bearer(sessions, member(t, "ADMIN"));
        mvc.perform(post("/company-account/deletion").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("confirmation", name))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_ONLY"));

        mvc.perform(post("/company-account/deletion").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("confirmation", name))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETION_PENDING"));
        mvc.perform(get("/store/slug/" + t.company().getSlug() + "/info")).andExpect(status().isGone());

        mvc.perform(delete("/company-account/deletion").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void elEnlaceDeRecuperacionEsDeUnSoloUsoYCierraLasSesiones() throws Exception {
        Tenant t = tenant();
        String raw = "reset-" + uid() + "-token-de-prueba";
        resetTokenRepository.save(PasswordResetToken.builder().token(Hashing.sha256(raw)).user(t.owner())
                .expiresAt(LocalDateTime.now().plusMinutes(30)).used(false).build());
        String newPassword = "Nueva-Clave-Segura-9";

        mvc.perform(get("/me").header("Authorization", t.token())).andExpect(status().isOk());
        mvc.perform(post("/auth/reset-password").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", raw, "password", newPassword))))
                .andExpect(status().isOk());

        mvc.perform(get("/me").header("Authorization", t.token())).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/reset-password").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", raw, "password", "Otra-Clave-Segura-7"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));

        login(t.owner().getEmail(), newPassword);
        mvc.perform(post("/auth/login").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", t.owner().getEmail(), "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pedirRecuperacionRespondeIgualExistaONoLaCuenta() throws Exception {
        Tenant t = tenant();
        String known = mvc.perform(post("/auth/forgot-password").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", t.owner().getEmail()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(post("/auth/forgot-password").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-existe-" + uid() + "@fluxy.invalid\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(known).isEqualTo(unknown);
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private record Tenant(Company company, User owner, Prodcut product, String token) {}

    private Tenant tenant() {
        String id = uid();
        Company company = Company.builder().name("Tienda " + id).slug("t-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company = companyRepository.save(company);
        User owner = User.builder().fullName("Dueño " + id).email("owner-" + id + "@fluxy.invalid")
                .password(encoder.encode(PASSWORD)).role(Role.BUSINESS_OWNER).company(company).build();
        owner.setStatus(User.Status.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = userRepository.save(owner);
        membershipRepository.save(new Membership(owner.getId(), company.getId(), "OWNER"));
        Prodcut product = productRepository.save(Prodcut.builder().name("Café").price(10).stock(20)
                .owner(owner).company(company).build());
        return new Tenant(company, owner, product, TestAuth.bearer(sessions, owner));
    }

    private User member(Tenant t, String role) {
        String id = uid();
        User user = User.builder().fullName(role + " " + id).email(role.toLowerCase() + "-" + id + "@fluxy.invalid")
                .password(encoder.encode(PASSWORD)).role(Role.TEAM_MEMBER).company(t.company()).build();
        user.setStatus(User.Status.ACTIVE);
        user = userRepository.save(user);
        membershipRepository.save(new Membership(user.getId(), t.company().getId(), role));
        return user;
    }

    private JsonNode login(String email, String password) throws Exception {
        return body(mvc.perform(post("/auth/login").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password, "rememberMe", true))))
                .andExpect(status().isOk()).andReturn());
    }

    private String startSignup(String email) throws Exception {
        return body(mvc.perform(post("/auth/signup").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(email)))
                .andExpect(status().isOk()).andReturn()).path("signupToken").asString();
    }

    private String signupBody(String email) throws Exception {
        return json.writeValueAsString(Map.of("fullName", "Ana Prueba", "businessName", "Bodega " + uid(),
                "category", "FOOD", "whatsapp", "987654321", "email", email, "password", PASSWORD, "termsAccepted", true));
    }

    private MockHttpServletRequestBuilder verify(String signupToken, String code) throws Exception {
        return post("/auth/signup/verify").with(ip()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("signupToken", signupToken, "channel", "EMAIL", "code", code)));
    }

    private MockHttpServletRequestBuilder refresh(String refreshToken) throws Exception {
        return post("/auth/refresh").with(ip()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", refreshToken)));
    }

    private MockHttpServletRequestBuilder storeOrder(Tenant t, String idempotencyKey) {
        MockHttpServletRequestBuilder request = post("/store/slug/" + t.company().getSlug() + "/order")
                .contentType(MediaType.APPLICATION_JSON).content(orderBody(t, 1));
        return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);
    }

    private static String orderBody(Tenant t, int quantity) {
        return "{\"customerName\":\"Cliente Prueba\",\"customerPhone\":\"987654321\",\"paymentMethod\":\"yape\","
                + "\"items\":[{\"productId\":" + t.product().getId() + ",\"quantity\":" + quantity + "}]}";
    }

    private void update(Company company, java.util.function.Consumer<Company> change) {
        Company fresh = companyRepository.findById(company.getId()).orElseThrow();
        change.accept(fresh);
        companyRepository.save(fresh);
    }

    private boolean evaluate(Company company) {
        return Boolean.TRUE.equals(transactions.execute(tx -> lifecycle.evaluate(company.getId(), LocalDateTime.now())));
    }

    private Company.Status companyStatus(Company company) {
        return companyRepository.findById(company.getId()).orElseThrow().getStatus();
    }

    private long auditCount(Long companyId, String action) {
        Long count = action == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE company_id = ?", Long.class, companyId)
                : jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE company_id = ? AND action = ?", Long.class,
                companyId, action);
        return count == null ? 0 : count;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String wrong(String code) {
        return "111111".equals(code) ? "222222" : "111111";
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** IP distinta por petición: los límites por IP no se mezclan entre pruebas. */
    private static RequestPostProcessor ip() {
        int n = SEQ.incrementAndGet();
        return request -> {
            request.setRemoteAddr("198.51." + (n / 250) + "." + (n % 250 + 1));
            return request;
        };
    }
}
