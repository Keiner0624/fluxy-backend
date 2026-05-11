package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    // ── GET /me ─────────────────────────────────────────────────────────────
    @GetMapping
    public Object me(Authentication authentication) {
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
        final boolean birthdayFlag = isBirthday;

        return new Object() {
            public final String        fullName      = user.getFullName();
            public final String        firstName_    = firstName;       // "firstName" en JSON
            public final boolean       isBirthday    = birthdayFlag;
            public final String        email         = user.getEmail();
            public final String        companyName   = user.getCompany() != null ? user.getCompany().getName() : "";
            public final Long          companyId     = user.getCompany() != null ? user.getCompany().getId()   : null;
            public final String        planName      = plan.name();
            public final int           planLimit     = productLimit;
            public final LocalDateTime planExpiresAt = expiresAt;
            public final boolean       trialUsed     = hasUsedTrial;

            // Alias para que Jackson serialice como "firstName"
            public String getFirstName()  { return firstName_; }
            public boolean getIsBirthday(){ return isBirthday;  }
        };
    }

    // ── PUT /me/profile ─────────────────────────────────────────────────────
    // Body: { "firstName": "Juan", "lastName": "Pérez", "birthDate": "1995-08-20" }
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
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
    }
}