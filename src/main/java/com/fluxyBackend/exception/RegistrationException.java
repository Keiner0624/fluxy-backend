package com.fluxyBackend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class RegistrationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String field;

    public RegistrationException(HttpStatus status, String code, String field, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }
}
