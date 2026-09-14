package com.fluxyBackend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

/** Datos del cliente de la petición en curso: IP, dispositivo e id de la petición. */
public final class ClientInfo {

    private ClientInfo() {
    }

    /**
     * IP real del cliente detrás del proxy de Render.
     *
     * Se toma la última dirección de X-Forwarded-For, la que agrega el proxy:
     * la primera la puede escribir el propio cliente para esquivar los límites.
     */
    public static String ip(HttpServletRequest request) {
        if (request == null) return "unknown";
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) return last;
        }
        return request.getRemoteAddr();
    }

    public static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    public static String currentIp() {
        return ip(currentRequest());
    }

    public static String ipHash(String ip) {
        return ip == null ? null : Hashing.sha256("ip:" + ip);
    }

    /** Ubicación aproximada sin guardar la IP: 190.237.x.x o 2001:db8:x. */
    public static String ipPrefix(String ip) {
        if (ip == null || ip.isBlank()) return null;
        if (ip.contains(".")) {
            String[] octets = ip.split("\\.");
            return octets.length == 4 ? octets[0] + "." + octets[1] + ".x.x" : null;
        }
        String[] groups = ip.split(":");
        return groups.length >= 2 ? groups[0] + ":" + groups[1] + ":x" : null;
    }

    public static String userAgent(HttpServletRequest request) {
        String agent = request == null ? null : request.getHeader("User-Agent");
        if (agent == null) return null;
        return agent.length() > 300 ? agent.substring(0, 300) : agent;
    }

    /** "Chrome en Windows", suficiente para reconocer un dispositivo en la lista. */
    public static String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Dispositivo desconocido";
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String browser = ua.contains("edg/") ? "Edge"
                : ua.contains("opr/") || ua.contains("opera") ? "Opera"
                : ua.contains("chrome/") && !ua.contains("chromium") ? "Chrome"
                : ua.contains("firefox/") ? "Firefox"
                : ua.contains("safari/") ? "Safari"
                : "Navegador";
        String os = ua.contains("android") ? "Android"
                : ua.contains("iphone") || ua.contains("ipad") ? "iOS"
                : ua.contains("windows") ? "Windows"
                : ua.contains("mac os") ? "macOS"
                : ua.contains("linux") ? "Linux"
                : "otro sistema";
        return browser + " en " + os;
    }

    public static String requestId() {
        return MDC.get(RequestIdFilter.MDC_KEY);
    }
}
