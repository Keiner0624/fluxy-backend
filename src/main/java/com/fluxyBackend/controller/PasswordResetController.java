package com.fluxyBackend.controller;

import com.fluxyBackend.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Autenticación", description = "Recuperación de contraseña mediante enlaces de un solo uso.")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public record ForgotRequest(String email) {}
    public record ResetRequest(String token, String password) {}

    @Operation(summary = "Solicitar recuperación de contraseña",
            description = "La respuesta es la misma exista o no la cuenta. Límite por IP y por correo.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"email\":\"cliente@example.com\"}"))))
    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@RequestBody ForgotRequest body) {
        passwordResetService.requestReset(body.email());
        return Map.of("message", "Si hay una cuenta con ese correo, te enviamos un enlace para restablecer la contraseña.");
    }

    @Operation(summary = "Validar un enlace de recuperación",
            description = "Responde 400 si expiró, fue usado o no existe.")
    @GetMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> validateToken(@RequestParam String token) {
        if (!passwordResetService.validateToken(token)) {
            return ResponseEntity.badRequest().body(Map.of("valid", false,
                    "code", "RESET_TOKEN_INVALID", "message", "El enlace venció o ya fue usado. Pedí uno nuevo."));
        }
        return ResponseEntity.ok(Map.of("valid", true));
    }

    @Operation(summary = "Restablecer la contraseña",
            description = "password de 10 a 72 caracteres. Cierra todas las sesiones abiertas y avisa por correo.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"token\":\"token-del-enlace\",\"password\":\"NuevaClave123!\"}"))))
    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@RequestBody ResetRequest body) {
        passwordResetService.resetPassword(body.token(), body.password());
        return Map.of("message", "Contraseña actualizada. Iniciá sesión con la nueva.");
    }
}
