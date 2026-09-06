package com.fluxyBackend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pedir un recurso inexistente debe responder 404, no 500.
 *
 * Estos endpoints son públicos y los consume la tienda: devolver 500 hacía
 * ver una tienda que no existe como una caída del servidor.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotFoundExceptionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void tiendaInexistentePorSlugDevuelve404() throws Exception {
        mockMvc.perform(get("/store/slug/no-existe-esta-tienda/info"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Tienda no encontrada"));
    }

    @Test
    void productosDeTiendaInexistenteDevuelve404() throws Exception {
        mockMvc.perform(get("/store/slug/no-existe-esta-tienda/products"))
                .andExpect(status().isNotFound());
    }

    @Test
    void empresaInexistentePorIdDevuelve404() throws Exception {
        mockMvc.perform(get("/store/999999/info"))
                .andExpect(status().isNotFound());
    }
}
