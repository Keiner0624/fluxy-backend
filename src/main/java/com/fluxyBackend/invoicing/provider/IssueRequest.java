package com.fluxyBackend.invoicing.provider;

import com.fluxyBackend.invoicing.enums.CreditNoteReason;
import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.enums.IdentityDocumentType;
import com.fluxyBackend.invoicing.enums.TaxAffectation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Comprobante listo para enviar, armado desde la copia guardada en ElectronicDocument. */
public record IssueRequest(
        DocumentType type,
        String series,
        long number,
        LocalDate issueDate,
        String issuerRuc,
        String issuerName,
        IdentityDocumentType customerDocumentType,
        String customerDocumentNumber,
        String customerName,
        String customerAddress,
        String customerEmail,
        TaxAffectation taxAffectation,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal total,
        String currency,
        List<Item> items,
        Related related) {

    public record Item(String code, String description, BigDecimal quantity, BigDecimal unitValue,
                       BigDecimal unitPrice, BigDecimal subtotal, BigDecimal tax, BigDecimal total) {}

    /** Documento que modifica una nota de crédito. */
    public record Related(DocumentType type, String series, long number, CreditNoteReason reason, String description) {}

    public String fullNumber() {
        return series + "-" + number;
    }
}
