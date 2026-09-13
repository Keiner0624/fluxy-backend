package com.fluxyBackend.controller;

import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Equipo", description = "Usuarios internos, roles y permisos por módulo.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/team")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;
    private final AccessService accessService;

    @Operation(summary = "Ver el equipo",
            description = "Integrantes, invitaciones pendientes y los permisos por defecto de cada rol.")
    @GetMapping
    @RequirePermission(Permission.TEAM_VIEW)
    public TeamService.Team team() {
        return teamService.team(accessService.current());
    }

    @Operation(summary = "Invitar a alguien",
            description = "role ADMIN, SELLER o VIEWER. permissions opcional; sin él se usan los del rol. "
                    + "Devuelve el enlace de aceptación, que vence en 7 días.")
    @PostMapping("/invitations")
    @RequirePermission(Permission.TEAM_INVITE)
    public TeamService.InviteResult invite(@RequestBody TeamService.InviteRequest request) {
        return teamService.invite(accessService.current(), request);
    }

    @Operation(summary = "Revocar una invitación", description = "El enlace deja de funcionar.")
    @DeleteMapping("/invitations/{id}")
    @RequirePermission(Permission.TEAM_INVITE)
    public Map<String, String> revoke(@PathVariable Long id) {
        teamService.revokeInvitation(accessService.current(), id);
        return Map.of("message", "Invitación revocada.");
    }

    @Operation(summary = "Cambiar rol, permisos o acceso",
            description = "status DISABLED corta el acceso de inmediato. useRoleDefaults vuelve a los permisos del rol. "
                    + "Solo el dueño gestiona administradores; nadie puede modificar al dueño ni a sí mismo.")
    @PatchMapping("/members/{userId}")
    @RequirePermission(Permission.TEAM_MANAGE)
    public TeamService.MemberView update(@PathVariable Long userId,
                                         @RequestBody TeamService.UpdateMemberRequest request) {
        return teamService.updateMember(accessService.current(), userId, request);
    }
}
