package com.fluxyBackend.security;

import com.fluxyBackend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;

/**
 * Firma y lee los access tokens.
 *
 * El access token dura 15 minutos y lleva el id de la sesión: si la sesión se
 * revoca, el token deja de servir aunque no haya vencido. La sesión larga la
 * sostiene el refresh token, que se guarda como hash y rota en cada uso.
 */
@Service
public class JwtService {

    /** Mínimo que exige HS256. Una clave más corta debilita la firma. */
    private static final int MIN_KEY_BYTES = 32;

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_ADMIN = "admin";

    private static final String COMO_GENERARLA = """
            Generá una y definila como variable de entorno JWT_SECRET
            o guardala en el archivo .env de la raíz del proyecto:

              PowerShell:  [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
              Linux/macOS: openssl rand -base64 32

            En IntelliJ: Run > Edit Configurations > Environment variables.
              Nombre: JWT_SECRET
              Valor: la clave Base64 generada (no usar la clave como nombre).
            En .env: JWT_SECRET=tu_clave_base64 (sin comillas).
            En Render:   Environment > Add Environment Variable.

            No la agregues a application.properties: ese archivo va al repositorio.""";

    public record TokenClaims(String subject, Long userId, String sessionId, String type) {}

    @Value("${jwt.secret:}")
    private String secretKey;

    @Value("${jwt.access_ttl_minutes:15}")
    private long accessTtlMinutes = 15;

    @Value("${jwt.admin_ttl_minutes:120}")
    private long adminTtlMinutes = 120;

    /** Se construye una sola vez al arrancar, no en cada token. */
    private SecretKey signInKey;
    private byte[] keyBytes;

    /**
     * Valida la clave al arrancar, en lugar de fallar al firmar el primer token.
     *
     * Antes application.properties traía una clave por defecto, que quedaba
     * publicada en el repositorio: cualquiera con acceso al código podía firmar
     * tokens válidos de cualquier usuario sin conocer su contraseña.
     */
    @PostConstruct
    void init() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "Falta JWT_SECRET. La aplicación no arranca sin una clave de firma.\n\n"
                            + COMO_GENERARLA);
        }

        try {
            keyBytes = Decoders.BASE64.decode(secretKey.trim());
        } catch (DecodingException e) {
            throw new IllegalStateException(
                    "JWT_SECRET no es Base64 válido.\n\n" + COMO_GENERARLA, e);
        }

        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET es demasiado corta: " + keyBytes.length + " bytes. "
                            + "HS256 exige al menos " + MIN_KEY_BYTES + ".\n\n" + COMO_GENERARLA);
        }

        this.signInKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(User user, String sessionId) {
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("uid", user.getId())
                .claim("sid", sessionId)
                .claim("typ", TYPE_ACCESS)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTtl().toMillis()))
                .signWith(signInKey)
                .compact();
    }

    public String generateAdminToken(String email) {
        return Jwts.builder()
                .subject(email)
                .claim("typ", TYPE_ADMIN)
                .claim("role", "ADMIN")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + Duration.ofMinutes(adminTtlMinutes).toMillis()))
                .signWith(signInKey)
                .compact();
    }

    public Duration accessTtl() {
        return Duration.ofMinutes(accessTtlMinutes);
    }

    /**
     * Valida firma y vencimiento. Lanza JwtException si el token no sirve.
     */
    public TokenClaims parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signInKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Number uid = claims.get("uid", Number.class);
        return new TokenClaims(claims.getSubject(), uid == null ? null : uid.longValue(),
                claims.get("sid", String.class), claims.get("typ", String.class));
    }

    /**
     * Clave derivada para otro propósito (por ejemplo, el HMAC de los códigos de
     * verificación), sin reutilizar la clave de firma tal cual.
     */
    public byte[] derivedKey(String purpose) {
        return java.util.HexFormat.of().parseHex(Hashing.hmacSha256(keyBytes, "fluxy:" + purpose));
    }
}
