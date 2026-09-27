package com.fluxyBackend.invoicing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Comprobante completo para el panel: estado, emisor, receptor, importes, ítems e historial.
 * actions dice qué se puede hacer según su estado (PDF, XML, PRINT, RESEND_EMAIL, PUBLIC_LINK,
 * CREDIT_NOTE, RETRY). Nunca incluye las URLs de archivos del proveedor.
 */
public record DocumentDetail(Long id, String type, String typeLabel, boolean test, String provider, String series, Long number,
                             String fullNumber, String status, String providerMessage, String lastError, int attempts,
                             OffsetDateTime nextAttemptAt, String issuerRuc, String issuerName, String issuerTradeName,
                             String issuerAddress, String customerDocumentType, String customerDocumentNumber,
                             String customerName, String customerEmail, String customerAddress, String taxAffectation,
                             BigDecimal subtotal, BigDecimal tax, BigDecimal discount, BigDecimal total, String currency,
                             String amountInWords, LocalDate issueDate, OffsetDateTime issuedAt, OffsetDateTime acceptedAt,
                             OffsetDateTime rejectedAt, String emailStatus, OffsetDateTime emailSentAt, Long orderId,
                             Related related, Related creditNote, String creditReason, String creditReasonLabel,
                             String creditDescription, String hash, String qrText, String publicUrl,
                             List<Item> items, List<Event> history, List<String> actions) {

    public record Item(int line, Long productId, String sku, String description, BigDecimal quantity,
                       BigDecimal unitPrice, BigDecimal unitValue, BigDecimal subtotal, BigDecimal tax, BigDecimal total) {}

    public record Event(String type, String message, String actor, OffsetDateTime createdAt) {}

    /** Comprobante relacionado: el que modifica una nota de crédito, o la nota que anula a uno de venta. */
    public record Related(Long id, String type, String fullNumber, String status) {}
}
