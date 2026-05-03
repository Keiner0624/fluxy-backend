package com.fluxyBackend.controller;

import com.fluxyBackend.service.PasswordResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class PasswordResetController {
    private final PasswordResetService passwordResetService;

    //Solicitar recuperacion
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()){
            return ResponseEntity.badRequest().body(Map.of("message", "El email es requerido"));
        }

        passwordResetService.requestReset(email);
        return ResponseEntity.ok(Map.of("message", "Si el email existe, se ha enviado un enlace de recuperación"));
    }
    @GetMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> validateToken(@RequestParam String token) {
        boolean valid = passwordResetService.validateToken(token);
        if (!valid){
            return ResponseEntity.badRequest().body(Map.of("valid", false, "message", "El link ha expirado o ya fue usado. Solicita uno nuevo."));
        }
        return ResponseEntity.ok(Map.of("valid", true));
    }

    //Resetear contraseña
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody Map<String, String> body){
        String token = body.get("token");
        String newPassword = body.get("password");

        if (token == null || token.isBlank()){
            return ResponseEntity.badRequest().body(Map.of("message", "El token es requerido"));
        }
        if (newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "La contraseña es requerida"));
        }
        try {
            passwordResetService.resetPassword(token, newPassword);
            return ResponseEntity.ok(Map.of("message", "Contraseña actualizada exitosamente"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
