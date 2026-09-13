package com.fluxyBackend.controller;

import com.fluxyBackend.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Aceptación de invitaciones: sin JWT, la persona todavía no tiene cuenta. */
@Tag(name = "Invitaciones", description = "Aceptar una invitación al equipo de un negocio.")
@RestController
@RequestMapping("/auth/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final TeamService teamService;

    @Operation(summary = "Consultar una invitación",
            description = "Devuelve el negocio, el correo y el rol. valid es false si venció, fue revocada o ya se usó.")
    @GetMapping("/{token}")
    public TeamService.InvitationInfo info(@PathVariable String token) {
        return teamService.invitationInfo(token);
    }

    @Operation(summary = "Aceptar una invitación",
            description = "Crea la cuenta con fullName y password (8 a 72 caracteres) y devuelve el token de sesión.")
    @PostMapping("/{token}/accept")
    public TeamService.AcceptResult accept(@PathVariable String token, @RequestBody TeamService.AcceptRequest request) {
        return teamService.accept(token, request);
    }
}
