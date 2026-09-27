package com.fluxyBackend.security;

import com.fluxyBackend.entity.IdempotencyRecord;
import com.fluxyBackend.repository.IdempotencyRecordRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Idempotency-Key para crear pedidos y registrar o reembolsar pagos.
 *
 * Con la misma clave y el mismo contenido se devuelve la respuesta original sin
 * repetir la operación (un doble clic o un reintento por mala señal no crea dos
 * pedidos). La misma clave con otro contenido responde 422; mientras la
 * primera sigue en curso, 409. Solo se guardan respuestas exitosas: si falló,
 * el cliente puede corregir y reintentar con la misma clave.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_\\-:.]{8,100}$");
    private static final List<Pattern> PATHS = List.of(
            Pattern.compile("^/store/\\d+/order$"),
            Pattern.compile("^/store/slug/[^/]+/order$"),
            Pattern.compile("^/orders/?$"),
            Pattern.compile("^/payments/?$"),
            Pattern.compile("^/payments/\\d+/refund$"),
            Pattern.compile("^/payments/create-preference$"),
            Pattern.compile("^/billing/subscription/(checkout|cancel|reactivate|trial)$"),
            // Emisión de comprobantes: un doble clic o un reintento de red no emite dos veces.
            Pattern.compile("^/invoicing/documents/?$"),
            Pattern.compile("^/invoicing/documents/\\d+/(credit-notes|resend-email|retry)$"));

    private final IdempotencyRecordRepository repository;
    private final TransactionTemplate transactions;

    public IdempotencyFilter(IdempotencyRecordRepository repository, TransactionTemplate transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || request.getHeader(HEADER) == null) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PATHS.stream().noneMatch(p -> p.matcher(path).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER).strip();
        if (!KEY.matcher(key).matches()) {
            writeError(response, 400, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key inválida: 8 a 100 caracteres alfanuméricos.");
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        String path = request.getRequestURI();
        String scope = truncate(subject(request) + "|" + path, 80);
        String requestHash = Hashing.sha256(new String(body, StandardCharsets.UTF_8));

        IdempotencyRecord existing = repository.findByScopeAndKey(scope, key).orElse(null);
        if (existing != null && existing.getExpiresAt().isAfter(LocalDateTime.now())) {
            replayOrReject(existing, requestHash, response);
            return;
        }
        if (existing != null) repository.delete(existing);

        IdempotencyRecord record = new IdempotencyRecord();
        record.setScope(scope);
        record.setKey(key);
        record.setRequestHash(requestHash);
        record.setStatus(IdempotencyRecord.Status.IN_PROGRESS);
        record.setCreatedAt(LocalDateTime.now());
        record.setExpiresAt(LocalDateTime.now().plusHours(24));
        try {
            record = repository.saveAndFlush(record);
        } catch (DataIntegrityViolationException race) {
            IdempotencyRecord winner = repository.findByScopeAndKey(scope, key).orElse(null);
            if (winner != null) {
                replayOrReject(winner, requestHash, response);
            } else {
                writeError(response, 409, "IDEMPOTENCY_IN_PROGRESS", "Esta operación ya se está procesando.");
            }
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
            int status = wrapped.getStatus();
            if (status >= 200 && status < 300) {
                record.setStatus(IdempotencyRecord.Status.COMPLETED);
                record.setResponseStatus(status);
                record.setResponseBody(new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
                repository.save(record);
                stored = true;
            }
        } finally {
            if (!stored) {
                try {
                    repository.deleteById(record.getId());
                } catch (RuntimeException e) {
                    log.warn("No se pudo liberar la Idempotency-Key {}: {}", key, e.getMessage());
                }
            }
            wrapped.copyBodyToResponse();
        }
    }

    private void replayOrReject(IdempotencyRecord record, String requestHash, HttpServletResponse response) throws IOException {
        if (!Hashing.constantTimeEquals(record.getRequestHash(), requestHash)) {
            writeError(response, 422, "IDEMPOTENCY_KEY_REUSED", "Esta Idempotency-Key ya se usó con otro contenido.");
            return;
        }
        if (record.getStatus() != IdempotencyRecord.Status.COMPLETED) {
            writeError(response, 409, "IDEMPOTENCY_IN_PROGRESS", "Esta operación ya se está procesando.");
            return;
        }
        response.setStatus(record.getResponseStatus() == null ? 200 : record.getResponseStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Idempotent-Replayed", "true");
        response.getWriter().write(record.getResponseBody() == null ? "" : record.getResponseBody());
    }

    /** Clave por usuario autenticado o, en la tienda pública, por IP. */
    private static String subject(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "u:" + Hashing.sha256(auth.getName()).substring(0, 16);
        }
        return "ip:" + Hashing.sha256(ClientInfo.ip(request)).substring(0, 16);
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String requestId = ClientInfo.requestId();
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\""
                + (requestId == null ? "" : ",\"requestId\":\"" + requestId + "\"") + "}");
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    // Sin @Transactional: un proxy de este filtro no podría interceptar sus métodos final.
    @Scheduled(cron = "0 50 3 * * *", zone = "America/Lima")
    public void purgeExpired() {
        Integer deleted = transactions.execute(tx -> repository.deleteExpired(LocalDateTime.now()));
        if (deleted != null && deleted > 0) log.info("Idempotencia: {} registros vencidos eliminados", deleted);
    }

    /** Petición cuyo cuerpo ya se leyó: lo vuelve a entregar a Spring. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
