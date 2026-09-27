package com.fluxyBackend.config;

import com.fluxyBackend.security.access.PermissionInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .excludePathPatterns("/auth/**", "/store/**", "/payments/webhook", "/webhooks/**", "/coupons/validate",
                        "/actuator/**", "/error", "/swagger-ui/**", "/v3/api-docs/**");
    }
}
