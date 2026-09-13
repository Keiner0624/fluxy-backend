package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;

import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Tag(name = "Perfil", description = "Datos personales y plan del usuario autenticado.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final AccessService accessService;

    // ── GET /me ─────────────────────────────────────────────────────────────
    @Operation(summary = "Consultar mi perfil",
            description = "Incluye empresa, plan, límite de productos, vencimiento y uso de la prueba gratuita.")
    @GetMapping
    public MeResponse me(Authentication authentication) {
        User user = findUser(authentication);

        Plan plan = (user.getCompany() != null && user.getCompany().getPlan() != null)
                ? user.getCompany().getPlan() : Plan.FREE;

        int productLimit = switch (plan) {
            case PRO      -> 100;
            case BUSINESS -> 999999;
            default       -> 10;
        };

        LocalDateTime expiresAt = user.getCompany() != null
                ? user.getCompany().getPlanExpiresAt() : null;

        boolean hasUsedTrial = user.getCompany() != null && user.getCompany().isTrialUsed();

        // ── Nombre para el saludo ─────────────────────────────────────────
        String firstName = user.getFirstName() != null ? user.getFirstName() : "";

        // ── Verificar cumpleaños ──────────────────────────────────────────
        boolean isBirthday = false;
        if (user.getBirthDate() != null) {
            LocalDate today = LocalDate.now();
            isBirthday = user.getBirthDate().getMonth()      == today.getMonth()
                    && user.getBirthDate().getDayOfMonth() == today.getDayOfMonth();
        }
        // ── Rol y permisos en la empresa: el panel decide qué módulos mostrar ─
        String role = null;
        java.util.Set<String> permissions = java.util.Set.of();
        if (user.getCompany() != null) {
            Member member = accessService.current();
            role = member.role().name();
            permissions = Permission.names(member.permissions());
        }
        return new MeResponse(
                user.getFullName(),
                firstName,
                isBirthday,
                user.getEmail(),
                user.getCompany() != null ? user.getCompany().getName() : "",
                user.getCompany() != null ? user.getCompany().getId() : null,
                plan.name(),
                productLimit,
                expiresAt,
                hasUsedTrial,
                role,
                permissions
        );
    }

    // ── PUT /me/profile ─────────────────────────────────────────────────────
    // Body: { "firstName": "Juan", "lastName": "Pérez", "birthDate": "1995-08-20" }
    @Operation(summary = "Actualizar mi perfil",
            description = "Permite actualizar firstName, lastName y birthDate (YYYY-MM-DD).",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"firstName\":\"Ana\",\"lastName\":\"Pérez\",\"birthDate\":\"1995-08-20\"}"))))
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(
            Authentication authentication,
            @RequestBody Map<String, String> body) {

        User user = findUser(authentication);

        String firstName = body.get("firstName");
        String lastName  = body.get("lastName");
        String birthStr  = body.get("birthDate");

        if (firstName != null && !firstName.isBlank()) user.setFirstName(firstName.trim());
        if (lastName  != null && !lastName.isBlank())  user.setLastName(lastName.trim());

        if (birthStr != null && !birthStr.isBlank()) {
            try {
                user.setBirthDate(LocalDate.parse(birthStr)); // formato: YYYY-MM-DD
            } catch (Exception e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Formato de fecha inválido. Usa YYYY-MM-DD."));
            }
        }

        // Actualizar fullName con los nuevos datos
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last  = user.getLastName()  != null ? user.getLastName()  : "";
        if (!first.isBlank() || !last.isBlank()) {
            user.setFullName((first + " " + last).trim());
        }

        userRepository.save(user);

        return ResponseEntity.ok(Map.of(
                "message",   "Perfil actualizado correctamente.",
                "firstName", user.getFirstName() != null ? user.getFirstName() : "",
                "lastName",  user.getLastName()  != null ? user.getLastName()  : "",
                "fullName",  user.getFullName()  != null ? user.getFullName()  : ""
        ));
    }

    // ── Helper ──────────────────────────────────────────────────────────────
    private User findUser(Authentication authentication) {
        return userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }

    public record MeResponse(
            String fullName,
            String firstName,
            boolean isBirthday,
            String email,
            String companyName,
            Long companyId,
            String planName,
            int planLimit,
            LocalDateTime planExpiresAt,
            boolean trialUsed,
            String role,
            java.util.Set<String> permissions
    ) {}
}
