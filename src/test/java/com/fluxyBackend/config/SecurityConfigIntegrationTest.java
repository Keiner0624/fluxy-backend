package com.fluxyBackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void meEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "owner@fluxy.invalid", roles = "BUSINESS_OWNER")
    void businessOwnerCannotAccessAdminEndpoints() throws Exception {
        mockMvc.perform(get("/admin/metrics"))
                .andExpect(status().isForbidden());
    }
}
