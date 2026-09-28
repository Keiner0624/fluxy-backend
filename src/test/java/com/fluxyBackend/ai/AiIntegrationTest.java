package com.fluxyBackend.ai;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.RateLimitService;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.support.TestAuth;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IA con un Gemini falso: plan, permisos, clave fuera de la URL, prompts y manejo de errores. */
@SpringBootTest
@AutoConfigureMockMvc
class AiIntegrationTest {

    private static final HttpServer GEMINI;
    private static final List<String> PATHS = new CopyOnWriteArrayList<>();
    private static final List<String> KEYS = new CopyOnWriteArrayList<>();
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static final AtomicReference<Object[]> REPLY = new AtomicReference<>();
    /** Respuestas que salen antes que REPLY, en orden (para simular una falla y el reintento). */
    private static final java.util.Queue<Object[]> NEXT = new java.util.concurrent.ConcurrentLinkedQueue<>();

    static {
        try {
            GEMINI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            GEMINI.createContext("/", exchange -> {
                PATHS.add(exchange.getRequestURI().toString());
                KEYS.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Object[] next = NEXT.poll();
                Object[] reply = next != null ? next : REPLY.get();
                byte[] bytes = ((String) reply[1]).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders((Integer) reply[0], bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            GEMINI.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void gemini(DynamicPropertyRegistry registry) {
        registry.add("gemini.api.key", () -> "clave-secreta-de-prueba");
        registry.add("gemini.base_url", () -> "http://127.0.0.1:" + GEMINI.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        GEMINI.stop(0);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private SessionService sessions;
    @Autowired private RateLimitService rateLimits;
    @Autowired private CompanyRepository companies;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;

    @BeforeEach
    void setUp() {
        rateLimits.reset();
        PATHS.clear();
        NEXT.clear();
        KEYS.clear();
        BODIES.clear();
        REPLY.set(new Object[]{200, text("\"Mochila resistente para el día a día, con espacio para tu laptop y todo lo que llevás.\"")});
    }

    @Test
    void generaLaDescripcionConLaClaveEnLaCabeceraYElTextoLimpio() throws Exception {
        String token = owner(Plan.BUSINESS);
        mvc.perform(post("/ai/describe").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochila urbana\",\"price\":\"89.90\",\"category\":\"Accesorios\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Mochila resistente para el día a día, con espacio para tu laptop y todo lo que llevás."));

        assertThat(KEYS).containsExactly("clave-secreta-de-prueba");
        assertThat(PATHS.get(0)).isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent").doesNotContain("key=");
        JsonNode sent = json.readTree(BODIES.get(0));
        assertThat(sent.at("/contents/0/parts/0/text").asString()).contains("\"Mochila urbana\"").contains("\"89.90\"");
        assertThat(sent.at("/systemInstruction/parts/0/text").asString()).contains("no inventes");
        assertThat(sent.at("/generationConfig/thinkingConfig/thinkingBudget").asInt()).isZero();
    }

    @Test
    void siElModeloFueRetiradoReintentaConElAliasVigente() throws Exception {
        NEXT.add(new Object[]{404, "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}"});
        mvc.perform(post("/ai/describe").header("Authorization", owner(Plan.BUSINESS)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochila urbana\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.startsWith("Mochila resistente")));
        assertThat(PATHS).containsExactly("/v1beta/models/gemini-2.5-flash:generateContent",
                "/v1beta/models/gemini-flash-latest:generateContent");
        // En el alias no se apaga el razonamiento: se deja lugar para pensar y responder.
        JsonNode retry = json.readTree(BODIES.get(1));
        assertThat(retry.at("/generationConfig/thinkingConfig").isMissingNode()).isTrue();
        assertThat(retry.at("/generationConfig/maxOutputTokens").asInt()).isGreaterThan(300);
    }

    @Test
    void soloElPlanBusinessYConPermisoDeProductos() throws Exception {
        mvc.perform(post("/ai/describe").header("Authorization", owner(Plan.PRO)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochila\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
        String business = owner(Plan.BUSINESS);
        mvc.perform(post("/ai/describe").header("Authorization", business).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
        assertThat(PATHS).isEmpty();
    }

    @Test
    void lasFallasDeGeminiNoExponenDetalles() throws Exception {
        String token = owner(Plan.BUSINESS);
        REPLY.set(new Object[]{400, "{\"error\":{\"message\":\"API key not valid\"}}"});
        String body = mvc.perform(post("/ai/describe").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochila\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("API key");

        REPLY.set(new Object[]{429, "{}"});
        mvc.perform(post("/ai/describe").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochila\"}"))
                .andExpect(status().isServiceUnavailable());

        REPLY.set(new Object[]{200, "{\"candidates\":[{\"finishReason\":\"SAFETY\",\"content\":{\"parts\":[]}}]}"});
        mvc.perform(post("/ai/describe").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochila\"}"))
                .andExpect(jsonPath("$.code").value("AI_BLOCKED"));
    }

    @Test
    void generaElTextoDeUnaCampana() throws Exception {
        String token = owner(Plan.BUSINESS);
        REPLY.set(new Object[]{200, text("```json\n{\"title\":\"Pollo del finde\",\"message\":\"Usá FINDE10 y llevate 10% menos en tu pollo.\",\"callToAction\":\"Pedilo acá\"}\n```")});
        mvc.perform(post("/ai/campaign-copy").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PRODUCT\",\"objective\":\"PROMOTION\",\"channel\":\"WHATSAPP\",\"target\":\"1/4 Pollo\",\"couponCode\":\"FINDE10\",\"discount\":\"10%\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Pollo del finde"))
                .andExpect(jsonPath("$.callToAction").value("Pedilo acá"));
        JsonNode sent = json.readTree(BODIES.get(0));
        assertThat(sent.at("/generationConfig/responseMimeType").asString()).isEqualTo("application/json");
        assertThat(sent.at("/contents/0/parts/0/text").asString()).contains("WhatsApp").contains("\"FINDE10\"");
    }

    @Test
    void hayUnTopeDeGeneracionesPorEmpresa() throws Exception {
        String token = owner(Plan.BUSINESS);
        for (int i = 0; i < 60; i++) {
            mvc.perform(post("/ai/describe").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Mochila\"}")).andExpect(status().isOk());
        }
        mvc.perform(post("/ai/describe").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Mochila\"}")).andExpect(status().isTooManyRequests());
    }

    @Test
    void sinClaveConfiguradaNoLlamaAGemini() {
        GeminiClient client = new GeminiClient(json, "", "gemini-2.5-flash", "http://127.0.0.1:1");
        assertThat(client.configured()).isFalse();
        assertThatThrownBy(() -> client.generate("x", "y", 10, 0.5, false))
                .hasMessageContaining("no está disponible");
    }

    private String owner(Plan plan) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Company company = Company.builder().name("IA " + id).slug("ia-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company.setPlan(plan);
        company.setPlanExpiresAt(LocalDateTime.now().plusDays(30));
        company = companies.save(company);
        User owner = User.builder().fullName("Dueña " + id).email("ia-" + id + "@fluxy.invalid").password("x")
                .role(Role.BUSINESS_OWNER).company(company).build();
        owner.setStatus(User.Status.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = users.save(owner);
        memberships.save(new Membership(owner.getId(), company.getId(), "OWNER"));
        return TestAuth.bearer(sessions, owner);
    }

    private String text(String value) {
        return json.writeValueAsString(java.util.Map.of("candidates", List.of(java.util.Map.of(
                "finishReason", "STOP",
                "content", java.util.Map.of("parts", List.of(java.util.Map.of("text", value)))))));
    }
}
