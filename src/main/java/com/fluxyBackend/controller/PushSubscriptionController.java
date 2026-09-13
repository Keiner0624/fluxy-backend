package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;

import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.entity.PushSubscription;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.PushSubscriptionRepository;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Notificaciones", description = "Suscripciones Web Push del usuario autenticado.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/push")
@RequiredArgsConstructor
public class PushSubscriptionController {

    private final PushSubscriptionRepository pushRepo;
    private final UserRepository             userRepo;

    @Operation(summary = "Registrar una suscripción push",
            description = "Requiere endpoint y keys con p256dh y auth. Repetir la suscripción para el mismo usuario no la duplica.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"endpoint\":\"https://push.example.com/subscription/123\",\"keys\":{\"p256dh\":\"clave-publica-del-navegador\",\"auth\":\"clave-de-autenticacion\"}}"))))
    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(
            Authentication auth,
            @RequestBody Map<String, Object> body) {

        User user = userRepo.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado"));

        String endpoint = (String) body.get("endpoint");

        @SuppressWarnings("unchecked")
        Map<String, String> keys = (Map<String, String>) body.get("keys");

        if (endpoint == null || keys == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Suscripción inválida."));
        }

        if (!pushRepo.existsByEndpointAndUser_Id(endpoint, user.getId())) {
            pushRepo.save(PushSubscription.builder()
                    .endpoint(endpoint)
                    .p256dh(keys.get("p256dh"))
                    .auth(keys.get("auth"))
                    .user(user)
                    .build());
        }

        return ResponseEntity.ok(Map.of("message", "Suscripción guardada."));
    }
}