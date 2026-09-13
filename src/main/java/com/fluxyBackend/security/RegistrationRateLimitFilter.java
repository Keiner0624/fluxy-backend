package com.fluxyBackend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Local-instance abuse protection. Do not trust client-supplied forwarding headers. */
@Component
public class RegistrationRateLimitFilter extends OncePerRequestFilter {
    private record Window(long until, int count) {}
    private final Map<String, Window> windows = new HashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !("/auth/register-business".equals(request.getServletPath())
                || "/auth/forgot-password".equals(request.getServletPath()));
    }

    private synchronized long waitSeconds(String key) {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().until() <= now);
        Window current = windows.get(key);
        if (current == null && windows.size() >= 10000) return 60;
        if (current == null) current = new Window(now + 3600000, 0);
        if (current.count() >= 10) return Math.max(1, (current.until() - now + 999) / 1000);
        windows.put(key, new Window(current.until(), current.count() + 1));
        return 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long wait = waitSeconds(request.getServletPath() + ":" + request.getRemoteAddr());
        if (wait == 0) { chain.doFilter(request, response); return; }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(wait));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":429,\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Demasiados intentos. Intenta más tarde.\"}");
    }
}
