package com.fluxyBackend.security;

import com.fluxyBackend.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifra credenciales de terceros (tokens de proveedores) con AES-256-GCM.
 *
 * La clave maestra vive en el entorno (SECRETS_ENCRYPTION_KEY, 32 bytes en base64), nunca en la
 * base de datos. Sin ella se deriva una de JWT_SECRET, que tampoco está en la base; en producción
 * conviene definir la propia para poder rotarlas por separado. El prefijo "v1:" permite rotar.
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(@Value("${app.secrets.encryption_key:}") String encryptionKey,
                        @Value("${jwt.secret:}") String jwtSecret) {
        this.key = resolveKey(encryptionKey, jwtSecret);
    }

    private static SecretKeySpec resolveKey(String encryptionKey, String jwtSecret) {
        try {
            if (encryptionKey != null && !encryptionKey.isBlank()) {
                byte[] raw = Base64.getDecoder().decode(encryptionKey.trim());
                if (raw.length != 32) throw new IllegalStateException("SECRETS_ENCRYPTION_KEY debe tener 32 bytes en base64");
                return new SecretKeySpec(raw, "AES");
            }
            if (jwtSecret == null || jwtSecret.isBlank()) return null;
            log.warn("SECRETS_ENCRYPTION_KEY no está definida: las credenciales se cifran con una clave derivada de JWT_SECRET.");
            byte[] derived = MessageDigest.getInstance("SHA-256")
                    .digest(("fluxy-secrets-v1:" + jwtSecret).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(derived, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo preparar el cifrado de secretos", e);
        }
    }

    public boolean available() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el secreto", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return null;
        requireKey();
        if (!stored.startsWith(PREFIX)) throw new IllegalStateException("Formato de secreto desconocido");
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Clave cambiada o dato alterado: GCM lo detecta y no devuelve basura.
            throw new IllegalStateException("No se pudo descifrar el secreto", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SECRETS_KEY_MISSING",
                    "El servidor no tiene configurada la clave para guardar credenciales de forma segura.");
        }
    }
}
