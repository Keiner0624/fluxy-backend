package com.fluxyBackend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {

    /** Mínimo que exige HS256. Una clave más corta debilita la firma. */
    private static final int MIN_KEY_BYTES = 32;

    private static final String COMO_GENERARLA = """
            Generá una y definila como variable de entorno JWT_SECRET:

              PowerShell:  [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
              Linux/macOS: openssl rand -base64 32

            En IntelliJ: Run > Edit Configurations > Environment variables.
            En Render:   Environment > Add Environment Variable.

            No la agregues a application.properties: ese archivo va al repositorio.""";

    @Value("${jwt.secret:}")
    private String secretKey;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    /** Se construye una sola vez al arrancar, no en cada token. */
    private SecretKey signInKey;

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

        byte[] keyBytes;
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

    public String generateToken(String email) {
        return Jwts.builder()
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(signInKey)
                .compact();
    }

    public String generateAdminToken(String email) {
        return Jwts.builder()
                .subject(email)
                .claims(Map.of("role", "ADMIN"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(signInKey)
                .compact();
    }

    public boolean isAdminToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return "ADMIN".equals(claims.get("role", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public boolean isTokenValid(String token, String email) {
        final String username = extractUsername(token);
        return username != null && email != null
                && username.equalsIgnoreCase(email)
                && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractAllClaims(token).getExpiration().before(new Date());
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signInKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
