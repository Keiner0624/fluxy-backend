package com.fluxyBackend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import jakarta.validation.ConstraintViolationException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<Map<String, Object>> handleRegistration(RegistrationException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "status", ex.getStatus().value(), "code", ex.getCode(), "field", ex.getField(),
                "message", ex.getMessage(), "path", request.getRequestURI()));
    }

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

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("status", 403, "code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(Map.of("status", ex.getStatus().value(), "code", ex.getCode(), "message", ex.getMessage()));
    }

    /**
     * Sin esto Spring responde el ResponseStatusException sin el motivo, y el
     * vendedor veía "Error" en vez de "Stock insuficiente: Pollo".
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        String reason = ex.getReason() == null ? "Solicitud rechazada" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("status", ex.getStatusCode().value(), "message", reason));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Solicitud inválida");
        Map<String, String> errors = new java.util.LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of("message", message, "status", 400,
                "code", "VALIDATION_ERROR", "errors", errors, "path", request.getRequestURI()));
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("message", "Solicitud inválida"));
    }
}
