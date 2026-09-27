package com.fluxyBackend.invoicing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Webhooks ya procesados: el mismo aviso repetido (reintento o replay) no se aplica dos veces. */
@Entity
@Table(name = "invoicing_webhook_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_invoicing_webhook_event", columnNames = {"provider", "event_id"}))
@Getter
@Setter
@NoArgsConstructor
public class InvoicingWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String provider;

    @Column(name = "event_id", nullable = false, length = 120)
    private String eventId;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    public InvoicingWebhookEvent(String provider, String eventId, Long documentId) {
        this.provider = provider;
        this.eventId = eventId;
        this.documentId = documentId;
        this.receivedAt = LocalDateTime.now();
    }
}
