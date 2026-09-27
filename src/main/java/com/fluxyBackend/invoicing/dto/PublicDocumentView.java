package com.fluxyBackend.invoicing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Lo que ve cualquiera con el enlace o el QR: sin correo ni dirección del cliente, documento enmascarado. */
public record PublicDocumentView(String type, String typeLabel, boolean test, String fullNumber, String status,
                                 String issuerRuc, String issuerName, String customerName, String customerDocument,
                                 LocalDate issueDate, BigDecimal subtotal, BigDecimal tax, BigDecimal total,
                                 List<Item> items, boolean pdfAvailable) {

    public record Item(String description, BigDecimal quantity, BigDecimal total) {}
}
