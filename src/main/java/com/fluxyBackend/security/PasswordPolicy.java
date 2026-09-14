package com.fluxyBackend.security;

import com.fluxyBackend.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Longitud antes que complejidad: 10 caracteres como mínimo, se aceptan frases y
 * no se exigen símbolos. Se rechaza lo trivialmente adivinable.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    /** BCrypt ignora lo que pasa de 72 bytes. */
    public static final int MAX_BYTES = 72;

    private static final Set<String> COMMON = Set.of(
            "1234567890", "0123456789", "12345678910", "qwertyuiop", "contraseña", "contrasena1",
            "password12", "password123", "fluxy12345", "abcdefghij", "1111111111", "0000000000",
            "iloveyou12", "peru123456", "admin12345", "asdfghjkl1");

    private PasswordPolicy() {
    }

    public static void validate(String password, String email) {
        if (password == null || password.isBlank() || password.length() < MIN_LENGTH) {
            throw error("La contraseña tiene que tener al menos " + MIN_LENGTH + " caracteres. Podés usar una frase.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw error("La contraseña es demasiado larga (máximo " + MAX_BYTES + " bytes).");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower) || lower.chars().distinct().count() <= 2) {
            throw error("Esa contraseña es muy fácil de adivinar. Elegí otra.");
        }
        if (email != null && !email.isBlank()) {
            String local = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (lower.equals(email.toLowerCase(Locale.ROOT)) || (local.length() >= 6 && lower.contains(local))) {
                throw error("La contraseña no puede contener tu correo.");
            }
        }
    }

    private static BusinessException error(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", message, Map.of("field", "password"));
    }
}
