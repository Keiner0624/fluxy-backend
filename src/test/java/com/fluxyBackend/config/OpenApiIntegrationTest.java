package com.fluxyBackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void swaggerUiAndItsResourcesArePublic() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        for (String path : new String[]{"/swagger-ui/index.html", "/swagger-ui/swagger-ui.css",
                "/swagger-ui/swagger-ui-bundle.js", "/v3/api-docs/swagger-config"}) {
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
    }

    @Test
    void specificationDescribesJwtAndPaymentContracts() throws Exception {
        JsonNode api = specification();
        assertThat(api.path("openapi").asText()).startsWith("3.");
        assertThat(api.at("/info/title").asText()).isEqualTo("Fluxy Backend API");
        assertThat(api.at("/components/securitySchemes/bearerAuth/type").asText()).isEqualTo("http");
        assertThat(api.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");

        JsonNode paths = api.path("paths");
        JsonNode checkout = paths.path("/billing/subscription/checkout").path("post");
        assertThat(checkout.path("summary").asText()).isNotBlank();
        JsonNode checkoutRequest = api.at("/components/schemas/CheckoutRequest/properties");
        assertThat(checkoutRequest.has("plan")).isTrue();
        assertThat(checkoutRequest.has("months")).isTrue();
        JsonNode checkoutResponse = api.at("/components/schemas/CheckoutResponse/properties");
        assertThat(checkoutResponse.has("checkoutUrl")).isTrue();
        assertThat(checkoutResponse.has("quote")).isTrue();
        for (String path : new String[]{"/billing/subscription/cancel", "/billing/subscription/reactivate"}) {
            assertThat(paths.path(path).path("post").path("description").asText()).as(path).isNotBlank();
        }
        assertThat(paths.path("/billing/subscription").path("get").isMissingNode()).isFalse();
        assertThat(paths.path("/payments/create-preference").path("post").isMissingNode()).isFalse();
    }

    @Test
    void onlyProtectedOperationsDeclareBearerAuthentication() throws Exception {
        JsonNode paths = specification().path("paths");
        for (String[] endpoint : new String[][]{
                {"/products", "get"}, {"/orders", "post"}, {"/me", "get"},
                {"/companies", "get"}, {"/admin/metrics", "get"},
                {"/categories", "get"}, {"/coupons", "get"},
                {"/payments/create-preference", "post"}}) {
            JsonNode operation = paths.path(endpoint[0]).path(endpoint[1]);
            assertThat(operation.path("security").path(0).has("bearerAuth"))
                    .as("%s %s requiere JWT", endpoint[1], endpoint[0]).isTrue();
        }
        for (String[] endpoint : new String[][]{
                {"/auth/login", "post"}, {"/auth/admin-login", "post"},
                {"/auth/forgot-password", "post"}, {"/store/{companyId}/order", "post"},
                {"/store/slug/{slug}/info", "get"}, {"/store/{companyId}/categories", "get"},
                {"/store/slug/{slug}/categories", "get"}, {"/coupons/validate", "get"},
                {"/payments/webhook", "post"}}) {
            JsonNode operation = paths.path(endpoint[0]).path(endpoint[1]);
            assertThat(operation.isMissingNode()).as("Existe %s %s", endpoint[1], endpoint[0]).isFalse();
            assertThat(operation.path("security").size())
                    .as("%s %s es público", endpoint[1], endpoint[0]).isZero();
        }
    }

    @Test
    void schemasPreserveExistingJsonNamesAndValidation() throws Exception {
        JsonNode schemas = specification().path("components").path("schemas");
        JsonNode business = schemas.path("RegisterBussinesRequest");
        assertThat(business.path("properties").has("businesName")).isTrue();
        assertThat(business.path("properties").has("whatssapp")).isTrue();
        assertThat(business.path("properties").path("password").path("minLength").asInt()).isEqualTo(10);
        assertThat(business.path("properties").path("password").path("maxLength").asInt()).isEqualTo(72);
        assertThat(schemas.path("OrderItemsRequest").path("properties").path("quantity")
                .path("maximum").asInt()).isEqualTo(1000);
        assertThat(schemas.has("Authentication")).isFalse();
        assertThat(schemas.has("HttpServletRequest")).isFalse();
    }

    @Test
    void exposingDocumentationDoesNotOpenBusinessEndpoints() throws Exception {
        mockMvc.perform(get("/products")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/payments/create-preference")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/metrics")).andExpect(status().isUnauthorized());
    }

    private JsonNode specification() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(json);
    }
}
