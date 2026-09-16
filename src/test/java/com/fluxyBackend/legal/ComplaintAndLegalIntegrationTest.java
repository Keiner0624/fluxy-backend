package com.fluxyBackend.legal;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.LegalAcceptance;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.LegalAcceptanceRepository;
import com.fluxyBackend.repository.MembershipRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Libro de Reclamaciones virtual y aceptación de la versión vigente de los documentos legales. */
@SpringBootTest
@AutoConfigureMockMvc
class ComplaintAndLegalIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RateLimitService rateLimits;
    @Autowired private SessionService sessions;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private LegalAcceptanceRepository legalRepository;

    @BeforeEach
    void resetLimits() {
        rateLimits.reset();
    }

    // ─── Libro de Reclamaciones ──────────────────────────────────────────────

    @Test
    void unaHojaQuedaRegistradaConCodigoYPlazo() throws Exception {
        JsonNode receipt = json.readTree(mvc.perform(complaint(validComplaint(), "198.18.0.1"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        String code = receipt.path("code").asString();
        assertThat(code).matches("LR-\\d{4}-\\d{6}");
        assertThat(receipt.path("provider").path("name").asString()).isNotBlank();
        LocalDate due = LocalDate.parse(receipt.path("dueDate").asString());
        assertThat(due).isAfter(LocalDate.now().plusDays(14));
        assertThat(due.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

        Map<String, Object> row = jdbc.queryForMap("SELECT status, document_number, ip_hash, legal_version FROM complaints WHERE code = ?", code);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("document_number")).isEqualTo("12345678");
        assertThat((String) row.get("ip_hash")).isNotBlank().doesNotContain("198.18");
        assertThat(row.get("legal_version")).isEqualTo(LegalAcceptance.TERMS_VERSION);
    }

    @Test
    void laHojaExigeDatosCompletosYConfirmacion() throws Exception {
        Map<String, Object> incomplete = validComplaint();
        incomplete.remove("detail");
        mvc.perform(complaint(incomplete, "198.18.0.2")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        Map<String, Object> unconfirmed = validComplaint();
        unconfirmed.put("accepted", false);
        mvc.perform(complaint(unconfirmed, "198.18.0.2")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"));

        Map<String, Object> minor = validComplaint();
        minor.put("minor", true);
        mvc.perform(complaint(minor, "198.18.0.2")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUARDIAN_REQUIRED"));
    }

    @Test
    void soloAdministracionVeYRespondeLasHojas() throws Exception {
        String code = json.readTree(mvc.perform(complaint(validComplaint(), "198.18.0.3"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("code").asString();
        Long id = jdbc.queryForObject("SELECT id FROM complaints WHERE code = ?", Long.class, code);

        String owner = TestAuth.bearer(sessions, owner().owner());
        mvc.perform(get("/admin/complaints").header("Authorization", owner)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/complaints")).andExpect(status().isUnauthorized());

        String admin = "Bearer " + json.readTree(mvc.perform(post("/auth/admin-login").with(ip("198.18.0.4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@fluxy.invalid\",\"password\":\"test-admin-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asString();

        mvc.perform(get("/admin/complaints").param("status", "PENDING").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").isNumber());

        mvc.perform(post("/admin/complaints/" + id + "/response").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"response\":\"Revisamos tu caso y reembolsamos el cobro.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.respondedBy").value("admin@fluxy.invalid"));
        mvc.perform(post("/admin/complaints/" + id + "/response").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"response\":\"Otra respuesta distinta.\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void lasHojasTienenLimitePorIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(complaint(validComplaint(), "198.18.1.1")).andExpect(status().isCreated());
        }
        mvc.perform(complaint(validComplaint(), "198.18.1.1")).andExpect(status().isTooManyRequests());
    }

    // ─── Aceptación de los documentos ────────────────────────────────────────

    @Test
    void unaCuentaConVersionAnteriorDebeAceptarLaVigente() throws Exception {
        Tenant t = owner();
        jdbc.update("INSERT INTO legal_acceptance (user_id, company_id, terms_version, privacy_version, accepted_at, event) "
                + "VALUES (?, ?, '2026-05-05', '2026-05-05', CURRENT_TIMESTAMP, 'BUSINESS_REGISTERED')", t.owner().getId(), t.company().getId());
        String token = TestAuth.bearer(sessions, t.owner());

        mvc.perform(get("/me/legal").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedVersion").value("2026-05-05"))
                .andExpect(jsonPath("$.needsAcceptance").value(true));

        mvc.perform(post("/me/legal/accept").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"2026-05-05\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGAL_VERSION_OUTDATED"));

        String body = "{\"version\":\"" + LegalAcceptance.TERMS_VERSION + "\"}";
        mvc.perform(post("/me/legal/accept").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsAcceptance").value(false));
        mvc.perform(post("/me/legal/accept").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        LegalAcceptance latest = legalRepository.findFirstByUserIdOrderByAcceptedAtDesc(t.owner().getId()).orElseThrow();
        assertThat(latest.getTermsVersion()).isEqualTo(LegalAcceptance.TERMS_VERSION);
        assertThat(latest.getEvent()).isEqualTo(LegalAcceptance.EVENT_UPDATED);
        assertThat(latest.getIpHash()).isNotBlank();
        Long accepted = jdbc.queryForObject("SELECT COUNT(*) FROM legal_acceptance WHERE user_id = ? AND terms_version = ?",
                Long.class, t.owner().getId(), LegalAcceptance.TERMS_VERSION);
        assertThat(accepted).isEqualTo(1);
        Long audited = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE company_id = ? AND action = 'TERMS_ACCEPTED'",
                Long.class, t.company().getId());
        assertThat(audited).isEqualTo(1);
    }

    // ─── Apoyo ───────────────────────────────────────────────────────────────

    private record Tenant(Company company, User owner) {}

    private Tenant owner() {
        String id = UUID.randomUUID().toString().substring(0, 8) + SEQ.incrementAndGet();
        Company company = Company.builder().name("Tienda " + id).slug("lr-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company = companyRepository.save(company);
        User owner = User.builder().fullName("Dueño " + id).email("lr-" + id + "@fluxy.invalid")
                .password("x").role(Role.BUSINESS_OWNER).company(company).build();
        owner.setStatus(User.Status.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = userRepository.save(owner);
        membershipRepository.save(new Membership(owner.getId(), company.getId(), "OWNER"));
        return new Tenant(company, owner);
    }

    private static Map<String, Object> validComplaint() {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "RECLAMO");
        body.put("consumerName", "Ana Pérez Torres");
        body.put("documentType", "DNI");
        body.put("documentNumber", "12345678");
        body.put("address", "Av. Arequipa 1234, Lima");
        body.put("phone", "987654321");
        body.put("email", "ana@fluxy.invalid");
        body.put("minor", false);
        body.put("itemType", "SERVICIO");
        body.put("itemDescription", "Plan PRO mensual");
        body.put("amount", 39);
        body.put("detail", "Pagué el plan y no se activó.");
        body.put("consumerRequest", "Que se active el plan o se devuelva el pago.");
        body.put("accepted", true);
        return body;
    }

    private MockHttpServletRequestBuilder complaint(Map<String, Object> body, String ip) throws Exception {
        return post("/complaints").with(ip(ip)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
