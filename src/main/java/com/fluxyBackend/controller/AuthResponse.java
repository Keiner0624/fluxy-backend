package com.fluxyBackend.controller;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * Sesión emitida al iniciar sesión. token es el access token (se conserva el
 * nombre para no romper clientes); refreshToken renueva la sesión.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {
    public String token;
    public String refreshToken;
    /** Segundos de vida del access token. */
    public Long expiresIn;
    public OffsetDateTime refreshExpiresAt;
    public String sessionId;

    public AuthResponse(String token) {
        this.token = token;
    }

    public AuthResponse(String token, String refreshToken, long expiresIn, OffsetDateTime refreshExpiresAt,
                        String sessionId) {
        this.token = token;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
        this.refreshExpiresAt = refreshExpiresAt;
        this.sessionId = sessionId;
    }
}
