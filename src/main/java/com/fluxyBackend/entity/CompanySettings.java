package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class CompanySettings {
    @Id
    private Long companyId;
    @Column(nullable = false)
    private String country = "PE";
    @Column(nullable = false)
    private String currency = "PEN";
    @Column(nullable = false)
    private String language = "es-PE";
    @Column(nullable = false)
    private String timezone = "America/Lima";
    @Column(nullable = false)
    private String phoneCountryCode = "+51";

    public CompanySettings(Long companyId) { this.companyId = companyId; }
}
