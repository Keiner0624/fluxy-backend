package com.fluxyBackend.controller;

import com.fluxyBackend.entity.PushSubscription;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.PushSubscriptionRepository;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/push")
@RequiredArgsConstructor
public class PushSubscriptionController {

    private final PushSubscriptionRepository pushRepo;
    private final UserRepository             userRepo;

    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(
            Authentication auth,
            @RequestBody Map<String, Object> body) {

        User user = userRepo.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

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