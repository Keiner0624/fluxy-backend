package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor
public class LegalAcceptance {
    public static final String TERMS_VERSION = "2026-05-05";
    public static final String PRIVACY_VERSION = "2026-05-05";
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

    public LegalAcceptance(Long userId, Long companyId) {
        this.userId = userId;
        this.companyId = companyId;
        this.termsVersion = TERMS_VERSION;
        this.privacyVersion = PRIVACY_VERSION;
        this.acceptedAt = Instant.now();
        this.event = "BUSINESS_REGISTERED";
    }
}
