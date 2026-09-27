package com.fluxyBackend.invoicing.entity;

import com.fluxyBackend.invoicing.enums.DocumentEventType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Historial de un comprobante: qué pasó, quién o qué lo hizo y cuándo. */
@Entity
@Table(name = "document_events",
        indexes = @Index(name = "idx_document_event_document", columnList = "document_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class DocumentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DocumentEventType type;

    @Column(length = 600)
    private String message;

    @Column(length = 150)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public DocumentEvent(Long documentId, Long companyId, DocumentEventType type, String message, String actor) {
        this.documentId = documentId;
        this.companyId = companyId;
        this.type = type;
        this.message = message == null ? null : message.length() > 600 ? message.substring(0, 600) : message;
        this.actor = actor;
        this.createdAt = LocalDateTime.now();
    }
}
