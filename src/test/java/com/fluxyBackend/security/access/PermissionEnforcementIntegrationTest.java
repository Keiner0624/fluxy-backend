package com.fluxyBackend.security.access;

import com.fluxyBackend.entity.*;
import com.fluxyBackend.repository.*;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los permisos los aplica el servidor. Ocultar un botón en el panel no impide
 * que alguien llame al endpoint con su token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PermissionEnforcementIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SessionService sessionService;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private MembershipRepository membershipRepository;

    private User owner;
    private User seller;
    private Prodcut product;

    @BeforeEach
    void setUp() {
        Company company = companyRepository.save(Company.builder().name("Tienda permisos").slug("tienda-permisos").build());
        // Dueño de antes de los memberships: no tiene registro y debe seguir siendo dueño.
        owner = userRepository.save(User.builder().fullName("Dueño").email("dueno-permisos@fluxy.invalid")
                .password("x").role(Role.BUSINESS_OWNER).company(company).build());
        seller = userRepository.save(User.builder().fullName("Vendedor").email("vendedor-permisos@fluxy.invalid")
                .password("x").role(Role.TEAM_MEMBER).company(company).build());
        membershipRepository.save(new Membership(seller.getId(), company.getId(), "SELLER"));
        product = productRepository.save(Prodcut.builder().name("Café").price(12).stock(4)
                .owner(owner).company(company).build());
    }

    private String bearer(User user) {
        return TestAuth.bearer(sessionService, user);
    }

    @Test
    void unVendedorVeElCatalogoPeroNoPuedeBorrarProductos() throws Exception {
        mockMvc.perform(get("/products/search").header("Authorization", bearer(seller)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/products/" + product.getId()).header("Authorization", bearer(seller)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MISSING_PERMISSION"));

        assertThat(productRepository.findById(product.getId())).isPresent();
    }

    @Test
    void unVendedorNoVeElEquipoNiLosReportes() throws Exception {
        mockMvc.perform(get("/team").header("Authorization", bearer(seller)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/export?type=sales").header("Authorization", bearer(seller)))
                .andExpect(status().isForbidden());
    }

    @Test
    void desactivarCortaElAccesoAunqueElTokenSigaVigente() throws Exception {
        String token = bearer(seller);
        Membership membership = membershipRepository.findByUserIdAndCompanyId(seller.getId(), seller.getCompany().getId())
                .orElseThrow();
        membership.setStatus(Membership.STATUS_DISABLED);
        membershipRepository.saveAndFlush(membership);

        mockMvc.perform(get("/me").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DISABLED"));
    }

    @Test
    void elDuenioSinMembershipConservaTodosLosPermisos() throws Exception {
        mockMvc.perform(get("/me").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
        mockMvc.perform(delete("/products/" + product.getId()).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        assertThat(membershipRepository.findByUserIdAndCompanyId(owner.getId(), owner.getCompany().getId()))
                .get().extracting(Membership::getRole).isEqualTo("OWNER");
    }
}
