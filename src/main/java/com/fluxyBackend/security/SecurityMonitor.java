package com.fluxyBackend.security;

import com.fluxyBackend.service.EmailService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Métricas de seguridad (Micrometer) y alertas por umbral.
 *
 * No alerta por cada error: avisa al administrador cuando una señal supera su
 * umbral dentro de la ventana, y como máximo una vez por hora por tipo. Las
 * métricas se consultan en /actuator/metrics con un token de administrador.
 */
@Component
@Slf4j
public class SecurityMonitor {

    enum Signal {
        LOGIN_FAILURES(50, Duration.ofMinutes(10), "Muchos inicios de sesión fallidos: posible fuerza bruta o credential stuffing."),
        SERVER_ERRORS(20, Duration.ofMinutes(5), "El backend está devolviendo errores 5xx."),
        RATE_LIMITED(300, Duration.ofMinutes(10), "Muchas solicitudes bloqueadas por límite de tasa: posible abuso o bots."),
        DENIED(500, Duration.ofMinutes(10), "Muchas respuestas 401/403: intentos no autorizados o frontend roto."),
        OTP_FAILURES(10, Duration.ofMinutes(10), "Fallan los envíos de códigos de verificación: proveedor caído o mal configurado."),
        REFRESH_REUSE(1, Duration.ofMinutes(10), "Se reutilizó un refresh token ya rotado: posible robo de sesión.");

        final int threshold;
        final Duration window;
        final String description;

        Signal(int threshold, Duration window, String description) {
            this.threshold = threshold;
            this.window = window;
            this.description = description;
        }
    }

    private record Window(Instant start, int count) {}

    private static final Duration ALERT_COOLDOWN = Duration.ofHours(1);

    private final ObjectProvider<MeterRegistry> registry;
    private final ObjectProvider<EmailService> emailService;
    private final Map<Signal, Window> windows = new ConcurrentHashMap<>();
    private final Map<Signal, Instant> lastAlert = new ConcurrentHashMap<>();

    @Value("${app.security.alert_email:${admin.email:}}")
    private String adminEmail;

    public SecurityMonitor(ObjectProvider<MeterRegistry> registry, ObjectProvider<EmailService> emailService) {
        this.registry = registry;
        this.emailService = emailService;
    }

    public void recordHttpStatus(int status, String path) {
        if (status == 401 || status == 403) {
            count("fluxy.http.denied", "status", String.valueOf(status));
            signal(Signal.DENIED, "Última ruta: " + path);
        } else if (status >= 500) {
            count("fluxy.http.server_errors", "status", String.valueOf(status));
            signal(Signal.SERVER_ERRORS, "Última ruta: " + path);
        }
    }

    public void recordLogin(boolean success) {
        count("fluxy.auth.login", "result", success ? "success" : "failure");
        if (!success) signal(Signal.LOGIN_FAILURES, null);
    }

    public void recordRateLimited(String bucket) {
        count("fluxy.ratelimit.blocked", "bucket", bucket);
        signal(Signal.RATE_LIMITED, "Último límite: " + bucket);
    }

    public void recordOtpDelivery(String channel, boolean delivered) {
        count("fluxy.otp.sent", "channel", channel, "result", delivered ? "delivered" : "failed");
        if (!delivered) signal(Signal.OTP_FAILURES, "Canal: " + channel);
    }

    public void recordRefreshReuse(Long userId) {
        count("fluxy.auth.refresh_reuse");
        signal(Signal.REFRESH_REUSE, "Usuario " + userId + ". Se revocó la sesión.");
    }

    public void recordLifecycle(String status) {
        count("fluxy.company.lifecycle", "status", status);
    }

    private void count(String name, String... tags) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) meters.counter(name, tags).increment();
    }

    private void signal(Signal signal, String detail) {
        Instant now = Instant.now();
        Window window = windows.compute(signal, (s, current) ->
                current == null || current.start().plus(signal.window).isBefore(now)
                        ? new Window(now, 1)
                        : new Window(current.start(), current.count() + 1));
        if (window.count() < signal.threshold) return;

        Instant previous = lastAlert.get(signal);
        if (previous != null && previous.plus(ALERT_COOLDOWN).isAfter(now)) return;
        lastAlert.put(signal, now);

        String message = signal.description + " " + window.count() + " eventos desde " + window.start()
                + (detail == null ? "" : ". " + detail);
        log.warn("ALERTA DE SEGURIDAD [{}] {}", signal, message);
        EmailService email = emailService.getIfAvailable();
        if (email != null && adminEmail != null && !adminEmail.isBlank()) {
            // Fuera del hilo de la petición: una alerta nunca debe demorar ni romper la respuesta.
            CompletableFuture.runAsync(() -> email.sendSecurityAlert(adminEmail, signal.name(), message));
        }
    }
}
