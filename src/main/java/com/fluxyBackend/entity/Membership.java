package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

/** Pertenencia de un usuario a una empresa, con su rol y sus permisos. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "company_id"}))
public class Membership {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "company_id", nullable = false)
    private Long companyId;
    private String role;
    private String status;
    private Instant joinedAt;

    /**
     * Permisos elegidos a mano, separados por coma. Null usa los del rol, así un
     * cambio en los permisos por defecto alcanza a todos los que no se tocaron.
     */
    @Column(length = 1000)
    private String permissions;

    private Long invitedBy;
    private Instant updatedAt;

    public Membership(Long userId, Long companyId) {
        this(userId, companyId, "OWNER");
    }

    public Membership(Long userId, Long companyId, String role) {
        this.userId = userId;
        this.companyId = companyId;
        this.role = role;
        this.status = STATUS_ACTIVE;
        this.joinedAt = Instant.now();
    }

    public boolean isActive() {
        return !STATUS_DISABLED.equals(status);
    }
}
