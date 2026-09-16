package com.fluxyBackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    public enum Status {
        /** Registrado, pero todavía sin verificar el correo o el celular. No tiene empresa. */
        PENDING_VERIFICATION,
        ACTIVE,
        /** Bloqueado por la plataforma. */
        DISABLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    // ── Datos personales del vendedor ──────────────────────────────────────
    private String    firstName;
    private String    lastName;
    private LocalDate birthDate;
    // ───────────────────────────────────────────────────────────────────────

    @Column(nullable = false, unique = true)
    private String email;

    /**
     * Hash BCrypt. Las cuentas creadas con Google o Apple guardan un hash de un
     * valor aleatorio que nadie conoce y passwordEnabled en false.
     */
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @OneToMany(mappedBy = "owner")
    @JsonIgnore
    private List<Prodcut> prodcuts;

    @ManyToOne
    @JoinColumn(name = "company_id")
    private Company company;

    // ── Seguridad de la cuenta ─────────────────────────────────────────────
    // Columnas nullables: ddl-auto las agrega sobre una tabla con datos. Los
    // getters interpretan null como el valor de las cuentas existentes.

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Status status;

    /** Celular del titular, con código de país y sin símbolos: 51987654321. */
    @Column(length = 20)
    private String phone;

    private LocalDateTime emailVerifiedAt;
    private LocalDateTime phoneVerifiedAt;

    /** false en cuentas creadas solo con Google o Apple. */
    private Boolean passwordEnabled;

    private LocalDateTime passwordChangedAt;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;

    public Status getStatus() {
        return status == null ? Status.ACTIVE : status;
    }

    public boolean hasPassword() {
        return passwordEnabled == null || passwordEnabled;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = Status.ACTIVE;
    }
}
