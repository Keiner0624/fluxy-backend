package com.fluxyBackend.security.oauth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.fluxyBackend.entity.UserIdentity.Provider;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.security.Hashing;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifica los ID tokens de Google y Apple (OpenID Connect).
 *
 * Firma RS256 con las claves públicas del proveedor, emisor, audiencia (el
 * client id de Fluxy), vencimiento y nonce. El token del proveedor solo prueba
 * la identidad: la sesión la emite Fluxy.
 */
@Service
@Slf4j
public class OidcTokenVerifier {

    public record VerifiedIdentity(Provider provider, String subject, String email, boolean emailVerified) {}

    private record ProviderConfig(String jwksUri, Set<String> issuers) {}

    private static final Map<Provider, ProviderConfig> PROVIDERS = Map.of(
            Provider.GOOGLE, new ProviderConfig("https://www.googleapis.com/oauth2/v3/certs",
                    Set.of("https://accounts.google.com", "accounts.google.com")),
            Provider.APPLE, new ProviderConfig("https://appleid.apple.com/auth/keys",
                    Set.of("https://appleid.apple.com")));

    private static final Duration KEYS_TTL = Duration.ofHours(6);
    private static final Duration MIN_REFETCH = Duration.ofMinutes(1);

    private record CachedKeys(Map<String, PublicKey> keys, Instant fetchedAt) {}

    private final JsonMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<Provider, CachedKeys> cache = new ConcurrentHashMap<>();

    @Value("${oauth.google.client_id:}")
    private String googleClientId;

    @Value("${oauth.apple.client_id:}")
    private String appleClientId;

    public OidcTokenVerifier(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled(Provider provider) {
        String clientId = clientId(provider);
        return clientId != null && !clientId.isBlank();
    }

    public String clientId(Provider provider) {
        return provider == Provider.GOOGLE ? googleClientId : appleClientId;
    }

    public VerifiedIdentity verify(Provider provider, String idToken, String expectedNonce) {
        if (!isEnabled(provider)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_DISABLED",
                    "El acceso con " + label(provider) + " no está disponible.");
        }
        if (idToken == null || idToken.isBlank() || idToken.length() > 8000) throw invalid(provider);

        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            if (!"RS256".equals(header.getAlgorithm())) {
                                throw new JwtException("Algoritmo no permitido: " + header.getAlgorithm());
                            }
                            return publicKey(provider, header.getKeyId());
                        }
                    })
                    .requireAudience(clientId(provider))
                    .clockSkewSeconds(60)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.info("Token de {} rechazado: {}", provider, e.getMessage());
            throw invalid(provider);
        }

        if (!PROVIDERS.get(provider).issuers().contains(claims.getIssuer())) throw invalid(provider);
        String nonce = claims.get("nonce", String.class);
        // Google devuelve el nonce tal cual; algunos flujos de Apple lo devuelven como SHA-256.
        boolean nonceMatches = expectedNonce != null && nonce != null
                && (Hashing.constantTimeEquals(nonce, expectedNonce) || Hashing.constantTimeEquals(nonce, Hashing.sha256(expectedNonce)));
        if (!nonceMatches) throw invalid(provider);

        String subject = claims.getSubject();
        String email = claims.get("email", String.class);
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) throw invalid(provider);
        Object verified = claims.get("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(String.valueOf(verified));
        return new VerifiedIdentity(provider, subject, email.strip().toLowerCase(), emailVerified);
    }

    private PublicKey publicKey(Provider provider, String kid) {
        CachedKeys cached = cache.get(provider);
        Instant now = Instant.now();
        boolean stale = cached == null || cached.fetchedAt().plus(KEYS_TTL).isBefore(now);
        boolean unknownKid = cached != null && kid != null && !cached.keys().containsKey(kid)
                && cached.fetchedAt().plus(MIN_REFETCH).isBefore(now);
        if (stale || unknownKid) {
            cached = new CachedKeys(parseJwks(fetchJwks(provider)), now);
            cache.put(provider, cached);
        }
        PublicKey key = cached.keys().get(kid);
        if (key == null) throw new JwtException("Clave desconocida: " + kid);
        return key;
    }

    /** Protegido para poder reemplazarlo en pruebas con claves propias. */
    protected String fetchJwks(Provider provider) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(PROVIDERS.get(provider).jwksUri()))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new JwtException("JWKS respondió " + response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JwtException("Descarga de claves interrumpida");
        } catch (java.io.IOException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                    "No pudimos comunicarnos con " + label(provider) + ". Intentá de nuevo.");
        }
    }

    private Map<String, PublicKey> parseJwks(String json) {
        try {
            Map<String, PublicKey> keys = new ConcurrentHashMap<>();
            KeyFactory factory = KeyFactory.getInstance("RSA");
            for (JsonNode jwk : objectMapper.readTree(json).path("keys")) {
                if (!"RSA".equals(jwk.path("kty").asString())) continue;
                BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asString()));
                BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asString()));
                keys.put(jwk.path("kid").asString(), factory.generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            }
            return keys;
        } catch (Exception e) {
            throw new JwtException("JWKS inválido: " + e.getMessage());
        }
    }

    private static BusinessException invalid(Provider provider) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "OAUTH_TOKEN_INVALID",
                "No pudimos validar tu acceso con " + label(provider) + ". Intentá de nuevo.");
    }

    public static String label(Provider provider) {
        return provider == Provider.GOOGLE ? "Google" : "Apple";
    }
}
