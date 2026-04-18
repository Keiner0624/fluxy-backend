package com.fluxyBackend.controller;

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

        return new Object() {
            public final String fullName = user.getFullName();
            public final String email = user.getEmail();
            public final String companyName = user.getCompany() != null ?
                    user.getCompany().getName() : "";
        };
    }
}
