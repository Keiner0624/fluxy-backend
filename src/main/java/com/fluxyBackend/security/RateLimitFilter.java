package com.fluxyBackend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Límites por IP para las rutas públicas y tamaño máximo del cuerpo.
 *
 * Los límites por usuario de la API autenticada se aplican en
 * PermissionInterceptor, donde ya se conoce quién hace la petición.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    /** Ninguna petición JSON de Fluxy se acerca a esto; las imágenes van directo a Cloudinary. */
    static final long MAX_BODY_BYTES = 1024 * 1024;

    private final RateLimitService rateLimitService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            write(response, 413, "PAYLOAD_TOO_LARGE", "La solicitud supera el tamaño máximo permitido.", 0);
            return;
        }

        RateLimitService.Bucket bucket = bucketFor(request);
        if (bucket != null) {
            RateLimitService.Decision decision = rateLimitService.hit(bucket, ClientInfo.ipHash(ClientInfo.ip(request)));
            if (!decision.allowed()) {
                write(response, 429, "RATE_LIMIT_EXCEEDED", "Demasiadas solicitudes. Intentá de nuevo en un momento.",
                        decision.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static RateLimitService.Bucket bucketFor(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();
        if (path.startsWith("/store/")) {
            return "POST".equals(method) ? RateLimitService.Bucket.STORE_ORDER : RateLimitService.Bucket.STORE_READ;
        }
        if (!"POST".equals(method)) return null;
        if (path.equals("/auth/signup") || path.equals("/auth/register-business") || path.startsWith("/auth/oauth/")) {
            return RateLimitService.Bucket.SIGNUP;
        }
        if (path.equals("/auth/refresh")) return RateLimitService.Bucket.REFRESH;
        if (path.equals("/auth/forgot-password")) return RateLimitService.Bucket.PASSWORD_RESET_IP;
        return null;
    }

    private static void write(HttpServletResponse response, int status, String code, String message, long retryAfter)
            throws IOException {
        response.setStatus(status);
        if (retryAfter > 0) response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
