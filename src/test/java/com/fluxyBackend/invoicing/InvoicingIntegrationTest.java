package com.fluxyBackend.invoicing;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.invoicing.entity.ElectronicDocument;
import com.fluxyBackend.invoicing.entity.InvoicingConfiguration;
import com.fluxyBackend.invoicing.enums.DocumentStatus;
import com.fluxyBackend.invoicing.enums.EmailStatus;
import com.fluxyBackend.invoicing.provider.ProviderException;
import com.fluxyBackend.invoicing.provider.ProviderResult;
import com.fluxyBackend.invoicing.provider.implementations.SandboxBillingProvider;
import com.fluxyBackend.invoicing.repository.DocumentEventRepository;
import com.fluxyBackend.invoicing.repository.ElectronicDocumentRepository;
import com.fluxyBackend.invoicing.repository.InvoicingConfigurationRepository;
import com.fluxyBackend.invoicing.service.DocumentEmailService;
import com.fluxyBackend.invoicing.service.DocumentProcessor;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.Hashing;
import com.fluxyBackend.security.RateLimitService;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.service.EmailService;
import com.fluxyBackend.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Facturación electrónica en modo de prueba: activación segura, emisión, series, notas, correo, enlaces y webhooks. */
@SpringBootTest
@AutoConfigureMockMvc
class InvoicingIntegrationTest {

    static final String RUC = "20600000005";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private SessionService sessions;
    @Autowired private RateLimitService rateLimits;
    @Autowired private CompanyRepository companies;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;
    @Autowired private ProductRepository products;
    @Autowired private OrderRepository orders;
    @Autowired private ElectronicDocumentRepository documents;
    @Autowired private DocumentEventRepository events;
    @Autowired private InvoicingConfigurationRepository configurations;
    @Autowired private DocumentProcessor processor;
    @Autowired private DocumentEmailService emailWorker;
    @MockitoSpyBean private SandboxBillingProvider sandbox;
    @MockitoSpyBean private EmailService email;

    @BeforeEach
    void setUp() {
        rateLimits.reset();
    }

    // ─── Activación ──────────────────────────────────────────────────────────

    @Test
    void soloElServidorActivaYNoAceptaCapacidadesDelCliente() throws Exception {
        Tenant t = tenant(Plan.PRO);
        mvc.perform(post("/invoicing/configuration/activate").header("Authorization", t.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVOICING_NOT_READY"));
        // Un cliente manipulado manda capacidades y estado: se ignoran.
        mvc.perform(put("/invoicing/configuration").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiscalAddress\":\"Av. Lima 123, Lima\",\"canIssueInvoice\":true,\"canIssueReceipt\":true,"
                                + "\"verificationStatus\":\"VERIFIED\",\"status\":\"ACTIVE\",\"electronicIssuer\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.profile.verificationStatus").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.profile.canIssueInvoice").value(false));
        mvc.perform(post("/invoicing/configuration/verify-ruc").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruc\":\"20600000004\",\"businessName\":\"X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RUC_INVALID"));
        emitAttempt(t, order(t, 20), "BOLETA").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVOICING_NOT_ACTIVE"));

        activate(t);
        mvc.perform(get("/invoicing/configuration").header("Authorization", t.token()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.environment").value("TEST"))
                .andExpect(jsonPath("$.profile.verificationSource").value("SANDBOX"))
                .andExpect(jsonPath("$.profile.canIssueInvoice").value(true))
                .andExpect(jsonPath("$.series[?(@.series=='F001')].enabled").value(true));
    }

    @Test
    void elPlanFreeNoConfiguraFacturacion() throws Exception {
        Tenant t = tenant(Plan.FREE);
        mvc.perform(put("/invoicing/configuration").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tradeName\":\"X\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
        mvc.perform(get("/invoicing/status").header("Authorization", t.token()))
                .andExpect(jsonPath("$.planAllowed").value(false))
                .andExpect(jsonPath("$.canIssueReceipt").value(false));
    }

    // ─── Emisión ─────────────────────────────────────────────────────────────

    @Test
    void emiteUnaBoletaConNumeroImportesArchivosEIdempotencia() throws Exception {
        Tenant t = tenant(Plan.PRO);
        activate(t);
        long orderId = order(t, 59, 2, 20, 1);

        String key = "emitir-" + orderId + "-abc";
        JsonNode first = body(emitAttempt(t, orderId, "BOLETA", key).andExpect(status().isOk()));
        long id = first.get("id").asLong();
        assertThat(first.get("fullNumber").asString()).isEqualTo("B001-00000001");
        assertThat(first.get("subtotal").decimalValue()).isEqualByComparingTo("116.95");
        assertThat(first.get("tax").decimalValue()).isEqualByComparingTo("21.05");
        assertThat(first.get("total").decimalValue()).isEqualByComparingTo("138.00");
        assertThat(first.get("test").asBoolean()).isTrue();

        // Doble clic: la misma clave devuelve el mismo comprobante; otra clave no emite un segundo.
        assertThat(body(emitAttempt(t, orderId, "BOLETA", key)).get("id").asLong()).isEqualTo(id);
        emitAttempt(t, orderId, "BOLETA", "otra-clave-" + orderId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ALREADY_EXISTS"));

        await(id, DocumentStatus.ACCEPTED);
        byte[] pdf = mvc.perform(get("/invoicing/documents/" + id + "/pdf").header("Authorization", t.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        String xml = mvc.perform(get("/invoicing/documents/" + id + "/xml").header("Authorization", t.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(xml).contains("<cbc:ID>B001-1</cbc:ID>").contains("<cbc:InvoiceTypeCode listID=\"0101\">03</cbc:InvoiceTypeCode>");

        mvc.perform(get("/invoicing/documents/" + id).header("Authorization", t.token()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.amountInWords").value("CIENTO TREINTA Y OCHO CON 00/100 SOLES"))
                .andExpect(jsonPath("$.history[0].type").value("CREATED"));
        mvc.perform(get("/invoicing/documents").param("orderId", String.valueOf(orderId)).header("Authorization", t.token()))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void laFacturaExigeRucValidoYElNuevoRusNoLaPuedeEmitir() throws Exception {
        Tenant t = tenant(Plan.PRO);
        activate(t);
        long orderId = order(t, 100);
        emit(t, Map.of("orderId", orderId, "type", "FACTURA", "customerDocumentType", "RUC", "customerDocumentNumber", "20600000004",
                "customerName", "Cliente SAC"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RECEIVER_INVALID"));
        JsonNode factura = body(emit(t, Map.of("orderId", orderId, "type", "FACTURA", "customerDocumentType", "RUC",
                "customerDocumentNumber", "20123456786", "customerName", "Cliente sac"), null).andExpect(status().isOk()));
        assertThat(factura.get("fullNumber").asString()).isEqualTo("F001-00000001");
        assertThat(factura.get("customerName").asString()).isEqualTo("CLIENTE SAC");

        mvc.perform(put("/invoicing/configuration").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"taxRegime\":\"NRUS\"}")).andExpect(jsonPath("$.profile.canIssueInvoice").value(false));
        emit(t, Map.of("orderId", order(t, 50), "type", "FACTURA", "customerDocumentType", "RUC", "customerDocumentNumber", "20123456786",
                "customerName", "Otro"), null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DOCUMENT_TYPE_NOT_ALLOWED"));
    }

    @Test
    void numeracionConcurrenteSinRepetidos() throws Exception {
        Tenant t = tenant(Plan.PRO);
        activate(t);
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) orderIds.add(order(t, 10 + i));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> numbers = new ArrayList<>();
        for (Long orderId : orderIds) {
            numbers.add(pool.submit(() -> {
                start.await();
                return body(emitAttempt(t, orderId, "BOLETA").andExpect(status().isOk())).get("number").asLong();
            }));
        }
        start.countDown();
        Set<Long> seen = new TreeSet<>();
        for (Future<Long> f : numbers) seen.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(seen).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    // ─── Tenant y permisos ───────────────────────────────────────────────────

    @Test
    void unaEmpresaNoVeNiOperaComprobantesDeOtra() throws Exception {
        Tenant a = tenant(Plan.PRO);
        Tenant b = tenant(Plan.PRO);
        activate(a);
        activate(b);
        long orderA = order(a, 30);
        long id = body(emitAttempt(a, orderA, "BOLETA")).get("id").asLong();
        await(id, DocumentStatus.ACCEPTED);

        for (String path : List.of("", "/pdf", "/xml")) {
            mvc.perform(get("/invoicing/documents/" + id + path).header("Authorization", b.token())).andExpect(status().isNotFound());
        }
        for (String path : List.of("/resend-email", "/retry", "/public-link/revoke")) {
            mvc.perform(post("/invoicing/documents/" + id + path).header("Authorization", b.token())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        }
        mvc.perform(post("/invoicing/documents/" + id + "/credit-notes").header("Authorization", b.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ANULACION\"}")).andExpect(status().isNotFound());
        emitAttempt(b, orderA, "BOLETA").andExpect(status().isNotFound());
        mvc.perform(get("/invoicing/documents").header("Authorization", b.token())).andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void cadaRolHaceSoloLoQueLePermiten() throws Exception {
        Tenant t = tenant(Plan.PRO);
        activate(t);
        String seller = TestAuth.bearer(sessions, member(t, "SELLER"));
        String viewer = TestAuth.bearer(sessions, member(t, "VIEWER"));
        mvc.perform(get("/invoicing/configuration").header("Authorization", seller)).andExpect(status().isForbidden());
        mvc.perform(put("/invoicing/series/1").header("Authorization", seller).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isForbidden());
        long id = body(emit(t, Map.of("orderId", order(t, 40), "type", "BOLETA"), seller).andExpect(status().isOk())).get("id").asLong();
        await(id, DocumentStatus.ACCEPTED);
        mvc.perform(post("/invoicing/documents/" + id + "/credit-notes").header("Authorization", seller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ANULACION\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/invoicing/documents/" + id).header("Authorization", viewer)).andExpect(status().isOk());
        emit(t, Map.of("orderId", order(t, 40), "type", "BOLETA"), viewer).andExpect(status().isForbidden());
    }

    // ─── Nota de crédito, reintentos, correo, enlace público ─────────────────

    @Test
    void laNotaDeCreditoAnulaElComprobanteAlSerAceptada() throws Exception {
        Tenant t = tenant(Plan.PRO);
        activate(t);
        long id = body(emitAttempt(t, order(t, 75), "BOLETA")).get("id").asLong();
        await(id, DocumentStatus.ACCEPTED);
        JsonNode note = body(mvc.perform(post("/invoicing/documents/" + id + "/credit-notes").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ANULACION\",\"description\":\"Pedido anulado\"}"))
                .andExpect(status().isOk()));
        assertThat(note.get("fullNumber").asString()).isEqualTo("BC01-00000001");
        assertThat(note.get("related").get("fullNumber").asString()).isEqualTo("B001-00000001");
        await(note.get("id").asLong(), DocumentStatus.ACCEPTED);
        assertThat(documents.findById(id).orElseThrow().getStatus()).isEqualTo(DocumentStatus.CANCELLED);
        mvc.perform(post("/invoicing/documents/" + id + "/credit-notes").header("Authorization", t.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ANULACION\"}")).andExpect(status().isConflict());
    }

    @Test
    void unaFallaDelProveedorSeReintentaSinCambiarElNumero() throws Exception {
        Tenant t = tenant(Plan.PRO);
        activate(t);
        doThrow(new ProviderException("Proveedor caído", false)).doCallRealMethod().when(sandbox).issue(any(), any());
        long id = body(emitAttempt(t, order(t, 33), "BOLETA")).get("id").asLong();
        ElectronicDocument failed = await(id, DocumentStatus.ERROR);
        assertThat(failed.getNextAttemptAt()).isAfter(LocalDateTime.now().plusSeconds(20));
        assertThat(failed.getLastError()).contains("Proveedor caído");

        ElectronicDocument doc = documents.findById(id).orElseThrow();
        doc.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        documents.save(doc);
        ElectronicDocument accepted = await(id, DocumentStatus.ACCEPTED);
        assertThat(accepted.getNumber()).isEqualTo(1L);
        assertThat(accepted.getAttempts()).isEqualTo(2);
        assertThat(events.findByDocumentIdAndCompanyIdOrderByCreatedAtAscIdAsc(id, t.company().getId()))
                .extracting(e -> e.getType().name()).contains("RETRY_SCHEDULED", "ACCEPTED");
    }

    @Test
    void elCorreoSaleConElPdfYReenviarNoVuelveAEmitir() throws Exception {
        Tenant t = tenant(Plan.PRO);
        activate(t);
        doReturn(true).when(email).sendWithAttachments(anyString(), any(), anyString(), anyString(), anyList());
        long orderId = storeOrder(t, Map.of("invoiceType", "BOLETA", "buyerDocumentType", "DNI", "buyerDocumentNumber", "45678912",
                "buyerEmail", "ana@cliente.test"));
        long id = body(emitAttempt(t, orderId, "BOLETA")).get("id").asLong();
        await(id, DocumentStatus.ACCEPTED);
        awaitEmail(id, EmailStatus.SENT);
        assertThat(documents.findById(id).orElseThrow().getCustomerDocumentNumber()).isEqualTo("45678912");

        mvc.perform(post("/invoicing/documents/" + id + "/resend-email").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"otro@cliente.test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullNumber").value("B001-00000001"));
        awaitEmail(id, EmailStatus.SENT);
        assertThat(documents.findByCompanyIdAndOrderIdOrderByIdDesc(t.company().getId(), orderId)).hasSize(1);
        assertThat(events.findByDocumentIdAndCompanyIdOrderByCreatedAtAscIdAsc(id, t.company().getId()))
                .extracting(e -> e.getType().name()).contains("EMAIL_SENT", "EMAIL_RESENT");
    }

    @Test
    void elEnlacePublicoMuestraLoMinimoYSePuedeRevocar() throws Exception {
        Tenant t = tenant(Plan.PRO);
        activate(t);
        long orderId = storeOrder(t, Map.of("invoiceType", "BOLETA", "buyerDocumentType", "DNI", "buyerDocumentNumber", "45678912",
                "buyerEmail", "ana@cliente.test"));
        long id = body(emitAttempt(t, orderId, "BOLETA")).get("id").asLong();
        await(id, DocumentStatus.ACCEPTED);
        String token = documents.findById(id).orElseThrow().getPublicToken();

        mvc.perform(get("/store/documents/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullNumber").value("B001-00000001"))
                .andExpect(jsonPath("$.customerDocument").value("DNI *****912"))
                .andExpect(jsonPath("$.customerEmail").doesNotExist());
        mvc.perform(get("/store/documents/" + token + "/pdf")).andExpect(status().isOk());
        mvc.perform(get("/store/documents/" + id)).andExpect(status().isNotFound());

        mvc.perform(post("/invoicing/documents/" + id + "/public-link/revoke").header("Authorization", t.token())).andExpect(status().isOk());
        mvc.perform(get("/store/documents/" + token)).andExpect(status().isNotFound());
        String fresh = documents.findById(id).orElseThrow().getPublicToken();
        mvc.perform(get("/store/documents/" + fresh)).andExpect(status().isOk());
    }

    // ─── Checkout y emisión automática ───────────────────────────────────────

    @Test
    void laFacturaPedidaAlComprarSeEmiteSolaAlConfirmarElPago() throws Exception {
        Tenant t = tenant(Plan.PRO);
        activate(t);
        mvc.perform(put("/invoicing/configuration").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"automaticIssuing\":true,\"issueTrigger\":\"PAYMENT_CONFIRMED\"}")).andExpect(status().isOk());
        mvc.perform(get("/store/" + t.company().getId() + "/info"))
                .andExpect(jsonPath("$.invoicing.receipt").value(true))
                .andExpect(jsonPath("$.invoicing.invoice").value(true))
                .andExpect(jsonPath("$.invoicing.test").value(true));

        storeOrderAttempt(t, Map.of("invoiceType", "FACTURA", "buyerDocumentType", "RUC", "buyerDocumentNumber", "20123456780",
                "buyerLegalName", "Cliente SAC")).andExpect(status().isBadRequest());
        long orderId = storeOrder(t, Map.of("invoiceType", "FACTURA", "buyerDocumentType", "RUC", "buyerDocumentNumber", "20123456786",
                "buyerLegalName", "Cliente SAC", "buyerFiscalAddress", "Jr. Unión 100, Lima"));
        assertThat(orders.findById(orderId).orElseThrow().getBuyerDocumentNumber()).isEqualTo("20123456786");

        mvc.perform(post("/payments").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("orderId", orderId, "amount", 25.0, "status", "APPROVED", "method", "yape"))))
                .andExpect(status().isOk());
        ElectronicDocument doc = null;
        for (int i = 0; i < 50 && doc == null; i++) {
            doc = documents.findByCompanyIdAndOrderIdOrderByIdDesc(t.company().getId(), orderId).stream().findFirst().orElse(null);
            if (doc == null) Thread.sleep(100);
        }
        assertThat(doc).isNotNull();
        assertThat(doc.getSeries()).isEqualTo("F001");
        assertThat(doc.getCustomerName()).isEqualTo("CLIENTE SAC");
        assertThat(doc.getCustomerAddress()).isEqualTo("Jr. Unión 100, Lima");
        await(doc.getId(), DocumentStatus.ACCEPTED);
    }

    // ─── Webhook ─────────────────────────────────────────────────────────────

    @Test
    void elWebhookExigeFirmaYNoSeAplicaDosVeces() throws Exception {
        Tenant t = tenant(Plan.PRO);
        activate(t);
        doReturn(new ProviderResult(ProviderResult.Outcome.PROCESSING, "SBX-1", "Esperando a SUNAT", null, null, null, null, null))
                .when(sandbox).issue(any(), any());
        long id = body(emitAttempt(t, order(t, 18), "BOLETA")).get("id").asLong();
        await(id, DocumentStatus.PROCESSING);

        String body = json.writeValueAsString(Map.of("eventId", "evt-" + id, "ruc", RUC, "series", "B001", "number", 1,
                "status", "ACCEPTED", "message", "Aceptado por SUNAT"));
        long now = Instant.now().getEpochSecond();
        webhook(body, "t=" + now + ",v1=" + "0".repeat(64)).andExpect(status().isUnauthorized());
        webhook(body, sign(body, now - 900)).andExpect(status().isUnauthorized());
        webhook(body, null).andExpect(status().isUnauthorized());

        webhook(body, sign(body, now)).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        webhook(body, sign(body, now)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        assertThat(documents.findById(id).orElseThrow().getStatus()).isEqualTo(DocumentStatus.ACCEPTED);
    }

    // ─── Apoyo ───────────────────────────────────────────────────────────────

    record Tenant(Company company, User owner, String token) {}

    private Tenant tenant(Plan plan) {
        String id = UUID.randomUUID().toString().substring(0, 8) + SEQ.incrementAndGet();
        Company company = Company.builder().name("Bodega " + id).slug("inv-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company.setPlan(plan);
        if (plan != Plan.FREE) company.setPlanExpiresAt(LocalDateTime.now().plusDays(30));
        company = companies.save(company);
        User owner = User.builder().fullName("Dueña " + id).email("inv-" + id + "@fluxy.invalid")
                .password("x").role(Role.BUSINESS_OWNER).company(company).build();
        owner.setStatus(User.Status.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = users.save(owner);
        memberships.save(new Membership(owner.getId(), company.getId(), "OWNER"));
        return new Tenant(company, owner, TestAuth.bearer(sessions, owner));
    }

    private User member(Tenant t, String role) {
        String id = UUID.randomUUID().toString().substring(0, 8) + SEQ.incrementAndGet();
        User user = User.builder().fullName(role + " " + id).email(role.toLowerCase() + "-" + id + "@fluxy.invalid")
                .password("x").role(Role.TEAM_MEMBER).company(t.company()).build();
        user.setStatus(User.Status.ACTIVE);
        user = users.save(user);
        memberships.save(new Membership(user.getId(), t.company().getId(), role));
        return user;
    }

    /** Modo de prueba: dirección fiscal, RUC, prueba de conexión y activación. */
    void activate(Tenant t) throws Exception {
        mvc.perform(put("/invoicing/configuration").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fiscalAddress\":\"Av. Siempre Viva 123, Lima\",\"taxRegime\":\"MYPE\"}")).andExpect(status().isOk());
        mvc.perform(post("/invoicing/configuration/verify-ruc").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruc\":\"" + RUC + "\",\"businessName\":\"Bodega Segura SAC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.verificationStatus").value("VERIFIED"));
        mvc.perform(post("/invoicing/configuration/test").header("Authorization", t.token()))
                .andExpect(jsonPath("$.connectionOk").value(true));
        mvc.perform(post("/invoicing/configuration/activate").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /** Pedido desde el panel con líneas (precio, cantidad, precio, cantidad...). */
    private long order(Tenant t, double... priceAndQuantity) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        if (priceAndQuantity.length == 1) priceAndQuantity = new double[]{priceAndQuantity[0], 1};
        for (int i = 0; i < priceAndQuantity.length; i += 2) {
            Prodcut p = products.save(Prodcut.builder().name("Producto " + SEQ.incrementAndGet()).price(priceAndQuantity[i])
                    .stock(100).owner(t.owner()).company(t.company()).build());
            items.add(Map.of("productId", p.getId(), "quantity", (int) priceAndQuantity[i + 1]));
        }
        return body(mvc.perform(post("/orders").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("customerName", "Ana Pérez", "customerPhone", "987654321", "items", items))))
                .andExpect(status().isOk())).get("id").asLong();
    }

    private ResultActions storeOrderAttempt(Tenant t, Map<String, Object> extra) throws Exception {
        Prodcut p = products.save(Prodcut.builder().name("Tienda " + SEQ.incrementAndGet()).price(25).stock(100)
                .owner(t.owner()).company(t.company()).build());
        Map<String, Object> body = new HashMap<>(extra);
        body.put("customerName", "Ana Pérez");
        body.put("customerPhone", "987654321");
        body.put("items", List.of(Map.of("productId", p.getId(), "quantity", 1)));
        return mvc.perform(post("/store/" + t.company().getId() + "/order").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private long storeOrder(Tenant t, Map<String, Object> extra) throws Exception {
        return body(storeOrderAttempt(t, extra).andExpect(status().isOk())).get("orderId").asLong();
    }

    private ResultActions emitAttempt(Tenant t, long orderId, String type) throws Exception {
        return emit(t, Map.of("orderId", orderId, "type", type), null);
    }

    private ResultActions emitAttempt(Tenant t, long orderId, String type, String key) throws Exception {
        return mvc.perform(post("/invoicing/documents").header("Authorization", t.token()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("orderId", orderId, "type", type))));
    }

    private ResultActions emit(Tenant t, Map<String, Object> body, String token) throws Exception {
        return mvc.perform(post("/invoicing/documents").header("Authorization", token == null ? t.token() : token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions webhook(String body, String signature) throws Exception {
        var request = post("/webhooks/invoicing/sandbox").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) request.header("X-Fluxy-Signature", signature);
        return mvc.perform(request);
    }

    private static String sign(String body, long timestamp) {
        return "t=" + timestamp + ",v1=" + Hashing.hmacSha256("test-invoicing-webhook-secret".getBytes(StandardCharsets.UTF_8),
                timestamp + "." + body);
    }

    private ElectronicDocument await(long id, DocumentStatus expected) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            processor.processDue();
            ElectronicDocument doc = documents.findById(id).orElseThrow();
            if (doc.getStatus() == expected) return doc;
            Thread.sleep(100);
        }
        fail("El comprobante " + id + " no llegó a " + expected + ": está " + documents.findById(id).orElseThrow().getStatus());
        return null;
    }

    private void awaitEmail(long id, EmailStatus expected) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            emailWorker.processDue();
            if (documents.findById(id).orElseThrow().getEmailStatus() == expected) return;
            Thread.sleep(100);
        }
        fail("El correo del comprobante " + id + " no llegó a " + expected);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    InvoicingConfiguration config(Tenant t) {
        return configurations.findByCompanyId(t.company().getId()).orElseThrow();
    }
}
