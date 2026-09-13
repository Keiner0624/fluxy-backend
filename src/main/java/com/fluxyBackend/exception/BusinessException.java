package com.fluxyBackend.exception;

import org.springframework.http.HttpStatus;

/**
 * Regla de negocio incumplida, con un mensaje pensado para mostrarse tal cual al
 * vendedor. Antes se lanzaba RuntimeException y terminaba en un 500 sin mensaje.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, "BUSINESS_RULE", message);
    }

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
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
}
