package com.fluxyBackend.security.oauth;

import com.fluxyBackend.entity.UserIdentity.Provider;
import com.fluxyBackend.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Firma, emisor, audiencia, vencimiento, nonce y algoritmo de los ID tokens. */
class OidcTokenVerifierTest {

    private static final String CLIENT_ID = "fluxy-web.apps.googleusercontent.com";
    private static final String NONCE = "nonce-de-prueba-123";

    private KeyPair providerKeys;
    private OidcTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        providerKeys = rsa();
        String jwks = jwks("k1", (RSAPublicKey) providerKeys.getPublic());
        verifier = new OidcTokenVerifier(JsonMapper.builder().build()) {
            @Override
            protected String fetchJwks(Provider provider) {
                return jwks;
            }
        };
        ReflectionTestUtils.setField(verifier, "googleClientId", CLIENT_ID);
    }

    @Test
    void aceptaUnTokenValido() {
        OidcTokenVerifier.VerifiedIdentity identity = verifier.verify(Provider.GOOGLE,
                token(providerKeys, "k1", "https://accounts.google.com", CLIENT_ID, NONCE, 600), NONCE);

        assertThat(identity.subject()).isEqualTo("google-sub-1");
        assertThat(identity.email()).isEqualTo("persona@gmail.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.emailAuthoritative()).isTrue();
    }

    @Test
    void googleSoloGarantizaGmailYSuWorkspace() {
        assertThat(verifyGoogle("ana@empresa.com", true, null).emailAuthoritative()).isFalse();
        assertThat(verifyGoogle("ana@empresa.com", true, "empresa.com").emailAuthoritative()).isTrue();
        assertThat(verifyGoogle("ana@gmail.com", false, null).emailAuthoritative()).isFalse();
    }

    private OidcTokenVerifier.VerifiedIdentity verifyGoogle(String email, boolean verified, String hostedDomain) {
        var builder = Jwts.builder().header().keyId("k1").and()
                .issuer("https://accounts.google.com").audience().add(CLIENT_ID).and()
                .subject("google-sub-2").claim("email", email).claim("email_verified", verified)
                .claim("nonce", NONCE).expiration(new Date(System.currentTimeMillis() + 600_000));
        if (hostedDomain != null) builder.claim("hd", hostedDomain);
        return verifier.verify(Provider.GOOGLE, builder.signWith(providerKeys.getPrivate(), Jwts.SIG.RS256).compact(), NONCE);
    }

    @Test
    void rechazaOtraAudiencia() {
        assertInvalid(token(providerKeys, "k1", "https://accounts.google.com", "otra-app", NONCE, 600), NONCE);
    }

    @Test
    void rechazaOtroEmisor() {
        assertInvalid(token(providerKeys, "k1", "https://evil.example.com", CLIENT_ID, NONCE, 600), NONCE);
    }

    @Test
    void rechazaNonceDistinto() {
        assertInvalid(token(providerKeys, "k1", "https://accounts.google.com", CLIENT_ID, "otro-nonce", 600), NONCE);
    }

    @Test
    void rechazaTokenVencido() {
        assertInvalid(token(providerKeys, "k1", "https://accounts.google.com", CLIENT_ID, NONCE, -3600), NONCE);
    }

    @Test
    void rechazaFirmaDeOtraClave() throws Exception {
        assertInvalid(token(rsa(), "k1", "https://accounts.google.com", CLIENT_ID, NONCE, 600), NONCE);
    }

    @Test
    void rechazaAlgoritmoSimetrico() {
        // Ataque clásico: firmar con HMAC usando la clave pública como secreto.
        String forged = Jwts.builder().header().keyId("k1").and()
                .issuer("https://accounts.google.com").audience().add(CLIENT_ID).and()
                .subject("google-sub-1").claim("email", "persona@gmail.com").claim("nonce", NONCE)
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(new SecretKeySpec(providerKeys.getPublic().getEncoded(), "HmacSHA256"))
                .compact();
        assertInvalid(forged, NONCE);
    }

    @Test
    void sinClientIdElProveedorNoEstaDisponible() {
        assertThatThrownBy(() -> verifier.verify(Provider.APPLE, "x.y.z", NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("PROVIDER_DISABLED");
    }

    private void assertInvalid(String token, String nonce) {
        assertThatThrownBy(() -> verifier.verify(Provider.GOOGLE, token, nonce))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("OAUTH_TOKEN_INVALID");
    }

    private static String token(KeyPair keys, String kid, String issuer, String audience, String nonce, long secondsToExpire) {
        return Jwts.builder().header().keyId(kid).and()
                .issuer(issuer).audience().add(audience).and()
                .subject("google-sub-1")
                .claim("email", "Persona@gmail.com")
                .claim("email_verified", true)
                .claim("nonce", nonce)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + secondsToExpire * 1000))
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String jwks(String kid, RSAPublicKey key) {
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"alg\":\"RS256\",\"n\":\"" + b64(key.getModulus())
                + "\",\"e\":\"" + b64(key.getPublicExponent()) + "\"}]}";
    }

    private static String b64(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
