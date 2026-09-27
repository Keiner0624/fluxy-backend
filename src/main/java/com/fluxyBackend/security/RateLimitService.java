package com.fluxyBackend.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contadores por ventana fija, en memoria.
 *
 * Alcanza para la única instancia de Render. Con varias instancias hay que
 * llevarlos a Redis: cada una contaría por su lado y el límite real se
 * multiplicaría.
 */
@Service
public class RateLimitService {

    /** Límites del documento de seguridad, anexo A. */
    public enum Bucket {
        /** API autenticada, por usuario. */
        API_USER(300, Duration.ofMinutes(1)),
        /** Lecturas de la tienda pública, por IP. */
        STORE_READ(240, Duration.ofMinutes(1)),
        /** Pedidos desde la tienda pública, por IP. */
        STORE_ORDER(15, Duration.ofMinutes(10)),
        /** Eventos de campañas desde la tienda pública, por IP: navegar un catálogo genera varios por minuto. */
        STORE_TRACKING(120, Duration.ofMinutes(1)),
        /** Registro y acceso con Google/Apple, por IP. */
        SIGNUP(10, Duration.ofHours(1)),
        /** Renovación de sesión, por IP. */
        REFRESH(60, Duration.ofMinutes(1)),
        /** Recuperación de contraseña, por correo. */
        PASSWORD_RESET_DESTINATION(3, Duration.ofHours(1)),
        /** Recuperación de contraseña, por IP. */
        PASSWORD_RESET_IP(10, Duration.ofHours(1)),
        /** Exportación de datos del negocio, por empresa. */
        DATA_EXPORT(5, Duration.ofHours(1)),
        /** Reautenticación y cambios sensibles, por usuario. */
        SENSITIVE(10, Duration.ofMinutes(15)),
        /** Hojas del Libro de Reclamaciones, por IP. */
        COMPLAINT(5, Duration.ofHours(1));

        final int limit;
        final Duration window;

        Bucket(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private record Window(long startMillis, int count) {}

    private static final int MAX_KEYS = 100_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final SecurityMonitor monitor;

    public RateLimitService(SecurityMonitor monitor) {
        this.monitor = monitor;
    }

    public Decision hit(Bucket bucket, String subject) {
        String key = bucket.name() + ":" + (subject == null ? "anon" : subject);
        long now = System.currentTimeMillis();
        long windowMillis = bucket.window.toMillis();

        // Protección de memoria: si alguien genera millones de claves, se frena en lugar de crecer sin límite.
        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
            monitor.recordRateLimited(bucket.name());
            return new Decision(false, 60);
        }

        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || now - current.startMillis() >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(current.startMillis(), current.count() + 1);
        });

        if (updated.count() > bucket.limit) {
            long retry = Math.max(1, (updated.startMillis() + windowMillis - now + 999) / 1000);
            monitor.recordRateLimited(bucket.name());
            return new Decision(false, retry);
        }
        return new Decision(true, 0);
    }

    /** Lanza 429 con Retry-After si se superó el límite. */
    public void check(Bucket bucket, String subject) {
        Decision decision = hit(bucket, subject);
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfterSeconds());
        }
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    void purge() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> {
            Bucket bucket = Bucket.valueOf(entry.getKey().substring(0, entry.getKey().indexOf(':')));
            return now - entry.getValue().startMillis() >= bucket.window.toMillis();
        });
    }

    /** Solo para pruebas. */
    public void reset() {
        windows.clear();
    }
}
