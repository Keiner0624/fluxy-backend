package com.fluxyBackend.invoicing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Fila de la lista de comprobantes. */
public record DocumentRow(Long id, String type, boolean test, String series, Long number, String fullNumber,
                          String customerName, String customerDocumentType, String customerDocumentNumber, BigDecimal total,
                          String status, String emailStatus, Long orderId, Long relatedDocumentId, Long creditNoteId,
                          OffsetDateTime issuedAt) {
}
