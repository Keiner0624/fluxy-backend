package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.PublicStoreResponse;
import com.fluxyBackend.entity.Company;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los endpoints /store/** son públicos. Antes devolvían la entidad Company
 * entera, con el correo del comerciante y su estado de facturación al alcance
 * de cualquiera. Estas pruebas fijan qué sale y qué no.
 */
class PublicStoreExposureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Company empresaCompleta() {
        Company c = new Company();
        c.setId(17L);
        c.setName("Polleria moren");
        c.setSlug("polleria-tochi");
        c.setEmail("duenio@privado.test");
        c.setPhone("999888777");
        c.setAddress("Av. Siempre Viva 742");
        c.setDescription("Pollo a la brasa");
        c.setPrimaryColor("#23221f");
        c.setLogoUrl("https://cdn.test/logo.png");
        c.setStoreStyle("{\"preset\":\"custom\"}");
        c.setPaymentMethods("[\"efectivo\"]");
        c.setCustomDomain("mitienda.com");
        c.setPlan(Company.Plan.PRO);
        c.setPlanActivatedAt(LocalDateTime.now());
        c.setPlanExpiresAt(LocalDateTime.now().plusMonths(1));
        c.setTrialUsed(true);
        return c;
    }

    @Test
    void noExponeDatosInternosDelComerciante() throws Exception {
        String json = mapper.writeValueAsString(PublicStoreResponse.from(empresaCompleta()));

        assertThat(json)
                .doesNotContain("duenio@privado.test")
                .doesNotContain("planExpiresAt")
                .doesNotContain("planActivatedAt")
                .doesNotContain("trialUsed")
                .doesNotContain("customDomain")
                .doesNotContain("mitienda.com");
    }

    @Test
    void conservaLoQueLaTiendaNecesitaParaRenderizarse() throws Exception {
        String json = mapper.writeValueAsString(PublicStoreResponse.from(empresaCompleta()));

        assertThat(json)
                .contains("Polleria moren")
                .contains("polleria-tochi")
                .contains("999888777")          // WhatsApp
                .contains("#23221f")            // color de marca
                .contains("logo.png")
                .contains("PRO");               // decide si se muestra la marca de Fluxy
    }

    @Test
    void elProductoPublicoNoArrastraLaEmpresa() throws Exception {
        com.fluxyBackend.entity.Prodcut producto = new com.fluxyBackend.entity.Prodcut();
        producto.setId(5L);
        producto.setName("Cafe");
        producto.setPrice(12.0);
        producto.setStock(5);
        producto.setCompany(empresaCompleta());

        String json = mapper.writeValueAsString(producto);

        assertThat(json).contains("Cafe");
        assertThat(json)
                .doesNotContain("duenio@privado.test")
                .doesNotContain("trialUsed")
                .doesNotContain("planExpiresAt");
    }
}
