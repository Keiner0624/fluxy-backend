package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Hoja del Libro de Reclamaciones virtual de Fluxy (Código de Protección y Defensa
 * del Consumidor y su reglamento). Es sobre el servicio de Fluxy; los reclamos por
 * productos de una tienda los atiende el comercio que los vende.
 */
@Entity
@Table(name = "complaints", indexes = {
        @Index(name = "idx_complaint_code", columnList = "code", unique = true),
        @Index(name = "idx_complaint_status_due", columnList = "status, due_date")
})
@Getter
@Setter
@NoArgsConstructor
public class Complaint {

    public enum Type { RECLAMO, QUEJA }
    public enum DocumentType { DNI, CE, PASAPORTE, RUC }
    public enum ItemType { PRODUCTO, SERVICIO }
    public enum Status { PENDING, ANSWERED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    @Column(nullable = false, length = 150)
    private String consumerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DocumentType documentType;

    @Column(nullable = false, length = 20)
    private String documentNumber;

    @Column(nullable = false, length = 300)
    private String address;

    @Column(length = 30)
    private String phone;

    @Column(nullable = false, length = 254)
    private String email;

    private boolean minor;

    @Column(length = 150)
    private String guardianName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ItemType itemType;

    @Column(nullable = false, length = 300)
    private String itemDescription;

    private Double amount;

    @Column(nullable = false, length = 3000)
    private String detail;

    @Column(nullable = false, length = 1500)
    private String consumerRequest;

    @Column(nullable = false)
    private Instant receivedAt;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(length = 3000)
    private String response;

    private Instant respondedAt;

    @Column(length = 120)
    private String respondedBy;

    @Column(length = 64)
    private String ipHash;

    @Column(length = 20)
    private String legalVersion;
}
