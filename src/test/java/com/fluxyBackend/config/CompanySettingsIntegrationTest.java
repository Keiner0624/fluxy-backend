package com.fluxyBackend.config;

import com.fluxyBackend.entity.Company;
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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lo que la validación acepta, la base lo guarda: una descripción o dirección larga no devuelve 409. */
@SpringBootTest
@AutoConfigureMockMvc
class CompanySettingsIntegrationTest {

    private static final String ABOUT = "Abrimos en 2019.\nTostamos nuestro propio café.";

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private SessionService sessions;
    @Autowired private RateLimitService rateLimits;
    @Autowired private CompanyRepository companies;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;
    @Autowired private ProductRepository products;

    @BeforeEach
    void setUp() {
        rateLimits.reset();
    }

    @Test
    void guardarLaConfiguracionConTextosLargosFunciona() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Company company = Company.builder().name("Cafetería " + id).slug("cfg-" + id).build();
        company.setStatus(Company.Status.ACTIVE);
        company.setLastBusinessActivityAt(LocalDateTime.now());
        company = companies.save(company);
        User owner = User.builder().fullName("Dueña " + id).email("cfg-" + id + "@fluxy.invalid")
                .password("x").role(Role.BUSINESS_OWNER).company(company).build();
        owner.setStatus(User.Status.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = users.save(owner);
        memberships.save(new Membership(owner.getId(), company.getId(), "OWNER"));

        String description = "Café recién preparado, postres y sándwiches. ".repeat(40).substring(0, 1500);
        String address = "Av. Larga ".repeat(29) + "123";
        String logo = "https://res.cloudinary.com/demo/image/upload/" + "a".repeat(300) + ".png";
        mvc.perform(put("/companies/config").header("Authorization", TestAuth.bearer(sessions, owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Cafetería", "description", description,
                                "aboutText", ABOUT,
                                "address", address, "logoUrl", logo, "paymentMethods", "[\"yape\"]"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(description));

        Company saved = companies.findById(company.getId()).orElseThrow();
        assertThat(saved.getDescription()).hasSize(1500);
        assertThat(saved.getAddress()).isEqualTo(address);
        assertThat(saved.getLogoUrl()).isEqualTo(logo);
        // Nosotros se guarda aparte de la descripción de la portada y la tienda pública lo recibe.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/store/slug/" + saved.getSlug() + "/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(description))
                .andExpect(jsonPath("$.aboutText").value(ABOUT));

        Prodcut product = products.save(Prodcut.builder().name("Latte").price(10).stock(5).owner(owner).company(saved).build());
        String deliveryAddress = "Jr. Muy Largo ".repeat(21) + "9";
        assertThat(deliveryAddress.length()).isBetween(256, 300);
        mvc.perform(post("/store/" + saved.getId() + "/order").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("customerName", "Ana", "customerPhone", "987654321",
                                "customerAddress", deliveryAddress,
                                "items", List.of(Map.of("productId", product.getId(), "quantity", 1))))))
                .andExpect(status().isOk());
    }
}
