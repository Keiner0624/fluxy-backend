package com.fluxyBackend.domain;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS de Fluxy más los dominios propios de las tiendas. Un dominio de tienda solo puede llamar
 * a la API pública de la tienda (/store/** y la validación de cupones), que no usa sesión: el
 * panel y el resto de la API siguen aceptando solo los orígenes configurados.
 */
public class StoreCorsConfigurationSource implements CorsConfigurationSource {

    private final CorsConfiguration base;
    private final CustomDomainRegistry registry;

    public StoreCorsConfigurationSource(CorsConfiguration base, CustomDomainRegistry registry) {
        this.base = base;
        this.registry = registry;
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || base.getAllowedOrigins() != null && base.getAllowedOrigins().contains(origin)) return base;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean publicStore = path.startsWith("/store/") || path.equals("/coupons/validate");
        if (!publicStore || !registry.isStoreOrigin(origin)) return base;

        CorsConfiguration store = new CorsConfiguration(base);
        List<String> origins = new ArrayList<>(base.getAllowedOrigins() == null ? List.of() : base.getAllowedOrigins());
        origins.add(origin);
        store.setAllowedOrigins(origins);
        store.setAllowCredentials(false);
        return store;
    }
}
