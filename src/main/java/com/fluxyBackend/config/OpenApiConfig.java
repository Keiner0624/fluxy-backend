package com.fluxyBackend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fluxyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fluxy Backend API")
                        .version("0.0.1-SNAPSHOT")
                        .description("API de Fluxy para gestionar tiendas, productos, pedidos y suscripciones. "
                                + "Obtén un JWT en POST /auth/login (o /auth/admin-login para administración) "
                                + "y pégalo en Authorize. Las operaciones con candado requieren el token. "
                                + "Las rutas públicas de tienda, autenticación y validación de cupones no requieren JWT. "
                                + "El webhook de Mercado Pago valida su propia firma. "
                                + "La seguridad actual responde 403 cuando falta autenticación en una ruta privada."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Pega únicamente el JWT; Swagger UI añade el prefijo Bearer.")));
    }
}
