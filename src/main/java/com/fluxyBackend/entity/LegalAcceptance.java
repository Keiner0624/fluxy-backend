package com.fluxyBackend.entity;

import com.fluxyBackend.security.ClientInfo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

/** Qué versión de los Términos y la Política aceptó cada usuario, cuándo y desde dónde (IP como hash). */
@Entity
@Getter
@NoArgsConstructor
public class LegalAcceptance {
    // Igual que CURRENT_LEGAL_VERSION en el frontend (src/modules/landing/legal/documents.js).
    public static final String TERMS_VERSION = "2026-09-13";
    public static final String PRIVACY_VERSION = "2026-09-13";
    public static final String EVENT_REGISTERED = "BUSINESS_REGISTERED";
    public static final String EVENT_UPDATED = "TERMS_UPDATED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false)
    private Long companyId;
    private String termsVersion;
    private String privacyVersion;
    private Instant acceptedAt;
    private String event;
    @Column(length = 64)
    private String ipHash;

    public LegalAcceptance(Long userId, Long companyId) {
        this(userId, companyId, EVENT_REGISTERED);
    }

    public LegalAcceptance(Long userId, Long companyId, String event) {
        this.userId = userId;
        this.companyId = companyId;
        this.termsVersion = TERMS_VERSION;
        this.privacyVersion = PRIVACY_VERSION;
        this.acceptedAt = Instant.now();
        this.event = event;
        this.ipHash = ClientInfo.currentRequest() == null ? null : ClientInfo.ipHash(ClientInfo.currentIp());
    }

    public boolean isCurrent() {
        return TERMS_VERSION.equals(termsVersion) && PRIVACY_VERSION.equals(privacyVersion);
    }
}
