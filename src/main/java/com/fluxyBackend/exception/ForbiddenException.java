package com.fluxyBackend.exception;

/** 403 con un código que el frontend puede interpretar. */
public class ForbiddenException extends RuntimeException {

    public static final String MISSING_PERMISSION = "MISSING_PERMISSION";
    public static final String ACCESS_DISABLED = "ACCESS_DISABLED";
    public static final String NO_COMPANY = "NO_COMPANY";

    private final String code;

    public ForbiddenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
