package com.fluxyBackend.security;

import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Autentica con el access token.
 *
 * Además de firma y vencimiento, exige que la sesión del token siga activa: un
 * token de una sesión cerrada o revocada no sirve aunque no haya vencido. Los
 * tokens anteriores a las sesiones (sin sid) ya no se aceptan.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    public static final String REQUEST_USER = "fluxy.user";

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final UserRepository userRepository;

    @Value("${admin.email:}")
    private String adminEmail;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/auth/")
                || path.startsWith("/store/")
                || path.equals("/payments/webhook")
                || path.equals("/coupons/validate")
                || path.startsWith("/actuator/health")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7).trim();
        if (jwt.isBlank() || !jwt.contains(".") || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            JwtService.TokenClaims claims = jwtService.parse(jwt);

            if (JwtService.TYPE_ADMIN.equals(claims.type())) {
                // ─── Token de admin (sin cuenta en BD) ───────────────────────
                if (!adminEmail.isBlank() && adminEmail.trim().equalsIgnoreCase(claims.subject())) {
                    authenticate(request, claims.subject(), List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                }
            } else if (JwtService.TYPE_ACCESS.equals(claims.type()) && claims.sessionId() != null
                    && claims.userId() != null && sessionService.isActive(claims.sessionId(), claims.userId())) {
                // ─── Token de vendedor con sesión activa ──────────────────────
                User user = userRepository.findById(claims.userId()).orElse(null);
                if (user != null && user.getStatus() == User.Status.ACTIVE) {
                    UserDetails details = CustomUserDetailsService.toUserDetails(user);
                    authenticate(request, details, details.getAuthorities());
                    request.setAttribute(SessionService.REQUEST_SESSION_ID, claims.sessionId());
                    request.setAttribute(REQUEST_USER, user);
                }
            }
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT inválido: {}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private static void authenticate(HttpServletRequest request, Object principal,
                                     java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> authorities) {
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
