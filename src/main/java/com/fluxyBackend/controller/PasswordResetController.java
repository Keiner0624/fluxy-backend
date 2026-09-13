package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;

import com.fluxyBackend.service.PasswordResetService;
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

    //Solicitar recuperacion
    @Operation(summary = "Solicitar recuperación de contraseña",
            description = "Requiere email. La respuesta es la misma exista o no la cuenta.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"email\":\"cliente@example.com\"}"))))
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()){
            return ResponseEntity.badRequest().body(Map.of("message", "El email es requerido"));
        }

        passwordResetService.requestReset(email);
        return ResponseEntity.ok(Map.of("message", "Si el email existe, se ha enviado un enlace de recuperación"));
    }
    @Operation(summary = "Validar un enlace de recuperación",
            description = "Comprueba el token de la URL. Responde 400 si expiró, fue usado o no existe.")
    @GetMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> validateToken(@RequestParam String token) {
        boolean valid = passwordResetService.validateToken(token);
        if (!valid){
            return ResponseEntity.badRequest().body(Map.of("valid", false, "message", "El link ha expirado o ya fue usado. Solicita uno nuevo."));
        }
        return ResponseEntity.ok(Map.of("valid", true));
    }

    //Resetear contraseña
    @Operation(summary = "Restablecer la contraseña",
            description = "Requiere token y password de 8 a 72 caracteres. Responde 400 ante un token o contraseña inválidos.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"token\":\"token-del-enlace\",\"password\":\"NuevaClave123!\"}"))))
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody Map<String, String> body){
        String token = body.get("token");
        String newPassword = body.get("password");

        if (token == null || token.isBlank()){
            return ResponseEntity.badRequest().body(Map.of("message", "El token es requerido"));
        }
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 72) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "La contraseña debe tener entre 8 y 72 caracteres"));
        }
        try {
            passwordResetService.resetPassword(token, newPassword);
            return ResponseEntity.ok(Map.of("message", "Contraseña actualizada exitosamente"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
