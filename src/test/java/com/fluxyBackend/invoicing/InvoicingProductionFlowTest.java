package com.fluxyBackend.invoicing;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.invoicing.entity.InvoicingConfiguration;
import com.fluxyBackend.invoicing.entity.TaxProfile;
import com.fluxyBackend.invoicing.enums.DocumentStatus;
import com.fluxyBackend.invoicing.repository.ElectronicDocumentRepository;
import com.fluxyBackend.invoicing.repository.InvoicingConfigurationRepository;
import com.fluxyBackend.invoicing.repository.TaxProfileRepository;
import com.fluxyBackend.invoicing.service.DocumentProcessor;
import com.fluxyBackend.invoicing.service.InvoicingConfigurationService;
import com.fluxyBackend.invoicing.service.RucLookup;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.RateLimitService;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.support.TestAuth;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Producción de punta a punta con un Nubefact falso y un padrón simulado: RUC verificado en el
 * servidor → credenciales cifradas → prueba → activación → pedido → comprobante aceptado → PDF del
 * proveedor transmitido por Fluxy → revalidación que suspende sin tocar lo emitido.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvoicingProductionFlowTest {

    private static final String RUC = "20600000005";
    private static final String TOKEN = "nubefact-token-secreto-0123456789";

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private SessionService sessions;
    @Autowired private RateLimitService rateLimits;
    @Autowired private CompanyRepository companies;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;
    @Autowired private ProductRepository products;
    @Autowired private InvoicingConfigurationRepository configurations;
    @Autowired private TaxProfileRepository profiles;
    @Autowired private ElectronicDocumentRepository documents;
    @Autowired private DocumentProcessor processor;
    @Autowired private InvoicingConfigurationService configuration;
    @MockitoBean private RucLookup rucLookup;

    private HttpServer nubefact;
    private String endpoint;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        rateLimits.reset();
        nubefact = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = nubefact.getAddress().getPort();
        nubefact.createContext("/api/v1/cuenta", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode body = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean consult = "consultar_comprobante".equals(body.path("operacion").asString());
            String reply = consult ? "{\"errors\":\"El documento no existe\",\"codigo\":24}"
                    : "{\"aceptada_por_sunat\":true,\"sunat_description\":\"Aceptada por SUNAT\",\"sunat_responsecode\":\"0\","
                    + "\"codigo_hash\":\"h4sh\",\"enlace_del_pdf\":\"http://localhost:" + port + "/files/doc.pdf\","
                    + "\"enlace_del_xml\":\"http://localhost:" + port + "/files/doc.xml\"}";
            send(exchange, consult ? 400 : 200, reply.getBytes(StandardCharsets.UTF_8));
        });
        nubefact.createContext("/files/", exchange -> send(exchange, 200, "%PDF-del-proveedor".getBytes(StandardCharsets.UTF_8)));
        nubefact.start();
        endpoint = "http://localhost:" + port + "/api/v1/cuenta";
    }

    @AfterEach
    void tearDown() {
        nubefact.stop(0);
    }

    @Test
    void emisionRealDePuntaAPuntaYSuspensionAlCaerElRuc() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Company company = Company.builder().name("Bodega " + id).slug("prod-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company.setPlan(Plan.BUSINESS);
        company.setPlanExpiresAt(LocalDateTime.now().plusDays(30));
        company = companies.save(company);
        User owner = User.builder().fullName("Dueña").email("prod-" + id + "@fluxy.invalid").password("x")
                .role(Role.BUSINESS_OWNER).company(company).build();
        owner.setStatus(User.Status.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = users.save(owner);
        memberships.save(new Membership(owner.getId(), company.getId(), "OWNER"));
        String token = TestAuth.bearer(sessions, owner);

        // Proveedor real: la ruta tiene que ser de Nubefact.
        mvc.perform(put("/invoicing/configuration/provider").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("provider", "NUBEFACT", "endpoint", "https://evil.test/robar", "token", TOKEN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROVIDER_ENDPOINT_INVALID"));
        String view = mvc.perform(put("/invoicing/configuration/provider").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("provider", "NUBEFACT", "endpoint", endpoint, "token", TOKEN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("PRODUCTION"))
                .andExpect(jsonPath("$.tokenHint").value("6789"))
                .andReturn().getResponse().getContentAsString();
        assertThat(view).doesNotContain(TOKEN).doesNotContain("/api/v1/cuenta");
        InvoicingConfiguration stored = configurations.findByCompanyId(company.getId()).orElseThrow();
        assertThat(stored.getProviderTokenCipher()).startsWith("v1:").doesNotContain(TOKEN);

        // Sin padrón disponible no se da por válido.
        when(rucLookup.configured()).thenReturn(true);
        when(rucLookup.lookup(anyString())).thenThrow(new RucLookup.Unavailable("El servicio de consulta de RUC no respondió."));
        verify(token).andExpect(jsonPath("$.profile.verificationStatus").value("ERROR"));
        mvc.perform(post("/invoicing/configuration/activate").header("Authorization", token)).andExpect(status().isConflict());

        doReturn(Optional.of(new RucLookup.RucInfo(RUC, "BODEGA SEGURA S.A.C.", "ACTIVO", "HABIDO", "AV. LIMA 123 - LIMA"))).when(rucLookup).lookup(anyString());
        verify(token)
                .andExpect(jsonPath("$.profile.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.profile.verificationSource").value("RUC_API"))
                .andExpect(jsonPath("$.businessName").value("BODEGA SEGURA S.A.C."))
                .andExpect(jsonPath("$.fiscalAddress").value("AV. LIMA 123 - LIMA"));
        mvc.perform(post("/invoicing/configuration/test").header("Authorization", token)).andExpect(jsonPath("$.connectionOk").value(true));
        mvc.perform(post("/invoicing/configuration/activate").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        Prodcut p = products.save(Prodcut.builder().name("Pollo").price(59).stock(10).owner(owner).company(company).build());
        long orderId = body(mvc.perform(post("/orders").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("customerName", "Ana", "items", List.of(Map.of("productId", p.getId(), "quantity", 1)))))))
                .get("id").asLong();
        long docId = body(mvc.perform(post("/invoicing/documents").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("orderId", orderId, "type", "BOLETA")))).andExpect(status().isOk()))
                .get("id").asLong();
        awaitAccepted(docId);
        assertThat(authorizations).contains("Token token=\"" + TOKEN + "\"");
        String pdf = mvc.perform(get("/invoicing/documents/" + docId + "/pdf").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(pdf).isEqualTo("%PDF-del-proveedor");
        mvc.perform(get("/invoicing/documents/" + docId).header("Authorization", token))
                .andExpect(jsonPath("$.test").value(false))
                .andExpect(jsonPath("$.hash").value("h4sh"))
                .andExpect(jsonPath("$.pdfStorageKey").doesNotExist());

        // Cambiar el token exige volver a probar y activar.
        mvc.perform(put("/invoicing/configuration/provider").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("provider", "NUBEFACT", "token", TOKEN + "-nuevo"))))
                .andExpect(jsonPath("$.status").value("REQUIRES_ACTION"))
                .andExpect(jsonPath("$.connectionOk").value(false));
        mvc.perform(post("/invoicing/configuration/test").header("Authorization", token));
        mvc.perform(post("/invoicing/configuration/activate").header("Authorization", token)).andExpect(jsonPath("$.status").value("ACTIVE"));

        // Revalidación: el RUC pasó a baja → se suspende sin tocar lo emitido.
        TaxProfile profile = profiles.findByCompanyId(company.getId()).orElseThrow();
        profile.setLastCheckedAt(LocalDateTime.now().minusDays(10));
        profiles.save(profile);
        doReturn(Optional.of(new RucLookup.RucInfo(RUC, "BODEGA SEGURA S.A.C.", "BAJA DE OFICIO", "HABIDO", null))).when(rucLookup).lookup(anyString());
        assertThat(configuration.revalidateActive()).isEqualTo(1);
        assertThat(configurations.findByCompanyId(company.getId()).orElseThrow().getStatus().name()).isEqualTo("SUSPENDED");
        assertThat(documents.findById(docId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.ACCEPTED);
        mvc.perform(post("/invoicing/documents").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("orderId", orderId, "type", "BOLETA"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVOICING_NOT_ACTIVE"));
    }

    private ResultActions verify(String token) throws Exception {
        return mvc.perform(post("/invoicing/configuration/verify-ruc").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruc\":\"" + RUC + "\",\"taxRegime\":\"MYPE\"}")).andExpect(status().isOk());
    }

    private void awaitAccepted(long id) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            processor.processDue();
            if (documents.findById(id).orElseThrow().getStatus() == DocumentStatus.ACCEPTED) return;
            Thread.sleep(100);
        }
        fail("No se aceptó: " + documents.findById(id).orElseThrow().getStatus() + " " + documents.findById(id).orElseThrow().getLastError());
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] bytes) throws java.io.IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
