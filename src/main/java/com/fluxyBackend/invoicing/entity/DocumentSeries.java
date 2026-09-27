package com.fluxyBackend.invoicing.entity;

import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.enums.Environment;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Serie y correlativo. El siguiente número se reserva en el servidor con la fila bloqueada
 * (DocumentNumberService): dos cajas a la vez nunca toman el mismo.
 */
@Entity
@Table(name = "document_series",
        uniqueConstraints = @UniqueConstraint(name = "uk_series_company_env_type_series",
                columnNames = {"company_id", "environment", "document_type", "series"}))
@Getter
@Setter
@NoArgsConstructor
public class DocumentSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Environment environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 16)
    private DocumentType documentType;

    @Column(nullable = false, length = 4)
    private String series;

    /** Último número usado; 0 si todavía no se emitió ninguno. */
    @Column(name = "current_number", nullable = false)
    private long currentNumber;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
