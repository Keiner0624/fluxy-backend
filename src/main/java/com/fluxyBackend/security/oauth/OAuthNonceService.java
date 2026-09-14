package com.fluxyBackend.security.oauth;

import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.security.Hashing;
import com.fluxyBackend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nonce firmado y de un solo uso para Google y Apple.
 *
 * Lo emite el backend y viaja dentro del ID token: un token capturado en otra
 * sesión no sirve, porque su nonce ya se usó o no lo emitió Fluxy.
 */
@Service
@RequiredArgsConstructor
public class OAuthNonceService {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final JwtService jwtService;
    private final Map<String, Instant> used = new ConcurrentHashMap<>();

    public String issue() {
        String payload = Hashing.randomToken() + "." + Instant.now().plus(TTL).getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + Hashing.hmacSha256(jwtService.derivedKey("oauth-nonce"), encoded);
    }

    /** Valida firma y vencimiento y lo marca como usado. */
    public void consume(String nonce) {
        if (nonce == null || nonce.length() > 300 || !nonce.contains(".")) throw invalid();
        int dot = nonce.lastIndexOf('.');
        String encoded = nonce.substring(0, dot);
        String signature = nonce.substring(dot + 1);
        if (!Hashing.constantTimeEquals(Hashing.hmacSha256(jwtService.derivedKey("oauth-nonce"), encoded), signature)) {
            throw invalid();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        Instant expires = Instant.ofEpochSecond(Long.parseLong(payload.substring(payload.lastIndexOf('.') + 1)));
        if (expires.isBefore(Instant.now())) throw invalid();
        if (used.putIfAbsent(nonce, expires) != null) throw invalid();
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    void purge() {
        Instant now = Instant.now();
        used.values().removeIf(expires -> expires.isBefore(now));
    }

    private static BusinessException invalid() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "OAUTH_NONCE_INVALID",
                "El intento de acceso venció. Volvé a intentarlo.");
    }
}
