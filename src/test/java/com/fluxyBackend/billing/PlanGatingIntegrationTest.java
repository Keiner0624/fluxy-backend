package com.fluxyBackend.billing;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.Coupon;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.CouponRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Equipo y cupones son del plan Pro: se valida en el servidor, no solo en el panel. */
@SpringBootTest
@AutoConfigureMockMvc
class PlanGatingIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private SessionService sessions;
    @Autowired private RateLimitService rateLimits;
    @Autowired private CompanyRepository companies;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;
    @Autowired private CouponRepository coupons;

    @BeforeEach
    void setUp() {
        rateLimits.reset();
    }

    @Test
    void enFreeNoSeInvitaAlEquipoPeroSeGestionaAQuienYaEsta() throws Exception {
        Tenant t = tenant(Plan.FREE);
        mvc.perform(post("/team/invitations").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nuevo-" + uid() + "@fluxy.invalid\",\"role\":\"SELLER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_REQUIRED"))
                .andExpect(jsonPath("$.details.feature").value("TEAM"))
                .andExpect(jsonPath("$.details.requiredPlan").value("PRO"));

        // Alguien que ya estaba (de cuando tenía Pro) se puede seguir editando o suspendiendo.
        User seller = member(t, "SELLER");
        mvc.perform(get("/team").header("Authorization", t.token())).andExpect(status().isOk());
        mvc.perform(patch("/team/members/" + seller.getId()).header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void enProSeInvitaYSiBajaAFreeLaInvitacionYaNoSeAcepta() throws Exception {
        Tenant t = tenant(Plan.PRO);
        String email = "inv-" + uid() + "@fluxy.invalid";
        String acceptUrl = json.readTree(mvc.perform(post("/team/invitations").header("Authorization", t.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"role\":\"SELLER\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("acceptUrl").asString();
        String token = acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);

        Company company = companies.findById(t.company().getId()).orElseThrow();
        company.setPlan(Plan.FREE);
        company.setPlanExpiresAt(null);
        companies.save(company);

        mvc.perform(post("/auth/invitations/" + token + "/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Nueva Persona\",\"password\":\"Fluxy-Prueba-2026!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
        assertThat(users.existsByEmailIgnoreCase(email)).isFalse();
    }

    @Test
    void enFreeNoSeCreanNiActivanCuponesPeroSeDesactivanYBorran() throws Exception {
        Tenant t = tenant(Plan.FREE);
        mvc.perform(post("/coupons").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"FREE10\",\"discountType\":\"PERCENTAGE\",\"discountValue\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_REQUIRED"))
                .andExpect(jsonPath("$.details.feature").value("COUPONS"));
        assertThat(coupons.findByCompany(t.company())).isEmpty();

        // Cupones creados cuando tenía Pro: se ven, se apagan y se borran, pero no se vuelven a activar.
        Coupon active = coupon(t, "VIEJO5", true);
        Coupon inactive = coupon(t, "OFF5", false);
        mvc.perform(get("/coupons").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(put("/coupons/" + active.getId() + "/toggle").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(put("/coupons/" + inactive.getId() + "/toggle").header("Authorization", t.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
        mvc.perform(delete("/coupons/" + inactive.getId()).header("Authorization", t.token()))
                .andExpect(status().isOk());
    }

    @Test
    void enProSeCreanYActivanCupones() throws Exception {
        Tenant t = tenant(Plan.PRO);
        long id = json.readTree(mvc.perform(post("/coupons").header("Authorization", t.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"PRO10\",\"discountType\":\"PERCENTAGE\",\"discountValue\":10}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(put("/coupons/" + id + "/toggle").header("Authorization", t.token()))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(put("/coupons/" + id + "/toggle").header("Authorization", t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private record Tenant(Company company, User owner, String token) {}

    private Tenant tenant(Plan plan) {
        String id = uid();
        Company company = Company.builder().name("Tienda " + id).slug("plan-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company.setPlan(plan);
        if (plan != Plan.FREE) company.setPlanExpiresAt(LocalDateTime.now().plusDays(30));
        company = companies.save(company);
        User owner = User.builder().fullName("Dueño " + id).email("plan-" + id + "@fluxy.invalid")
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

    private Coupon coupon(Tenant t, String code, boolean active) {
        return coupons.save(Coupon.builder().code(code).discountType(Coupon.DiscountType.FIXED).discountValue(5.0)
                .company(t.company()).active(active).usageCount(0).build());
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8) + SEQ.incrementAndGet();
    }
}
