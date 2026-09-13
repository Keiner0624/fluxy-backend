package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.BusinessProfileRequest;
import com.fluxyBackend.service.BusinessProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/companies/onboarding")
@RequiredArgsConstructor
public class BusinessProfileController {
    private final BusinessProfileService service;

    @GetMapping
    public BusinessProfileService.Details get(Authentication authentication) {
        return service.get(authentication.getName());
    }

    @PutMapping
    public BusinessProfileService.Details save(Authentication authentication,
            @RequestBody @Valid BusinessProfileRequest request) {
        return service.save(authentication.getName(), request);
    }
}
