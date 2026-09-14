package com.fluxyBackend.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Regla de negocio incumplida, con un mensaje pensado para mostrarse tal cual al
 * vendedor. Antes se lanzaba RuntimeException y terminaba en un 500 sin mensaje.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    /** Datos extra para el cliente, por ejemplo el token para continuar un registro. */
    private final Map<String, Object> details;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, "BUSINESS_RULE", message);
    }

    public BusinessException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public BusinessException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
