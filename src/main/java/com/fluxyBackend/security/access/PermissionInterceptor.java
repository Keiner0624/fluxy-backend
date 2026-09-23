package com.fluxyBackend.security.access;

import com.fluxyBackend.exception.ForbiddenException;
import com.fluxyBackend.security.RateLimitService;
import com.fluxyBackend.service.CompanyLifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Set;

/**
 * Aplica @RequirePermission, corta el acceso a quien fue desactivado, limita
 * las peticiones por usuario y registra la actividad real del negocio.
 *
 * El estado se revisa en cada petición de un vendedor, no solo al iniciar
 * sesión: desactivar a alguien tiene efecto inmediato aunque su token siga
 * vigente.
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** Rutas que no son actividad de negocio y que siguen disponibles con la tienda archivada. */
    private static final List<String> ACCOUNT_PATHS = List.of("/me/", "/company-account/", "/users/me",
            "/payments/create-preference", "/companies/trial", "/push/", "/billing/");

    private final AccessService accessService;
    private final RateLimitService rateLimitService;
    private final ObjectProvider<CompanyLifecycleService> lifecycleService;

    public PermissionInterceptor(AccessService accessService, RateLimitService rateLimitService,
                                 ObjectProvider<CompanyLifecycleService> lifecycleService) {
        this.accessService = accessService;
        this.rateLimitService = rateLimitService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequirePermission required = method.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequirePermission.class);
        }

        if (accessService.sellerEmail().isEmpty()) {
            // Anónimo o token de administrador: los endpoints con permisos son de vendedores.
            if (required != null) {
                throw new ForbiddenException(ForbiddenException.NO_COMPANY,
                        "Esta acción requiere una cuenta de vendedor.");
            }
            return true;
        }

        Member member;
        try {
            member = accessService.current();
        } catch (ForbiddenException e) {
            // Sin empresa todavía puede consultar su perfil; desactivado no.
            if (required != null || ForbiddenException.ACCESS_DISABLED.equals(e.getCode())) throw e;
            return true;
        }

        rateLimitService.check(RateLimitService.Bucket.API_USER, "user:" + member.user().getId());

        if (required != null) {
            Permission[] permissions = required.value();
            if (required.any()) {
                boolean allowed = false;
                for (Permission permission : permissions) {
                    if (member.can(permission)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) throw AccessService.missing(permissions[0]);
            } else {
                for (Permission permission : permissions) {
                    if (!member.can(permission)) throw AccessService.missing(permission);
                }
            }
        }

        if (MUTATING.contains(request.getMethod()) && !isAccountPath(request.getRequestURI())) {
            CompanyLifecycleService lifecycle = lifecycleService.getIfAvailable();
            if (lifecycle != null) {
                lifecycle.ensureWritable(member.company());
                lifecycle.recordActivity(member.companyId());
            }
        }
        return true;
    }

    private static boolean isAccountPath(String uri) {
        for (String prefix : ACCOUNT_PATHS) {
            if (uri.startsWith(prefix) || uri.equals(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix)) {
                return true;
            }
        }
        return false;
    }
}
