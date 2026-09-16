package com.fluxyBackend.controller;

import com.fluxyBackend.entity.LegalAcceptance;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.repository.LegalAcceptanceRepository;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Aceptación de la versión vigente de los Términos y la Política de Privacidad. */
@Tag(name = "Documentos legales")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/me/legal")
@RequiredArgsConstructor
public class LegalController {

    private final AccessService accessService;
    private final LegalAcceptanceRepository repository;
    private final AuditService auditService;

    public record LegalStatus(String currentVersion, String acceptedVersion, boolean needsAcceptance) {}

    public record AcceptRequest(String version) {}

    @Operation(summary = "Versión aceptada", description = "needsAcceptance es true si falta aceptar la versión vigente.")
    @GetMapping
    public LegalStatus status() {
        return status(accessService.current().user().getId());
    }

    @Operation(summary = "Aceptar la versión vigente", description = "version debe ser la vigente; queda registrada con fecha e IP como hash.")
    @PostMapping("/accept")
    @Transactional
    public LegalStatus accept(@RequestBody AcceptRequest request) {
        if (request == null || !LegalAcceptance.TERMS_VERSION.equals(request.version())) {
            throw new BusinessException(HttpStatus.CONFLICT, "LEGAL_VERSION_OUTDATED",
                    "Los documentos cambiaron. Recargá la página para ver la versión vigente.");
        }
        Member member = accessService.current();
        LegalStatus current = status(member.user().getId());
        if (current.needsAcceptance()) {
            repository.save(new LegalAcceptance(member.user().getId(), member.companyId(), LegalAcceptance.EVENT_UPDATED));
            auditService.record(member, AuditAction.TERMS_ACCEPTED, "USER", member.user().getId(),
                    Map.of("version", LegalAcceptance.TERMS_VERSION));
        }
        return status(member.user().getId());
    }

    private LegalStatus status(Long userId) {
        String accepted = repository.findFirstByUserIdOrderByAcceptedAtDesc(userId)
                .map(LegalAcceptance::getTermsVersion).orElse(null);
        return new LegalStatus(LegalAcceptance.TERMS_VERSION, accepted, !LegalAcceptance.TERMS_VERSION.equals(accepted));
    }
}
