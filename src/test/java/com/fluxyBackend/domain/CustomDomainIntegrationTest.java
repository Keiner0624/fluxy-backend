package com.fluxyBackend.domain;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.marketing.entity.MarketingCampaign;
import com.fluxyBackend.marketing.enums.CampaignChannel;
import com.fluxyBackend.marketing.enums.CampaignType;
import com.fluxyBackend.marketing.service.CampaignLinkBuilder;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.RateLimitService;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.support.TestAuth;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dominio propio con un Vercel falso: conexión, www, DNS, verificación, CORS, plan, tienda y limpieza. */
@SpringBootTest
@AutoConfigureMockMvc
class CustomDomainIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final HttpServer VERCEL;
    /** Dominios en el proyecto falso → redirección (o ""). */
    private static final Map<String, String> PROJECT = new ConcurrentHashMap<>();
    /** Dominios cuyos DNS ya apuntan a Vercel. */
    private static final Set<String> CONFIGURED = ConcurrentHashMap.newKeySet();
    /** Dominios que Vercel pide verificar con TXT. */
    private static final Set<String> UNVERIFIED = ConcurrentHashMap.newKeySet();
    /** Dominios que están en otra cuenta de Vercel. */
    private static final Set<String> ELSEWHERE = ConcurrentHashMap.newKeySet();
    private static final List<String> AUTH = new CopyOnWriteArrayList<>();
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REDIRECT = Pattern.compile("\"redirect\"\\s*:\\s*\"([^\"]+)\"");

    static {
        try {
            VERCEL = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            VERCEL.createContext("/", CustomDomainIntegrationTest::handle);
            VERCEL.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void vercel(DynamicPropertyRegistry registry) {
        registry.add("vercel.api_url", () -> "http://127.0.0.1:" + VERCEL.getAddress().getPort());
        registry.add("vercel.token", () -> "token-de-prueba");
        registry.add("vercel.project_id", () -> "prj_fluxy");
        registry.add("app.domains.check_initial_delay_ms", () -> "3600000");
    }

    @AfterAll
    static void stop() {
        VERCEL.stop(0);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private SessionService sessions;
    @Autowired private RateLimitService rateLimits;
    @Autowired private CompanyRepository companies;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;
    @Autowired private CampaignLinkBuilder links;
    @Autowired private CustomDomainRegistry registry;

    @BeforeEach
    void setUp() {
        rateLimits.reset();
        AUTH.clear();
    }

    @Test
    void conectaUnDominioRaizConWwwYSeActivaCuandoLosDnsApuntan() throws Exception {
        Tenant t = tenant(Plan.BUSINESS);
        String domain = "mitienda" + uid() + ".com";

        JsonNode view = body(connect(t, "https://WWW." + domain.toUpperCase() + "/").andExpect(status().isOk()));
        assertThat(view.get("domain").asString()).isEqualTo(domain);
        assertThat(view.get("status").asString()).isEqualTo("PENDING_DNS");
        assertThat(view.get("apex").asBoolean()).isTrue();
        assertThat(records(view)).containsExactly("A @ 76.76.21.21", "CNAME www cname.vercel-dns.com");
        assertThat(PROJECT).containsEntry(domain, "").containsEntry("www." + domain, domain);
        assertThat(AUTH).allMatch("Bearer token-de-prueba"::equals);

        CONFIGURED.add(domain);
        mvc.perform(post("/domains/verify").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.records.length()").value(0))
                .andExpect(jsonPath("$.url").value("https://" + domain));
        mvc.perform(get("/companies/my-company").header("Authorization", t.token()))
                .andExpect(jsonPath("$.storeDomain").value(domain));

        // Los enlaces de campañas pasan a usar el dominio propio.
        MarketingCampaign campaign = new MarketingCampaign();
        campaign.setType(CampaignType.STORE);
        campaign.setTrackingCode("abc123");
        String url = links.link(campaign, companies.findById(t.company().getId()).orElseThrow(), CampaignChannel.WHATSAPP, null).url();
        assertThat(url).startsWith("https://" + domain + "/?").contains("cmp=abc123");
    }

    @Test
    void laTiendaSeResuelvePorDominioYElCorsSoloAbreLaApiPublica() throws Exception {
        Tenant t = tenant(Plan.BUSINESS);
        String domain = "cors" + uid() + ".pe";
        CONFIGURED.add(domain);
        connect(t, domain).andExpect(jsonPath("$.status").value("ACTIVE"));
        String origin = "https://" + domain;

        mvc.perform(get("/store/domain").param("host", "www." + domain + ":443"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(t.company().getSlug()))
                .andExpect(jsonPath("$.companyId").value(t.company().getId()))
                .andExpect(jsonPath("$.active").value(true));

        mvc.perform(get("/store/domain").param("host", domain).header("Origin", origin))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
        mvc.perform(get("/store/slug/" + t.company().getSlug() + "/info").header("Origin", "https://www." + domain))
                .andExpect(header().string("Access-Control-Allow-Origin", "https://www." + domain));
        // Preflight del pedido (JSON + Idempotency-Key).
        mvc.perform(options("/store/" + t.company().getId() + "/order").header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));

        // El resto de la API no acepta el dominio de una tienda, ni orígenes desconocidos o sin HTTPS.
        mvc.perform(get("/domains").header("Authorization", t.token()).header("Origin", origin))
                .andExpect(status().isForbidden());
        mvc.perform(get("/store/domain").param("host", domain).header("Origin", "https://otra" + uid() + ".com"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/store/domain").param("host", domain).header("Origin", "http://" + domain))
                .andExpect(status().isForbidden());
        mvc.perform(get("/store/domain").param("host", "nadie" + uid() + ".com")).andExpect(status().isNotFound());
    }

    @Test
    void sinBusinessNoSeConectaYSiBajaDePlanLaTiendaVuelveAFluxy() throws Exception {
        Tenant pro = tenant(Plan.PRO);
        connect(pro, "pro" + uid() + ".com")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));

        Tenant t = tenant(Plan.BUSINESS);
        String domain = "baja" + uid() + ".com";
        CONFIGURED.add(domain);
        connect(t, domain).andExpect(jsonPath("$.status").value("ACTIVE"));

        Company company = companies.findById(t.company().getId()).orElseThrow();
        company.setPlan(Plan.PRO);
        companies.save(company);

        mvc.perform(get("/store/domain").param("host", domain))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.storeUrl").value(org.hamcrest.Matchers.endsWith("/store/" + t.company().getSlug())));
        mvc.perform(get("/companies/my-company").header("Authorization", t.token()))
                .andExpect(jsonPath("$.storeDomain").doesNotExist());
        mvc.perform(get("/domains").header("Authorization", t.token()))
                .andExpect(jsonPath("$.planIncludes").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("son llevados a tu tienda en Fluxy")));
        // Quitarlo sigue permitido sin el plan.
        mvc.perform(delete("/domains").header("Authorization", t.token())).andExpect(status().isOk());
        assertThat(PROJECT).doesNotContainKeys(domain, "www." + domain);
    }

    @Test
    void unDominioEsDeUnaSolaTiendaYLosDeFluxyNoSePermiten() throws Exception {
        Tenant a = tenant(Plan.BUSINESS);
        Tenant b = tenant(Plan.BUSINESS);
        String domain = "unica" + uid() + ".com";
        connect(a, domain).andExpect(status().isOk());
        connect(b, "www." + domain)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_TAKEN"));
        connect(a, "otro" + uid() + ".com")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_ALREADY_SET"));

        connect(b, "fluxyweb.vercel.app").andExpect(status().isBadRequest());
        connect(b, "tienda.fluxyweb.com").andExpect(status().isBadRequest());
        connect(b, "mi-tienda.vercel.app").andExpect(status().isBadRequest());
        connect(b, "no es un dominio").andExpect(status().isBadRequest());
        connect(b, "localhost").andExpect(status().isBadRequest());

        String elsewhere = "ajeno" + uid() + ".com";
        ELSEWHERE.add(elsewhere);
        connect(b, elsewhere)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("otro sitio en Vercel")));
        mvc.perform(get("/companies/my-company").header("Authorization", b.token()))
                .andExpect(jsonPath("$.customDomain").doesNotExist());
    }

    @Test
    void pideElTxtCuandoElDominioEstaEnOtraCuentaYAceptaSubdominios() throws Exception {
        Tenant t = tenant(Plan.BUSINESS);
        String domain = "verificar" + uid() + ".com";
        UNVERIFIED.add(domain);
        JsonNode view = body(connect(t, domain).andExpect(status().isOk()));
        assertThat(view.get("status").asString()).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(records(view)).contains("TXT _vercel vc-domain-verify=" + domain);

        UNVERIFIED.remove(domain);
        CONFIGURED.add(domain);
        mvc.perform(post("/domains/verify").header("Authorization", t.token())).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(delete("/domains").header("Authorization", t.token())).andExpect(status().isOk());

        String sub = "tienda.marca" + uid() + ".com";
        view = body(connect(t, sub).andExpect(status().isOk()));
        assertThat(view.get("apex").asBoolean()).isFalse();
        assertThat(records(view)).containsExactly("CNAME tienda cname.vercel-dns.com");
        assertThat(PROJECT).containsKey(sub).doesNotContainKey("www." + sub);
    }

    @Test
    void quitarLoBorraDeVercelYLaTiendaDejaDeResponderEnEseDominio() throws Exception {
        Tenant t = tenant(Plan.BUSINESS);
        String domain = "quitar" + uid() + ".com";
        CONFIGURED.add(domain);
        connect(t, domain).andExpect(status().isOk());
        assertThat(registry.isStoreOrigin("https://" + domain)).isTrue();

        mvc.perform(delete("/domains").header("Authorization", t.token())).andExpect(status().isOk());
        assertThat(PROJECT).doesNotContainKeys(domain, "www." + domain);
        mvc.perform(get("/store/domain").param("host", domain)).andExpect(status().isNotFound());
        assertThat(registry.isStoreOrigin("https://" + domain)).isFalse();
        mvc.perform(get("/domains").header("Authorization", t.token())).andExpect(jsonPath("$.status").value("NONE"));
    }

    // ─── Vercel falso ─────────────────────────────────────────────────────────

    private static void handle(HttpExchange exchange) throws IOException {
        AUTH.add(exchange.getRequestHeaders().getFirst("Authorization"));
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String prefix = "/v9/projects/prj_fluxy/domains/";

        if ("POST".equals(method) && path.equals("/v10/projects/prj_fluxy/domains")) {
            String name = group(NAME, body);
            if (ELSEWHERE.contains(name) || PROJECT.containsKey(name)) {
                reply(exchange, 409, "{\"error\":{\"code\":\"domain_already_in_use\",\"message\":\"in use\"}}");
                return;
            }
            String redirect = group(REDIRECT, body);
            PROJECT.put(name, redirect == null ? "" : redirect);
            reply(exchange, 200, domainJson(name));
        } else if ("GET".equals(method) && path.startsWith(prefix)) {
            String name = path.substring(prefix.length());
            reply(exchange, PROJECT.containsKey(name) ? 200 : 404, PROJECT.containsKey(name) ? domainJson(name) : "{}");
        } else if ("POST".equals(method) && path.startsWith(prefix) && path.endsWith("/verify")) {
            String name = path.substring(prefix.length(), path.length() - "/verify".length());
            if (UNVERIFIED.contains(name)) reply(exchange, 400, "{\"error\":{\"code\":\"missing_txt_record\"}}");
            else reply(exchange, 200, domainJson(name));
        } else if ("DELETE".equals(method) && path.startsWith(prefix)) {
            PROJECT.remove(path.substring(prefix.length()));
            reply(exchange, 200, "{}");
        } else if ("GET".equals(method) && path.startsWith("/v6/domains/") && path.endsWith("/config")) {
            String name = path.substring("/v6/domains/".length(), path.length() - "/config".length());
            reply(exchange, 200, "{\"misconfigured\":" + !CONFIGURED.contains(name)
                    + ",\"recommendedIPv4\":[{\"rank\":1,\"value\":[\"76.76.21.21\"]}],"
                    + "\"recommendedCNAME\":[{\"rank\":1,\"value\":\"cname.vercel-dns.com.\"}]}");
        } else {
            reply(exchange, 404, "{\"error\":{\"code\":\"not_found\"}}");
        }
    }

    private static String domainJson(String name) {
        String[] labels = name.split("\\.");
        String apex = labels.length > 2 ? labels[labels.length - 2] + "." + labels[labels.length - 1] : name;
        boolean verified = !UNVERIFIED.contains(name);
        String verification = verified ? "[]"
                : "[{\"type\":\"TXT\",\"domain\":\"_vercel." + apex + "\",\"value\":\"vc-domain-verify=" + name
                + "\",\"reason\":\"pending_domain_verification\"}]";
        String redirect = PROJECT.getOrDefault(name, "");
        return "{\"name\":\"" + name + "\",\"apexName\":\"" + apex + "\",\"verified\":" + verified
                + ",\"verification\":" + verification + ",\"redirect\":" + (redirect.isEmpty() ? "null" : "\"" + redirect + "\"") + "}";
    }

    private static String group(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private record Tenant(Company company, User owner, String token) {}

    private ResultActions connect(Tenant t, String domain) throws Exception {
        return mvc.perform(post("/domains").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("domain", domain))));
    }

    private static List<String> records(JsonNode view) {
        List<String> records = new java.util.ArrayList<>();
        view.get("records").forEach(r -> records.add(r.get("type").asString() + " " + r.get("name").asString() + " "
                + r.get("value").asString()));
        return records;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Tenant tenant(Plan plan) {
        String id = uid();
        Company company = Company.builder().name("Tienda " + id).slug("dom-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company.setPlan(plan);
        if (plan != Plan.FREE) company.setPlanExpiresAt(LocalDateTime.now().plusDays(30));
        company = companies.save(company);
        User owner = User.builder().fullName("Dueño " + id).email("dom-" + id + "@fluxy.invalid")
                .password("x").role(Role.BUSINESS_OWNER).company(company).build();
        owner.setStatus(User.Status.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = users.save(owner);
        memberships.save(new Membership(owner.getId(), company.getId(), "OWNER"));
        return new Tenant(company, owner, TestAuth.bearer(sessions, owner));
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 6) + SEQ.incrementAndGet();
    }
}
