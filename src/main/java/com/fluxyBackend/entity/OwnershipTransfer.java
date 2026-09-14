package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Traspaso de la propiedad del negocio: lo inicia el dueño y lo acepta el nuevo dueño. */
@Entity
@Table(name = "ownership_transfers", indexes = @Index(name = "idx_transfers_company", columnList = "company_id"))
@Getter
@Setter
@NoArgsConstructor
public class OwnershipTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false)
    private Long fromUserId;

    @Column(nullable = false)
    private Long toUserId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime acceptedAt;
    private LocalDateTime cancelledAt;

    public boolean isPending() {
        return acceptedAt == null && cancelledAt == null && expiresAt.isAfter(LocalDateTime.now());
    }
}
