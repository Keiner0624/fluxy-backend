package com.fluxyBackend.invoicing.provider.implementations;

import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.enums.ProviderCode;
import com.fluxyBackend.invoicing.provider.*;
import com.fluxyBackend.security.Hashing;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Modo de prueba: acepta todo lo que pasó las validaciones de Fluxy y no envía nada a SUNAT.
 * Sus comprobantes van en una numeración aparte (Environment.TEST) y se marcan "sin valor tributario".
 */
@Component
public class SandboxBillingProvider implements ElectronicBillingProvider {

    @Override
    public ProviderCode code() {
        return ProviderCode.SANDBOX;
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return EnumSet.allOf(DocumentType.class);
    }

    @Override
    public boolean requiresCredentials() {
        return false;
    }

    @Override
    public ProviderResult issue(ProviderContext context, IssueRequest request) {
        String id = "SBX-" + request.issuerRuc() + "-" + request.type().sunatCode() + "-" + request.fullNumber();
        String hash = Hashing.sha256(id + "|" + request.total().toPlainString()).substring(0, 28);
        return new ProviderResult(ProviderResult.Outcome.ACCEPTED, id,
                "Aceptado en modo de prueba: no se envió a SUNAT.", null, null, null, hash, SunatQr.text(request, hash));
    }

    @Override
    public ProviderResult getStatus(ProviderContext context, IssueRequest request) {
        return issue(context, request);
    }

    @Override
    public ProviderHealth healthCheck(ProviderContext context) {
        return new ProviderHealth(true, "Modo de prueba: los comprobantes no se envían a SUNAT ni tienen valor tributario.");
    }

    @Override
    public byte[] download(ProviderContext context, String storageKey) {
        return null;
    }
}
