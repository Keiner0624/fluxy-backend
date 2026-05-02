package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping
    public Object me(Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        Plan plan = (user.getCompany() != null && user.getCompany().getPlan() != null)
                ? user.getCompany().getPlan()
                : Plan.FREE;

        int productLimit = switch (plan) {
            case PRO      -> 100;
            case BUSINESS -> 999999;
            default       -> 10;       // FREE
        };

        return new Object() {
            public final String fullName    = user.getFullName();
            public final String email       = user.getEmail();
            public final String companyName = user.getCompany() != null
                    ? user.getCompany().getName() : "";
            public final Long   companyId   = user.getCompany() != null
                    ? user.getCompany().getId() : null;
            public final String planName    = plan.name();          // "FREE" | "PRO" | "BUSINESS"
            public final int    planLimit   = productLimit;          // 10 | 100 | 999999
        };
    }
}
