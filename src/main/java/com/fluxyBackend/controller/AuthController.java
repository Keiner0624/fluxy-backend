package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.fluxyBackend.DTOs.LoginRequest;
import com.fluxyBackend.DTOs.RegisterBussinesRequest;
import com.fluxyBackend.DTOs.RegisterRequest;
import com.fluxyBackend.entity.BusinessCategory;
import com.fluxyBackend.entity.UserIdentity;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.security.ClientInfo;
import com.fluxyBackend.security.LoginAttemptService;
import com.fluxyBackend.security.RateLimitedException;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.security.oauth.OAuthNonceService;
import com.fluxyBackend.security.oauth.OidcTokenVerifier;
import com.fluxyBackend.service.AuthService;
import com.fluxyBackend.service.SignupService;
import com.fluxyBackend.service.VerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Tag(name = "Autenticación", description = "Registro verificado, inicio de sesión, sesiones y acceso con Google o Apple.")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SignupService signupService;
    private final SessionService sessionService;
    private final LoginAttemptService loginAttemptService;
    private final OAuthNonceService nonceService;
    private final OidcTokenVerifier oidcTokenVerifier;
    private final VerificationService verificationService;

    @Value("${oauth.apple.redirect_uri:}")
    private String appleRedirectUri;

    public record SignupTokenRequest(String signupToken) {}
    public record VerifyRequest(String signupToken, String channel, String code) {}
    public record ResendRequest(String signupToken, String channel) {}
    public record BusinessStepRequest(String signupToken, String businessName, BusinessCategory category,
                                      String whatsapp, String taxId, boolean termsAccepted) {}
    public record RefreshRequest(String refreshToken) {}
    public record OAuthRequest(String idToken, String nonce, String name, boolean rememberMe) {}

    @Operation(summary = "Registrar un usuario", description = "Requiere rol ADMIN. Crea un usuario en una empresa existente.")
    @PostMapping("/register")
    public String register(@RequestBody @Valid RegisterRequest request) {
        return authService.register(request);
    }

    // ─── Inicio de sesión ─────────────────────────────────────────────────────

    @Operation(summary = "Iniciar sesión",
            description = "Devuelve access token (15 min) y refresh token. rememberMe extiende la sesión a 14 días. "
                    + "401 ante credenciales inválidas, 403 VERIFICATION_REQUIRED con signupToken si la cuenta no está "
                    + "verificada, y 429 con Retry-After tras varios intentos fallidos.")
    @PostMapping("/login")
    public AuthResponse login(@RequestBody @Valid LoginRequest request, HttpServletRequest http) {
        return conLimiteDeIntentos(request.email, http, () -> authService.login(request, http));
    }

    @Operation(summary = "Iniciar sesión como administrador", description = "Token de administrador de 2 horas.")
    @PostMapping("/admin-login")
    public AuthResponse adminLogin(@RequestBody @Valid LoginRequest request, HttpServletRequest http) {
        return conLimiteDeIntentos(request.email, http, () -> authService.adminLogin(request, http));
    }

    @Operation(summary = "Renovar la sesión",
            description = "Rota el refresh token: el anterior deja de servir. Reutilizar un refresh token ya rotado "
                    + "revoca la sesión (401 SESSION_REVOKED). 409 REFRESH_ROTATED si otra pestaña acaba de renovarla.")
    @PostMapping("/refresh")
    public AuthResponse refresh(@RequestBody RefreshRequest request) {
        return sessionService.refresh(request.refreshToken());
    }

    // ─── Registro verificado ──────────────────────────────────────────────────

    @Operation(summary = "Crear una cuenta",
            description = "Guarda los datos y envía un código al correo (y al WhatsApp si está disponible). "
                    + "La tienda se crea al verificar. Devuelve signupToken para los pasos siguientes.")
    @PostMapping("/signup")
    public SignupService.SignupState signup(@RequestBody SignupService.SignupRequest request) {
        return signupService.startWithPassword(request);
    }

    @Operation(summary = "Crear una cuenta (ruta anterior)", description = "Equivale a /auth/signup.")
    @PostMapping("/register-business")
    public SignupService.SignupState registerBusiness(@RequestBody @Valid RegisterBussinesRequest request) {
        return signupService.startWithPassword(new SignupService.SignupRequest(request.fullName, request.businesName,
                request.category, request.whatssapp, request.email, request.password, request.taxId, request.termsAccepted));
    }

    @Operation(summary = "Estado del registro", description = "Qué falta: BUSINESS, EMAIL o PHONE.")
    @PostMapping("/signup/state")
    public SignupService.SignupState signupState(@RequestBody SignupTokenRequest request) {
        return signupService.state(request.signupToken());
    }

    @Operation(summary = "Verificar un código del registro",
            description = "channel EMAIL o PHONE. Al completar lo pendiente crea la tienda y devuelve la sesión.")
    @PostMapping("/signup/verify")
    public SignupService.SignupState verify(@RequestBody VerifyRequest request, HttpServletRequest http) {
        return signupService.verify(request.signupToken(), request.channel(), request.code(), http);
    }

    @Operation(summary = "Reenviar un código del registro", description = "Un reenvío por minuto y cinco por hora por destino.")
    @PostMapping("/signup/resend")
    public VerificationService.Issued resend(@RequestBody ResendRequest request) {
        return signupService.resend(request.signupToken(), request.channel());
    }

    @Operation(summary = "Completar los datos del negocio",
            description = "Para registros con Google o Apple, que llegan sin datos del negocio.")
    @PostMapping("/signup/business")
    public SignupService.SignupState business(@RequestBody BusinessStepRequest request, HttpServletRequest http) {
        return signupService.saveBusiness(request.signupToken(), new SignupService.BusinessRequest(request.businessName(),
                request.category(), request.whatsapp(), request.taxId(), request.termsAccepted()), http);
    }

    // ─── Google y Apple ───────────────────────────────────────────────────────

    @Operation(summary = "Proveedores disponibles",
            description = "Client ids públicos para mostrar los botones. Un proveedor sin configurar vuelve en null.")
    @GetMapping("/oauth/config")
    public Map<String, Object> oauthConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("google", oidcTokenVerifier.isEnabled(UserIdentity.Provider.GOOGLE)
                ? Map.of("clientId", oidcTokenVerifier.clientId(UserIdentity.Provider.GOOGLE)) : null);
        config.put("apple", oidcTokenVerifier.isEnabled(UserIdentity.Provider.APPLE)
                ? Map.of("clientId", oidcTokenVerifier.clientId(UserIdentity.Provider.APPLE),
                "redirectUri", appleRedirectUri == null ? "" : appleRedirectUri) : null);
        config.put("phoneVerification", verificationService.phoneChannelAvailable());
        return config;
    }

    @Operation(summary = "Obtener un nonce", description = "De un solo uso y 10 minutos. Va dentro del ID token del proveedor.")
    @PostMapping("/oauth/nonce")
    public Map<String, String> nonce() {
        return Map.of("nonce", nonceService.issue());
    }

    @Operation(summary = "Acceder con Google o Apple",
            description = "provider google o apple. status LOGGED_IN trae la sesión; ONBOARDING_REQUIRED trae el registro "
                    + "por completar. 409 ACCOUNT_EXISTS si el correo ya tiene cuenta con otro método.")
    @PostMapping("/oauth/{provider}")
    public AuthService.OAuthResult oauth(@PathVariable String provider, @RequestBody OAuthRequest request,
                                         HttpServletRequest http) {
        return authService.oauth(parseProvider(provider), request.idToken(), request.nonce(), request.name(),
                request.rememberMe(), http);
    }

    public static UserIdentity.Provider parseProvider(String provider) {
        try {
            return UserIdentity.Provider.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PROVIDER_UNKNOWN", "Proveedor desconocido.");
        }
    }

    /**
     * Corta el intento si la cuenta o la IP están bloqueadas, y lleva la cuenta
     * de fallos y aciertos. Se aplica igual al login de administrador, que es
     * un objetivo más valioso que cualquier cuenta de vendedor.
     */
    private AuthResponse conLimiteDeIntentos(String email, HttpServletRequest http, Supplier<AuthResponse> autenticar) {
        String ip = ClientInfo.ip(http);
        long espera = loginAttemptService.segundosDeBloqueo(email, ip);
        if (espera > 0) {
            throw new RateLimitedException(espera, "Demasiados intentos fallidos. Probá de nuevo en "
                    + ((espera / 60) + 1) + " minutos.");
        }
        try {
            AuthResponse response = autenticar.get();
            loginAttemptService.registrarExito(email, ip);
            return response;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                loginAttemptService.registrarFallo(email, ip);
            }
            throw e;
        }
    }
}
