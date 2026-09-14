package com.fluxyBackend.controller;

import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.service.AccountSecurityService;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import com.fluxyBackend.service.VerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Seguridad de la cuenta de quien hace la petición. No requiere permisos de la empresa. */
@Tag(name = "Seguridad de la cuenta", description = "Sesiones, contraseña, contacto verificado y cuentas vinculadas.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class AccountSecurityController {

    private final AccessService accessService;
    private final SessionService sessionService;
    private final AccountSecurityService securityService;
    private final AuditService auditService;

    @Operation(summary = "Resumen de seguridad", description = "Verificaciones, contraseña y cuentas vinculadas.")
    @GetMapping("/security")
    public AccountSecurityService.Overview overview() {
        return securityService.overview(accessService.current().user());
    }

    // ─── Sesiones ─────────────────────────────────────────────────────────────

    @Operation(summary = "Sesiones activas", description = "Dispositivo, red aproximada y último uso.")
    @GetMapping("/sessions")
    public List<SessionService.SessionView> sessions() {
        Member member = accessService.current();
        return sessionService.list(member.user().getId(), accessService.currentSessionId());
    }

    @Operation(summary = "Cerrar una sesión")
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> revoke(@PathVariable String sessionId) {
        Member member = accessService.current();
        sessionService.revoke(member.user().getId(), sessionId, "USER_REVOKED");
        auditService.record(member, AuditAction.SESSION_REVOKED, "SESSION", sessionId, null);
        return Map.of("message", "Sesión cerrada.");
    }

    @Operation(summary = "Cerrar sesión en todos los demás dispositivos")
    @PostMapping("/sessions/revoke-others")
    public Map<String, Object> revokeOthers() {
        Member member = accessService.current();
        int revoked = sessionService.revokeAllForUser(member.user().getId(), accessService.currentSessionId(), "USER_REVOKED_ALL");
        auditService.record(member, AuditAction.SESSIONS_REVOKED_ALL, "USER", member.user().getId(), Map.of("revoked", revoked));
        return Map.of("revoked", revoked);
    }

    @Operation(summary = "Cerrar la sesión actual", description = "Revoca el refresh token de este dispositivo.")
    @PostMapping("/logout")
    public Map<String, String> logout() {
        Member member = accessService.current();
        String sessionId = accessService.currentSessionId();
        if (sessionId != null) sessionService.revoke(member.user().getId(), sessionId, "LOGOUT");
        auditService.record(member, AuditAction.LOGOUT, "SESSION", sessionId, null);
        return Map.of("message", "Sesión cerrada.");
    }

    // ─── Identidad ────────────────────────────────────────────────────────────

    @Operation(summary = "Confirmar identidad",
            description = "Con password, o con provider + idToken + nonce. Habilita por 10 minutos las acciones sensibles.")
    @PostMapping("/reauth")
    public Map<String, String> reauth(@RequestBody AccountSecurityService.ReauthRequest request) {
        securityService.reauthenticate(accessService.current().user(), accessService.currentSessionId(), request);
        return Map.of("message", "Identidad confirmada.");
    }

    @Operation(summary = "Cambiar o definir la contraseña",
            description = "signOutOthers (por defecto true) cierra las demás sesiones.")
    @PostMapping("/password")
    public Map<String, Object> password(@RequestBody AccountSecurityService.PasswordRequest request) {
        int revoked = securityService.changePassword(accessService.current().user(), accessService.currentSessionId(), request);
        return Map.of("message", "Contraseña actualizada.", "sessionsRevoked", revoked);
    }

    // ─── Correo y WhatsApp ────────────────────────────────────────────────────

    @Operation(summary = "Enviar código para verificar el correo actual")
    @PostMapping("/email/verification")
    public VerificationService.Issued sendEmailVerification() {
        return securityService.sendEmailVerification(accessService.current().user());
    }

    @Operation(summary = "Confirmar el correo actual")
    @PostMapping("/email/verification/confirm")
    public Map<String, String> confirmEmailVerification(@RequestBody AccountSecurityService.CodeRequest request) {
        securityService.confirmEmailVerification(accessService.current().user(), request.code());
        return Map.of("message", "Correo verificado.");
    }

    @Operation(summary = "Pedir cambio de correo", description = "Requiere identidad reciente. Envía un código al correo nuevo.")
    @PostMapping("/email/change")
    public VerificationService.Issued requestEmailChange(@RequestBody AccountSecurityService.ContactRequest request) {
        return securityService.requestEmailChange(accessService.current().user(), accessService.currentSessionId(), request.value());
    }

    @Operation(summary = "Confirmar cambio de correo", description = "Avisa al correo anterior.")
    @PostMapping("/email/change/confirm")
    public Map<String, String> confirmEmailChange(@RequestBody AccountSecurityService.CodeRequest request) {
        securityService.confirmEmailChange(accessService.current().user(), request.code());
        return Map.of("message", "Correo actualizado.");
    }

    @Operation(summary = "Pedir cambio de WhatsApp", description = "Requiere identidad reciente y la Cloud API configurada.")
    @PostMapping("/phone/change")
    public VerificationService.Issued requestPhoneChange(@RequestBody AccountSecurityService.ContactRequest request) {
        return securityService.requestPhoneChange(accessService.current().user(), accessService.currentSessionId(), request.value());
    }

    @Operation(summary = "Confirmar cambio de WhatsApp", description = "applyToStore también lo usa como WhatsApp de la tienda.")
    @PostMapping("/phone/change/confirm")
    public Map<String, String> confirmPhoneChange(@RequestBody AccountSecurityService.CodeRequest request) {
        securityService.confirmPhoneChange(accessService.current(), request.code(), Boolean.TRUE.equals(request.applyToStore()));
        return Map.of("message", "WhatsApp actualizado.");
    }

    // ─── Cuentas vinculadas ───────────────────────────────────────────────────

    @Operation(summary = "Vincular Google o Apple", description = "Requiere identidad reciente.")
    @PostMapping("/identities/{provider}")
    public Map<String, String> link(@PathVariable String provider, @RequestBody AccountSecurityService.IdentityRequest request) {
        securityService.linkIdentity(accessService.current().user(), accessService.currentSessionId(), provider, request);
        return Map.of("message", "Cuenta vinculada.");
    }

    @Operation(summary = "Desvincular Google o Apple", description = "No se puede quitar el único método de acceso.")
    @DeleteMapping("/identities/{provider}")
    public Map<String, String> unlink(@PathVariable String provider) {
        securityService.unlinkIdentity(accessService.current().user(), accessService.currentSessionId(), provider);
        return Map.of("message", "Cuenta desvinculada.");
    }
}
