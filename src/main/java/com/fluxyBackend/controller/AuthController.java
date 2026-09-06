package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.LoginRequest;
import com.fluxyBackend.DTOs.RegisterBussinesRequest;
import com.fluxyBackend.DTOs.RegisterBussinesResponse;
import com.fluxyBackend.DTOs.RegisterRequest;
import com.fluxyBackend.security.LoginAttemptService;
import com.fluxyBackend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Function;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final LoginAttemptService loginAttemptService;

    @PostMapping("/register")
    public String register(@RequestBody @Valid RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody @Valid LoginRequest request,
                              HttpServletRequest http) {
        return conLimiteDeIntentos(request, http, authService::login);
    }

    @PostMapping("/register-business")
    public RegisterBussinesResponse registerBusiness(
            @RequestBody @Valid RegisterBussinesRequest request) {
        return authService.registerBusiness(request);
    }

    // ─── Login exclusivo para el administrador de Fluxy ──────────────────────
    @PostMapping("/admin-login")
    public AuthResponse adminLogin(@RequestBody @Valid LoginRequest request,
                                   HttpServletRequest http) {
        return conLimiteDeIntentos(request, http, authService::adminLogin);
    }

    /**
     * Corta el intento si la cuenta o la IP están bloqueadas, y lleva la cuenta
     * de fallos y aciertos. Se aplica igual al login de administrador, que es
     * un objetivo más valioso que cualquier cuenta de vendedor.
     */
    private AuthResponse conLimiteDeIntentos(LoginRequest request,
                                             HttpServletRequest http,
                                             Function<LoginRequest, AuthResponse> autenticar) {
        String ip = ipDelCliente(http);
        long espera = loginAttemptService.segundosDeBloqueo(request.email, ip);
        if (espera > 0) {
            throw new BloqueoPorIntentos(espera);
        }

        try {
            AuthResponse response = autenticar.apply(request);
            loginAttemptService.registrarExito(request.email, ip);
            return response;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                loginAttemptService.registrarFallo(request.email, ip);
            }
            throw e;
        }
    }

    /** 429 con Retry-After, para que el cliente sepa cuánto esperar. */
    private static class BloqueoPorIntentos extends ResponseStatusException {
        private final long segundos;

        BloqueoPorIntentos(long segundos) {
            super(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos fallidos. Probá de nuevo en "
                            + ((segundos / 60) + 1) + " minutos.");
            this.segundos = segundos;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(segundos));
            return headers;
        }
    }

    /**
     * Detrás del proxy de Render, getRemoteAddr() devuelve la IP del proxy y
     * todos los clientes se verían como uno solo. La IP real es la primera de
     * X-Forwarded-For.
     */
    private String ipDelCliente(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
