package com.fluxyBackend.security.access;

import com.fluxyBackend.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Aplica @RequirePermission y corta el acceso a quien fue desactivado.
 *
 * El estado se revisa en cada petición de un vendedor, no solo al iniciar
 * sesión: desactivar a alguien tiene efecto inmediato aunque su token siga
 * vigente.
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private final AccessService accessService;

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

        if (required == null) {
            try {
                accessService.current();
            } catch (ForbiddenException e) {
                // Sin empresa todavía puede consultar su perfil; desactivado no.
                if (ForbiddenException.ACCESS_DISABLED.equals(e.getCode())) throw e;
            }
            return true;
        }

        Member member = accessService.current();
        Permission[] permissions = required.value();
        if (required.any()) {
            for (Permission permission : permissions) {
                if (member.can(permission)) return true;
            }
            throw AccessService.missing(permissions[0]);
        }
        for (Permission permission : permissions) {
            if (!member.can(permission)) throw AccessService.missing(permission);
        }
        return true;
    }
}
