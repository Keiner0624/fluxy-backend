package com.fluxyBackend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductLimitException.class)
    public ResponseEntity<Map<String, Object>> handleProductLimit(ProductLimitException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "message", ex.getMessage(),
                "limit", ex.getLimit(),
                "current", ex.getCurrent(),
                "plan", ex.getPlan(),
                "upgradeRequired", true
        ));
    }
}
