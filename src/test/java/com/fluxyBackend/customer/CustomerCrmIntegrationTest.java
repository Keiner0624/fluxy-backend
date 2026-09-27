package com.fluxyBackend.customer;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.Customer;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRM ligero: métricas, segmentos, origen, actividad, filtros, permisos, aislamiento y audiencias. */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerCrmIntegrationTest {

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

    @BeforeEach
    void setUp() {
        rateLimits.reset();
    }

    @Test
    void perfilConMétricasSegmentosPreferenciasYLíneaDeTiempo() throws Exception {
        Tenant t = tenant(Plan.PRO);
        Prodcut latte = product(t, "Latte", 12);
        Prodcut torta = product(t, "Torta", 30);
        long first = order(t, latte, 3, "Ana", "987654321");
        long second = order(t, torta, 1, "Ana", "987 654 321");
        long customerId = customerOf(first);

        mvc.perform(patch("/orders/" + first + "/status").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isOk());

        JsonNode detail = body(mvc.perform(get("/customers/" + customerId).header("Authorization", t.token()))
                .andExpect(status().isOk()));
        assertThat(detail.get("ordersCount").asLong()).isEqualTo(2);
        assertThat(detail.get("source").asString()).isEqualTo("ONLINE_STORE");
        assertThat(detail.get("sourceLabel").asString()).isEqualTo("Tienda online");
        assertThat(detail.get("daysSinceLastPurchase").asLong()).isZero();
        assertThat(types(detail.get("segments"))).containsExactlyInAnyOrder("NEW", "RECURRING");
        assertThat(detail.at("/segments/0/rule").asString()).isNotBlank();
        assertThat(detail.at("/preferences/mostPurchasedProduct").asString()).isEqualTo("Latte");
        assertThat(detail.at("/preferences/totalProductsPurchased").asLong()).isEqualTo(4);
        assertThat(detail.get("notesVisible").asBoolean()).isTrue();

        JsonNode activity = body(mvc.perform(get("/customers/" + customerId + "/activity?size=2")
                .header("Authorization", t.token())).andExpect(status().isOk()));
        assertThat(activity.get("totalElements").asLong()).isEqualTo(4); // alta, 2 pedidos, entrega
        assertThat(activity.get("content").size()).isEqualTo(2);
        List<String> all = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            JsonNode p = body(mvc.perform(get("/customers/" + customerId + "/activity?size=2&page=" + page)
                    .header("Authorization", t.token())));
            p.get("content").forEach(a -> all.add(a.get("type").asString()));
        }
        assertThat(all).containsExactlyInAnyOrder("CUSTOMER_CREATED", "ORDER_CREATED", "ORDER_CREATED", "ORDER_DELIVERED");

        JsonNode history = body(mvc.perform(get("/customers/" + customerId + "/orders?size=1")
                .header("Authorization", t.token())).andExpect(status().isOk()));
        assertThat(history.get("totalElements").asLong()).isEqualTo(2);
        assertThat(history.at("/content/0/id").asLong()).isEqualTo(second);

        mvc.perform(get("/customers/" + customerId + "/segments").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void clienteSinPedidosYEtiquetasYNotasQuedanEnLaActividad() throws Exception {
        Tenant t = tenant(Plan.PRO);
        JsonNode created = body(mvc.perform(post("/customers").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Beto\",\"phone\":\"911222333\"}"))
                .andExpect(status().isOk()));
        long id = created.get("id").asLong();
        assertThat(created.get("source").asString()).isEqualTo("MANUAL");
        assertThat(created.get("totalSpent").asDouble()).isZero();
        assertThat(created.get("averageTicket").asDouble()).isZero();
        assertThat(created.get("segments").size()).isZero();
        assertThat(created.get("daysSinceLastPurchase").isNull()).isTrue();
        assertThat(created.at("/preferences/totalProductsPurchased").asLong()).isZero();

        mvc.perform(put("/customers/" + id).header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"VIP\",\"mayorista\"],\"notes\":\"Paga por Yape\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("Paga por Yape"));
        mvc.perform(put("/customers/" + id).header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"vip\"]}"))
                .andExpect(status().isOk());

        JsonNode activity = body(mvc.perform(get("/customers/" + id + "/activity").header("Authorization", t.token())));
        List<String> types = new ArrayList<>();
        activity.get("content").forEach(a -> types.add(a.get("type").asString()));
        assertThat(types).containsExactlyInAnyOrder("CUSTOMER_CREATED", "TAG_ADDED", "TAG_ADDED", "NOTE_CREATED", "TAG_REMOVED");
        assertThat(activity.at("/content/0/actor").asString()).startsWith("Dueño");
    }

    @Test
    void filtrosDeListadoPorSegmentoOrigenMarketingEtiquetaYFecha() throws Exception {
        Tenant t = tenant(Plan.PRO);
        Prodcut p = product(t, "Café", 10);
        long recent = customerOf(order(t, p, 1, "Reciente", "900000001"));
        long old = customerOf(order(t, p, 40, "Antiguo", "900000002"));
        Order oldOrder = orders.findByCompanyIdAndCustomer_Id(t.company().getId(), old,
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0);
        oldOrder.setCreatedAt(LocalDateTime.now().minusDays(60));
        orders.save(oldOrder);
        Customer optedOut = customers.findById(old).orElseThrow();
        optedOut.setMarketingOptOut(true);
        optedOut.setTags("vip");
        customers.save(optedOut);
        long manual = body(mvc.perform(post("/customers").header("Authorization", t.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Sin compras\"}"))).get("id").asLong();

        assertThat(ids(t, "")).containsExactlyInAnyOrder(recent, old, manual);
        assertThat(ids(t, "segment=INACTIVE")).containsExactly(old);
        assertThat(ids(t, "segment=HIGH_VALUE")).containsExactly(old);
        assertThat(ids(t, "segment=new")).containsExactly(recent);
        assertThat(ids(t, "source=MANUAL")).containsExactly(manual);
        assertThat(ids(t, "source=ONLINE_STORE")).containsExactlyInAnyOrder(recent, old);
        assertThat(ids(t, "marketingAllowed=false")).containsExactly(old);
        assertThat(ids(t, "marketingAllowed=true")).containsExactlyInAnyOrder(recent, manual);
        assertThat(ids(t, "tag=VIP")).containsExactly(old);
        String today = LocalDate.now().toString();
        assertThat(ids(t, "lastPurchaseFrom=" + today)).containsExactly(recent);
        assertThat(ids(t, "lastPurchaseTo=" + LocalDate.now().minusDays(30))).containsExactly(old);

        JsonNode page = body(mvc.perform(get("/customers?size=1&sort=name&direction=asc").header("Authorization", t.token())));
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.at("/content/0/name").asString()).isEqualTo("Antiguo");
        assertThat(page.at("/content/0/segments").toString()).contains("INACTIVE");

        mvc.perform(get("/customers?segment=OTRO").header("Authorization", t.token())).andExpect(status().isBadRequest());
    }

    @Test
    void aislamientoEntreEmpresas() throws Exception {
        Tenant a = tenant(Plan.PRO);
        Tenant b = tenant(Plan.PRO);
        long customerId = customerOf(order(a, product(a, "Pan", 3), 1, "Carla", "955000111"));

        mvc.perform(get("/customers/" + customerId).header("Authorization", b.token())).andExpect(status().isNotFound());
        mvc.perform(get("/customers/" + customerId + "/activity").header("Authorization", b.token())).andExpect(status().isNotFound());
        mvc.perform(get("/customers/" + customerId + "/orders").header("Authorization", b.token())).andExpect(status().isNotFound());
        mvc.perform(get("/customers/" + customerId + "/segments").header("Authorization", b.token())).andExpect(status().isNotFound());
        mvc.perform(put("/customers/" + customerId).header("Authorization", b.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"x\"}")).andExpect(status().isNotFound());
        assertThat(ids(b, "")).isEmpty();
    }

    @Test
    void permisosDeCrearNotasYEtiquetasSeValidanEnElServidor() throws Exception {
        Tenant t = tenant(Plan.PRO);
        long customerId = customerOf(order(t, product(t, "Té", 5), 1, "Dora", "966000222"));
        mvc.perform(put("/customers/" + customerId).header("Authorization", t.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"Cliente delicado\",\"tags\":[\"vip\"]}"))
                .andExpect(status().isOk());

        String viewer = token(member(t, "VIEWER", null));
        mvc.perform(get("/customers/" + customerId).header("Authorization", viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").doesNotExist())
                .andExpect(jsonPath("$.notesVisible").value(false));
        mvc.perform(post("/customers").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\"}")).andExpect(status().isForbidden());

        String seller = token(member(t, "SELLER", null));
        mvc.perform(post("/customers").header("Authorization", seller).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nuevo por vendedor\"}")).andExpect(status().isOk());

        // Personalizado: puede editar datos pero no notas ni etiquetas.
        String limited = token(member(t, "SELLER", "CUSTOMER_VIEW,CUSTOMER_UPDATE"));
        mvc.perform(get("/customers/" + customerId).header("Authorization", limited))
                .andExpect(jsonPath("$.notes").doesNotExist());
        mvc.perform(put("/customers/" + customerId).header("Authorization", limited).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notes\":\"otra\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/customers/" + customerId).header("Authorization", limited).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[]}")).andExpect(status().isForbidden());
        mvc.perform(post("/customers").header("Authorization", limited).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Y\"}")).andExpect(status().isForbidden());
        // Reenviar las mismas etiquetas junto con otro cambio no cuenta como editarlas.
        mvc.perform(put("/customers/" + customerId).header("Authorization", limited).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dora@example.com\",\"tags\":[\"vip\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dora@example.com"))
                .andExpect(jsonPath("$.notes").doesNotExist());
        assertThat(customers.findById(customerId).orElseThrow().getNotes()).isEqualTo("Cliente delicado");
    }

    @Test
    void audienciasDeMarketingPorEtiquetaOrigenYProductoSinBajas() throws Exception {
        Tenant t = tenant(Plan.BUSINESS);
        Prodcut mug = product(t, "Taza", 20);
        Prodcut pan = product(t, "Pan", 2);
        long ana = customerOf(order(t, mug, 1, "Ana", "977000001"));
        long beto = customerOf(order(t, mug, 1, "Beto", "977000002"));
        long caro = customerOf(order(t, pan, 1, "Caro", "977000003"));
        for (long id : List.of(ana, beto)) {
            Customer c = customers.findById(id).orElseThrow();
            c.setTags("vip");
            customers.save(c);
        }
        Customer optedOut = customers.findById(beto).orElseThrow();
        optedOut.setMarketingOptOut(true);
        customers.save(optedOut);

        assertThat(audience(t, "TAG", "value=VIP")).containsExactly(ana);
        assertThat(audience(t, "SOURCE", "value=online_store")).containsExactlyInAnyOrder(ana, caro);
        assertThat(audience(t, "PRODUCT_BUYERS", "value=" + mug.getId())).containsExactly(ana);
        assertThat(audience(t, "RECURRING", "")).isEmpty();
        assertThat(audience(t, "NEW", "")).containsExactlyInAnyOrder(ana, caro);

        JsonNode segments = body(mvc.perform(get("/marketing/segments").header("Authorization", t.token())));
        for (JsonNode s : segments) {
            if ("TAG".equals(s.get("key").asString())) assertThat(s.get("param").asString()).isEqualTo("TAG");
            if ("NEW".equals(s.get("key").asString())) assertThat(s.get("customers").asLong()).isEqualTo(2);
        }

        long campaign = body(mvc.perform(post("/marketing/campaigns").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "name", "VIPs", "type", "STORE", "channel", "WHATSAPP", "segment", "TAG", "segmentValue", " VIP "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segmentValue").value("vip"))).get("id").asLong();
        assertThat(campaign).isPositive();
        mvc.perform(get("/marketing/segments/PRODUCT_BUYERS/customers?value=999999").header("Authorization", t.token()))
                .andExpect(status().isBadRequest());
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private record Tenant(Company company, User owner, String token) {}

    private Tenant tenant(Plan plan) {
        String id = uid();
        Company company = Company.builder().name("Tienda " + id).slug("crm-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company.setPlan(plan);
        if (plan != Plan.FREE) company.setPlanExpiresAt(LocalDateTime.now().plusDays(30));
        company = companies.save(company);
        User owner = User.builder().fullName("Dueño " + id).email("crm-" + id + "@fluxy.invalid")
                .password("x").role(Role.BUSINESS_OWNER).company(company).build();
        owner.setStatus(User.Status.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = users.save(owner);
        memberships.save(new Membership(owner.getId(), company.getId(), "OWNER"));
        return new Tenant(company, owner, TestAuth.bearer(sessions, owner));
    }

    private User member(Tenant t, String role, String customPermissions) {
        String id = uid();
        User user = User.builder().fullName(role + " " + id).email(role.toLowerCase() + "-" + id + "@fluxy.invalid")
                .password("x").role(Role.TEAM_MEMBER).company(t.company()).build();
        user.setStatus(User.Status.ACTIVE);
        user = users.save(user);
        Membership membership = new Membership(user.getId(), t.company().getId(), role);
        membership.setPermissions(customPermissions);
        memberships.save(membership);
        return user;
    }

    private String token(User user) {
        return TestAuth.bearer(sessions, user);
    }

    private Prodcut product(Tenant t, String name, double price) {
        return products.save(Prodcut.builder().name(name).price(price).stock(100).owner(t.owner()).company(t.company()).build());
    }

    private long order(Tenant t, Prodcut p, int quantity, String name, String phone) throws Exception {
        Map<String, Object> body = Map.of("customerName", name, "customerPhone", phone,
                "items", List.of(Map.of("productId", p.getId(), "quantity", quantity)));
        return body(mvc.perform(post("/store/" + t.company().getId() + "/order").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isOk())).get("orderId").asLong();
    }

    private long customerOf(long orderId) {
        return orders.findById(orderId).orElseThrow().getCustomerId();
    }

    private List<Long> ids(Tenant t, String query) throws Exception {
        JsonNode page = body(mvc.perform(get("/customers?size=100&" + query).header("Authorization", t.token()))
                .andExpect(status().isOk()));
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(c -> ids.add(c.get("id").asLong()));
        return ids;
    }

    private List<Long> audience(Tenant t, String key, String query) throws Exception {
        JsonNode list = body(mvc.perform(get("/marketing/segments/" + key + "/customers?" + query)
                .header("Authorization", t.token())).andExpect(status().isOk()));
        List<Long> ids = new ArrayList<>();
        list.forEach(c -> ids.add(c.get("id").asLong()));
        return ids;
    }

    private static List<String> types(JsonNode segments) {
        List<String> types = new ArrayList<>();
        segments.forEach(s -> types.add(s.get("type").asString()));
        return types;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8) + SEQ.incrementAndGet();
    }
}
