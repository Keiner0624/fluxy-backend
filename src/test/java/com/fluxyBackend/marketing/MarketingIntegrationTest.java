package com.fluxyBackend.marketing;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.Coupon;
import com.fluxyBackend.entity.Customer;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.marketing.entity.MarketingCampaign;
import com.fluxyBackend.marketing.enums.CampaignStatus;
import com.fluxyBackend.marketing.repository.MarketingCampaignRepository;
import com.fluxyBackend.marketing.service.CampaignService;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.CouponRepository;
import com.fluxyBackend.repository.CustomerRepository;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.OrderRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Marketing: criterios de aceptación del documento (tenant, ciclo de vida, tracking, pedidos, permisos, fechas, plan). */
@SpringBootTest
@AutoConfigureMockMvc
class MarketingIntegrationTest {

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
    @Autowired private CustomerRepository customers;
    @Autowired private CouponRepository coupons;
    @Autowired private MarketingCampaignRepository campaigns;
    @Autowired private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        rateLimits.reset();
    }

    // ─── Ciclo completo ──────────────────────────────────────────────────────

    @Test
    void campañaEnlaceVisitaPedidoYVentaAtribuida() throws Exception {
        Tenant t = tenant(Plan.PRO);
        Prodcut latte = product(t, "Latte", 12);
        long id = createCampaign(t, Map.of("name", "Latte finde", "type", "PRODUCT", "targetId", latte.getId(), "channel", "WHATSAPP"));
        mvc.perform(post("/marketing/campaigns/" + id + "/activate").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.acceptingAttribution").value(true));

        JsonNode link = body(mvc.perform(post("/marketing/campaigns/" + id + "/links").header("Authorization", t.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()));
        String code = code(t, id);
        assertThat(link.get("url").asString()).contains("/store/" + t.company().getSlug())
                .contains("producto=" + latte.getId()).contains("cmp=" + code).contains("utm_source=whatsapp");
        assertThat(link.get("shareUrl").asString()).startsWith("https://wa.me/?text=");
        assertThat(link.get("autoPublish").asBoolean()).isFalse();

        String session = session();
        track(t, "VIEW", code, session, null, "whatsapp")
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.recorded").value(true))
                .andExpect(jsonPath("$.target.type").value("PRODUCT"))
                .andExpect(jsonPath("$.target.id").value(latte.getId()));
        // La misma visita el mismo día no se cuenta dos veces.
        track(t, "VIEW", code, session, null, "whatsapp").andExpect(jsonPath("$.recorded").value(false));
        track(t, "PRODUCT_VIEW", code, session, latte.getId(), null).andExpect(jsonPath("$.recorded").value(true));
        track(t, "ADD_TO_CART", code, session, latte.getId(), null).andExpect(jsonPath("$.recorded").value(true));
        track(t, "CHECKOUT_STARTED", code, session, null, null).andExpect(jsonPath("$.recorded").value(true));

        long attributed = order(t, latte, 2, session, null);
        order(t, latte, 1, null, null); // sin enlace: no suma

        JsonNode analytics = analytics(t, id);
        assertThat(analytics.at("/totals/visits").asLong()).isEqualTo(1);
        assertThat(analytics.at("/totals/orders").asLong()).isEqualTo(1);
        assertThat(analytics.at("/totals/pendingSales").asDouble()).isEqualTo(24.0);
        assertThat(analytics.at("/totals/sales").asDouble()).isZero();
        assertThat(analytics.at("/full").asBoolean()).isTrue();
        assertThat(analytics.at("/funnel").size()).isEqualTo(5);
        assertThat(analytics.at("/funnel/4/count").asLong()).isEqualTo(1);
        assertThat(analytics.at("/channels/0/channel").asString()).isEqualTo("WHATSAPP");
        assertThat(analytics.at("/products/0/name").asString()).isEqualTo("Latte");

        // Confirmado pasa a venta; cancelado deja de sumar.
        setStatus(attributed, OrderStatus.CONFIRMED);
        analytics = analytics(t, id);
        assertThat(analytics.at("/totals/sales").asDouble()).isEqualTo(24.0);
        assertThat(analytics.at("/totals/conversionRate").asDouble()).isEqualTo(1.0);
        setStatus(attributed, OrderStatus.CANCELLED);
        analytics = analytics(t, id);
        assertThat(analytics.at("/totals/orders").asLong()).isZero();
        assertThat(analytics.at("/totals/cancelledOrders").asLong()).isEqualTo(1);

        mvc.perform(get("/marketing/campaigns").header("Authorization", t.token()))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].targetName").value("Latte"))
                .andExpect(jsonPath("$[0].metrics.visits").value(1));
    }

    // ─── Tenant ──────────────────────────────────────────────────────────────

    @Test
    void unaEmpresaNoVeNiUsaLasCampañasDeOtra() throws Exception {
        Tenant a = tenant(Plan.PRO);
        Tenant b = tenant(Plan.PRO);
        long id = createCampaign(a, Map.of("name", "Solo A", "type", "STORE", "channel", "DIRECT"));
        activate(a, id).andExpect(status().isOk());
        String code = code(a, id);

        mvc.perform(get("/marketing/campaigns/" + id).header("Authorization", b.token())).andExpect(status().isNotFound());
        mvc.perform(patch("/marketing/campaigns/" + id).header("Authorization", b.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Robada\"}")).andExpect(status().isNotFound());
        activate(b, id).andExpect(status().isNotFound());
        mvc.perform(post("/marketing/campaigns/" + id + "/pause").header("Authorization", b.token())).andExpect(status().isNotFound());
        mvc.perform(get("/marketing/campaigns/" + id + "/analytics").header("Authorization", b.token())).andExpect(status().isNotFound());
        mvc.perform(get("/marketing/campaigns").header("Authorization", b.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // El código de A en la tienda de B no se acepta, y un pedido en B no se atribuye a A.
        String session = session();
        track(b, "VIEW", code, session, null, null).andExpect(jsonPath("$.accepted").value(false));
        track(a, "VIEW", code, session, null, null).andExpect(jsonPath("$.accepted").value(true));
        order(b, product(b, "Té", 5), 1, session, null);
        assertThat(analytics(a, id).at("/totals/orders").asLong()).isZero();
        assertThat(campaigns.findById(id).orElseThrow().getName()).isEqualTo("Solo A");
    }

    // ─── Ciclo de vida ───────────────────────────────────────────────────────

    @Test
    void cicloDeVidaBorradorActivaPausadaFinalizadaArchivada() throws Exception {
        Tenant t = tenant(Plan.PRO);
        long id = createCampaign(t, Map.of("name", "Ciclo", "type", "STORE"));
        mvc.perform(get("/marketing/campaigns/" + id).header("Authorization", t.token()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.channel").value("DIRECT"))
                .andExpect(jsonPath("$.objective").value("VISITS"));
        mvc.perform(post("/marketing/campaigns/" + id + "/pause").header("Authorization", t.token())).andExpect(status().isConflict());
        activate(t, id).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/marketing/campaigns/" + id + "/pause").header("Authorization", t.token()))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.acceptingAttribution").value(false));
        activate(t, id).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(delete("/marketing/campaigns/" + id).header("Authorization", t.token())).andExpect(status().isConflict());
        mvc.perform(post("/marketing/campaigns/" + id + "/finish").header("Authorization", t.token()))
                .andExpect(jsonPath("$.status").value("FINISHED"));
        activate(t, id).andExpect(status().isConflict());
        mvc.perform(patch("/marketing/campaigns/" + id).header("Authorization", t.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Otra\"}")).andExpect(status().isConflict());
        mvc.perform(post("/marketing/campaigns/" + id + "/archive").header("Authorization", t.token()))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        long draft = createCampaign(t, Map.of("name", "Borrador", "type", "STORE"));
        mvc.perform(delete("/marketing/campaigns/" + draft).header("Authorization", t.token())).andExpect(status().isOk());
        assertThat(campaigns.findById(draft)).isEmpty();
    }

    @Test
    void lasFechasProgramanYCierranLaAtribucion() throws Exception {
        Tenant t = tenant(Plan.PRO);
        Map<String, Object> future = new HashMap<>(Map.of("name", "Programada", "type", "STORE"));
        future.put("startsAt", OffsetDateTime.now().plusDays(2).toString());
        long id = createCampaign(t, future);
        activate(t, id).andExpect(jsonPath("$.status").value("SCHEDULED"));
        String code = code(t, id);
        track(t, "VIEW", code, session(), null, null).andExpect(jsonPath("$.accepted").value(false));

        // Llega el inicio: la tarea la activa.
        MarketingCampaign campaign = campaigns.findById(id).orElseThrow();
        campaign.setStartsAt(LocalDateTime.now().minusMinutes(1));
        campaigns.save(campaign);
        campaignService.applyScheduledTransitions();
        assertThat(campaigns.findById(id).orElseThrow().getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        String session = session();
        track(t, "VIEW", code, session, null, null).andExpect(jsonPath("$.accepted").value(true));

        // Pasa la fecha de fin: aunque la tarea no haya corrido, ya no atribuye.
        campaign = campaigns.findById(id).orElseThrow();
        campaign.setEndsAt(LocalDateTime.now().minusMinutes(1));
        campaigns.save(campaign);
        track(t, "VIEW", code, session(), null, null).andExpect(jsonPath("$.accepted").value(false));
        order(t, product(t, "Pan", 3), 1, session, null);
        assertThat(analytics(t, id).at("/totals/orders").asLong()).isZero();
        campaignService.applyScheduledTransitions();
        assertThat(campaigns.findById(id).orElseThrow().getStatus()).isEqualTo(CampaignStatus.FINISHED);

        Map<String, Object> past = new HashMap<>(Map.of("name", "Vencida", "type", "STORE"));
        past.put("endsAt", OffsetDateTime.now().minusDays(1).toString());
        activate(t, createCampaign(t, past)).andExpect(status().isBadRequest());
    }

    // ─── Tracking ────────────────────────────────────────────────────────────

    @Test
    void losPasosSinVisitaPreviaOEnPausaNoSeCuentan() throws Exception {
        Tenant t = tenant(Plan.PRO);
        Prodcut p = product(t, "Queque", 8);
        long id = createCampaign(t, Map.of("name", "Queque", "type", "PRODUCT", "targetId", p.getId()));
        activate(t, id);
        String code = code(t, id);
        String session = session();
        track(t, "PRODUCT_VIEW", code, session, p.getId(), null).andExpect(jsonPath("$.accepted").value(false));
        track(t, "ORDER_COMPLETED", code, session, null, null).andExpect(status().isBadRequest());
        mvc.perform(post("/store/" + t.company().getId() + "/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"VIEW\",\"code\":\"x\",\"sessionId\":\"corta\"}")).andExpect(status().isBadRequest());

        track(t, "VIEW", code, session, null, null).andExpect(jsonPath("$.recorded").value(true));
        mvc.perform(post("/marketing/campaigns/" + id + "/pause").header("Authorization", t.token()));
        track(t, "ADD_TO_CART", code, session, p.getId(), null).andExpect(jsonPath("$.accepted").value(false));
        order(t, p, 1, session, null);
        assertThat(analytics(t, id).at("/totals/orders").asLong()).isZero();
    }

    // ─── Permisos ────────────────────────────────────────────────────────────

    @Test
    void sinPermisoNoSeCreaEditaNiPublica() throws Exception {
        Tenant t = tenant(Plan.PRO);
        long id = createCampaign(t, Map.of("name", "Equipo", "type", "STORE"));
        String seller = TestAuth.bearer(sessions, member(t, "SELLER"));
        mvc.perform(get("/marketing/campaigns").header("Authorization", seller)).andExpect(status().isOk());
        mvc.perform(post("/marketing/campaigns").header("Authorization", seller).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"type\":\"STORE\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/marketing/campaigns/" + id).header("Authorization", seller).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\"}")).andExpect(status().isForbidden());
        activate(seller, id).andExpect(status().isForbidden());
        mvc.perform(get("/marketing/campaigns/" + id + "/analytics").header("Authorization", seller)).andExpect(status().isForbidden());
        mvc.perform(get("/marketing/campaigns").header("Authorization", seller))
                .andExpect(jsonPath("$[0].metrics").value(org.hamcrest.Matchers.nullValue()));

        String manager = TestAuth.bearer(sessions, member(t, "MANAGER"));
        activate(manager, id).andExpect(status().isOk());
        mvc.perform(get("/marketing/campaigns/" + id + "/analytics").header("Authorization", manager)).andExpect(status().isOk());
        // Solo lectura ve las campañas pero no publica ni ve resultados (solo permisos *_VIEW).
        String viewer = TestAuth.bearer(sessions, member(t, "VIEWER"));
        mvc.perform(get("/marketing/campaigns/" + id).header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(get("/marketing/campaigns/" + id + "/analytics").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(post("/marketing/campaigns/" + id + "/pause").header("Authorization", viewer)).andExpect(status().isForbidden());
    }

    // ─── Plan ────────────────────────────────────────────────────────────────

    @Test
    void elPlanFreeLimitaCampañasYAnalitica() throws Exception {
        Tenant t = tenant(Plan.FREE);
        long first = createCampaign(t, Map.of("name", "Uno", "type", "STORE"));
        long second = createCampaign(t, Map.of("name", "Dos", "type", "STORE"));
        long third = createCampaign(t, Map.of("name", "Tres", "type", "STORE"));
        activate(t, first).andExpect(status().isOk());
        activate(t, second).andExpect(status().isOk());
        activate(t, third).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_LIMIT"));
        mvc.perform(post("/marketing/campaigns/" + first + "/pause").header("Authorization", t.token()));
        activate(t, third).andExpect(status().isOk());

        mvc.perform(get("/marketing/overview").header("Authorization", t.token()))
                .andExpect(jsonPath("$.liveCampaigns").value(2))
                .andExpect(jsonPath("$.liveCampaignLimit").value(2))
                .andExpect(jsonPath("$.capabilities.fullAnalytics").value(false));
        JsonNode analytics = analytics(t, third);
        assertThat(analytics.at("/full").asBoolean()).isFalse();
        assertThat(analytics.at("/funnel").size()).isZero();

        Coupon coupon = coupon(t, "FREE10");
        mvc.perform(post("/marketing/campaigns").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Cupón", "type", "COUPON", "couponId", coupon.getId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
        mvc.perform(get("/marketing/segments/FREQUENT/customers").header("Authorization", t.token()))
                .andExpect(status().isForbidden());
    }

    // ─── Cupones, segmentos y oportunidades ──────────────────────────────────

    @Test
    void laCampañaDeCuponSugiereElCodigoYMideSuUso() throws Exception {
        Tenant t = tenant(Plan.PRO);
        Prodcut p = product(t, "Torta", 50);
        Coupon coupon = coupon(t, "TORTA10");
        long id = createCampaign(t, Map.of("name", "Torta con cupón", "type", "COUPON", "couponId", coupon.getId(), "channel", "INSTAGRAM"));
        activate(t, id).andExpect(jsonPath("$.couponCode").value("TORTA10"));
        String session = session();
        track(t, "VIEW", code(t, id), session, null, "instagram").andExpect(jsonPath("$.couponCode").value("TORTA10"));
        order(t, p, 1, session, "TORTA10");
        order(t, p, 1, null, "TORTA10");

        JsonNode coupons = analytics(t, id).get("coupon");
        assertThat(coupons.get("code").asString()).isEqualTo("TORTA10");
        assertThat(coupons.get("orders").asLong()).isEqualTo(2);
        assertThat(coupons.get("attributedOrders").asLong()).isEqualTo(1);
        assertThat(coupons.get("discount").asDouble()).isEqualTo(10.0);
    }

    @Test
    void segmentosRespetanLaBajaYDetectanClientesInactivos() throws Exception {
        Tenant t = tenant(Plan.PRO);
        Prodcut p = product(t, "Café", 10);
        for (int i = 1; i <= 4; i++) order(t, p, 1, null, null, "Cliente " + i, "98765432" + i);
        for (Order o : orders.findByCompanyId(t.company().getId())) {
            o.setCreatedAt(LocalDateTime.now().minusDays(40));
            orders.save(o);
        }
        Customer optedOut = customers.findByCompanyId(t.company().getId()).stream()
                .filter(c -> "Cliente 4".equals(c.getName())).findFirst().orElseThrow();
        optedOut.setMarketingOptOut(true);
        customers.save(optedOut);

        JsonNode segments = body(mvc.perform(get("/marketing/segments").header("Authorization", t.token())).andExpect(status().isOk()));
        JsonNode inactive = find(segments, "INACTIVE_30");
        assertThat(inactive.get("customers").asLong()).isEqualTo(3);
        assertThat(inactive.get("reachable").asLong()).isEqualTo(3);
        assertThat(find(segments, "NEW").get("customers").asLong()).isZero();
        mvc.perform(get("/marketing/segments/INACTIVE_30/customers").header("Authorization", t.token()))
                .andExpect(jsonPath("$.length()").value(3));

        JsonNode opportunities = body(mvc.perform(get("/marketing/opportunities").header("Authorization", t.token())));
        JsonNode winBack = find(opportunities, "INACTIVE_CUSTOMERS");
        assertThat(winBack.at("/suggestion/segment").asString()).isEqualTo("INACTIVE_30");
        assertThat(winBack.at("/suggestion/objective").asString()).isEqualTo("WIN_BACK");
        assertThat(opportunities.size()).isLessThanOrEqualTo(4);
    }

    // ─── Apoyo ───────────────────────────────────────────────────────────────

    private record Tenant(Company company, User owner, String token) {}

    private Tenant tenant(Plan plan) {
        String id = uid();
        Company company = Company.builder().name("Tienda " + id).slug("mkt-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company.setPlan(plan);
        if (plan != Plan.FREE) company.setPlanExpiresAt(LocalDateTime.now().plusDays(30));
        company = companies.save(company);
        User owner = User.builder().fullName("Dueño " + id).email("mkt-" + id + "@fluxy.invalid")
                .password("x").role(Role.BUSINESS_OWNER).company(company).build();
        owner.setStatus(User.Status.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = users.save(owner);
        memberships.save(new Membership(owner.getId(), company.getId(), "OWNER"));
        return new Tenant(company, owner, TestAuth.bearer(sessions, owner));
    }

    private User member(Tenant t, String role) {
        String id = uid();
        User user = User.builder().fullName(role + " " + id).email(role.toLowerCase() + "-" + id + "@fluxy.invalid")
                .password("x").role(Role.TEAM_MEMBER).company(t.company()).build();
        user.setStatus(User.Status.ACTIVE);
        user = users.save(user);
        memberships.save(new Membership(user.getId(), t.company().getId(), role));
        return user;
    }

    private Prodcut product(Tenant t, String name, double price) {
        return products.save(Prodcut.builder().name(name).price(price).stock(50).owner(t.owner()).company(t.company()).build());
    }

    private Coupon coupon(Tenant t, String code) {
        return coupons.save(Coupon.builder().code(code).discountType(Coupon.DiscountType.FIXED).discountValue(5.0)
                .company(t.company()).active(true).usageCount(0).build());
    }

    private long createCampaign(Tenant t, Map<String, Object> body) throws Exception {
        return body(mvc.perform(post("/marketing/campaigns").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))).get("id").asLong();
    }

    private ResultActions activate(Tenant t, long id) throws Exception {
        return activate(t.token(), id);
    }

    private ResultActions activate(String token, long id) throws Exception {
        return mvc.perform(post("/marketing/campaigns/" + id + "/activate").header("Authorization", token));
    }

    private String code(Tenant t, long id) {
        return campaigns.findByIdAndCompanyId(id, t.company().getId()).orElseThrow().getTrackingCode();
    }

    private ResultActions track(Tenant t, String type, String code, String session, Long productId, String source) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", type, "code", code, "sessionId", session));
        if (productId != null) body.put("productId", productId);
        if (source != null) body.put("source", source);
        return mvc.perform(post("/store/" + t.company().getId() + "/events").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private long order(Tenant t, Prodcut p, int quantity, String session, String coupon) throws Exception {
        return order(t, p, quantity, session, coupon, "Ana", "987654321");
    }

    private long order(Tenant t, Prodcut p, int quantity, String session, String coupon, String name, String phone) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("customerName", name, "customerPhone", phone,
                "items", List.of(Map.of("productId", p.getId(), "quantity", quantity))));
        if (session != null) body.put("marketingSessionId", session);
        if (coupon != null) body.put("couponCode", coupon);
        return body(mvc.perform(post("/store/" + t.company().getId() + "/order").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isOk())).get("orderId").asLong();
    }

    private void setStatus(long orderId, OrderStatus status) {
        Order order = orders.findById(orderId).orElseThrow();
        order.setStatus(status);
        orders.save(order);
    }

    private JsonNode analytics(Tenant t, long id) throws Exception {
        return body(mvc.perform(get("/marketing/campaigns/" + id + "/analytics").header("Authorization", t.token()))
                .andExpect(status().isOk()));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static JsonNode find(JsonNode array, String key) {
        for (JsonNode node : array) {
            if (key.equals(node.get("key").asString())) return node;
        }
        throw new AssertionError("No está " + key + " en " + array);
    }

    private static String session() {
        return "s-" + UUID.randomUUID();
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8) + SEQ.incrementAndGet();
    }
}
