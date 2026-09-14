package com.fluxyBackend.exception;

import com.fluxyBackend.security.ClientInfo;
import com.fluxyBackend.security.RateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Todas las respuestas de error comparten forma: status, code, message, path y
 * requestId. Los errores inesperados nunca exponen detalles internos: el
 * requestId permite encontrarlos en los logs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static Map<String, Object> body(HttpStatusCode status, String code, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);
        if (request != null) body.put("path", request.getRequestURI());
        String requestId = ClientInfo.requestId();
        if (requestId != null) body.put("requestId", requestId);
        return body;
    }

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<Map<String, Object>> handleRegistration(RegistrationException ex, HttpServletRequest request) {
        Map<String, Object> body = body(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
        body.put("field", ex.getField());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(ProductLimitException.class)
    public ResponseEntity<Map<String, Object>> handleProductLimit(ProductLimitException ex, HttpServletRequest request) {
        Map<String, Object> body = body(HttpStatus.FORBIDDEN, "PRODUCT_LIMIT_REACHED", ex.getMessage(), request);
        body.put("limit", ex.getLimit());
        body.put("current", ex.getCurrent());
        body.put("plan", ex.getPlan());
        body.put("upgradeRequired", true);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(HttpStatus.FORBIDDEN, ex.getCode(), ex.getMessage(), request));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        Map<String, Object> body = body(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
        if (ex.getDetails() != null && !ex.getDetails().isEmpty()) body.put("details", ex.getDetails());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimited(RateLimitedException ex, HttpServletRequest request) {
        Map<String, Object> body = body(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", ex.getMessage(), request);
        body.put("retryAfterSeconds", ex.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    /**
     * Sin esto Spring responde el ResponseStatusException sin el motivo, y el
     * vendedor veía "Error" en vez de "Stock insuficiente: Pollo".
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex, HttpServletRequest request) {
        String reason = ex.getReason() == null ? "Solicitud rechazada" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .body(body(ex.getStatusCode(), codeFor(ex.getStatusCode()), reason, request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Solicitud inválida");
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        Map<String, Object> body = body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
        body.put("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception ex, HttpServletRequest request) {
        log.info("Solicitud inválida en {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Solicitud inválida", request));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(body(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "El archivo es demasiado grande.", request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Método no permitido.", request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Formato no soportado.", request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recurso no encontrado.", request));
    }

    /** Dos operaciones sobre el mismo dato a la vez, o un duplicado que la base rechazó. */
    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, Object>> handleConflict(RuntimeException ex, HttpServletRequest request) {
        log.warn("Conflicto de datos en {}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(HttpStatus.CONFLICT, "CONFLICT",
                "Otro cambio se hizo al mismo tiempo o el dato ya existe. Actualizá e intentá de nuevo.", request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Error inesperado en {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "Ocurrió un error inesperado. Si se repite, escribinos con el código de la solicitud.", request));
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
    }
}
