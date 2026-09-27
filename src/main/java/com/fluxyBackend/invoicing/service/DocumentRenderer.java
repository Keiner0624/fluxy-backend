package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.invoicing.entity.ElectronicDocument;
import com.fluxyBackend.invoicing.entity.ElectronicDocumentItem;
import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.enums.Environment;
import com.fluxyBackend.invoicing.enums.IdentityDocumentType;
import com.fluxyBackend.invoicing.enums.TaxAffectation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Representación impresa (PDF A4) y XML de los comprobantes que genera Fluxy (modo de prueba).
 * El XML sigue la estructura UBL 2.1 pero no va firmado: firmar y enviar a SUNAT es trabajo del proveedor.
 */
public final class DocumentRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private DocumentRenderer() {
    }

    public static LocalDate issueDate(ElectronicDocument doc) {
        return doc.getIssuedAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(LIMA).toLocalDate();
    }

    // ─── PDF ─────────────────────────────────────────────────────────────────

    public static byte[] pdf(ElectronicDocument doc, ElectronicDocument related, String publicUrl) {
        SimplePdf pdf = new SimplePdf();
        float left = 40;
        float right = SimplePdf.A4_WIDTH - 40;
        float y = SimplePdf.A4_HEIGHT - 50;
        boolean test = doc.getEnvironment() == Environment.TEST;

        // Emisor
        String name = doc.getIssuerTradeName() != null ? doc.getIssuerTradeName() : doc.getIssuerName();
        pdf.text(left, y, 15, true, name);
        float iy = y - 16;
        if (!name.equalsIgnoreCase(doc.getIssuerName())) {
            pdf.text(left, iy, 9, false, doc.getIssuerName());
            iy -= 12;
        }
        if (doc.getIssuerAddress() != null) {
            for (String line : SimplePdf.wrap(doc.getIssuerAddress(), 9, 290)) {
                pdf.text(left, iy, 9, false, line);
                iy -= 12;
            }
        }

        // Recuadro del comprobante
        float boxX = 360;
        float boxW = right - boxX;
        pdf.rect(boxX, y - 58, boxW, 72, false);
        pdf.text(boxX + 12, y - 2, 11, true, "R.U.C. N° " + doc.getIssuerRuc());
        List<String> title = SimplePdf.wrap(doc.getType().label().toUpperCase(), 10, boxW - 24);
        float ty = y - 20;
        for (String line : title) {
            pdf.text(boxX + 12, ty, 10, true, line);
            ty -= 13;
        }
        pdf.text(boxX + 12, y - 50, 12, true, doc.fullNumber());

        y = Math.min(iy, y - 70) - 14;
        if (test) {
            pdf.color(0.75f, 0.1f, 0.1f);
            pdf.text(left, y, 10, true, "DOCUMENTO DE PRUEBA — SIN VALOR TRIBUTARIO (no enviado a SUNAT)");
            pdf.color(0, 0, 0);
            y -= 18;
        }

        // Receptor
        pdf.line(left, y + 6, right, y + 6, 0.5f);
        y -= 10;
        y = row(pdf, left, y, "Fecha de emisión:", issueDate(doc).format(DATE));
        y = row(pdf, left, y, "Cliente:", doc.getCustomerName());
        if (doc.getCustomerDocumentType() != IdentityDocumentType.NINGUNO) {
            y = row(pdf, left, y, doc.getCustomerDocumentType().label() + ":", doc.getCustomerDocumentNumber());
        }
        if (doc.getCustomerAddress() != null) y = row(pdf, left, y, "Dirección:", doc.getCustomerAddress());
        y = row(pdf, left, y, "Moneda:", "SOLES");
        if (doc.getType() == DocumentType.NOTA_CREDITO && related != null) {
            y = row(pdf, left, y, "Documento que modifica:", related.getType().label() + " " + related.fullNumber());
            y = row(pdf, left, y, "Motivo:", doc.getCreditReason().sunatCode() + " " + doc.getCreditReason().label()
                    + (doc.getCreditDescription() != null ? " — " + doc.getCreditDescription() : ""));
        }

        // Ítems
        y -= 8;
        float qtyX = left;
        float descX = left + 50;
        float unitRight = right - 80;
        float totalRight = right;
        pdf.color(0.93f, 0.94f, 0.96f);
        pdf.rect(left, y - 5, right - left, 18, true);
        pdf.color(0, 0, 0);
        pdf.text(qtyX + 4, y, 9, true, "Cant.");
        pdf.text(descX, y, 9, true, "Descripción");
        pdf.textRight(unitRight, y, 9, true, "P. unit.");
        pdf.textRight(totalRight - 4, y, 9, true, "Importe");
        y -= 20;
        for (ElectronicDocumentItem item : doc.getItems()) {
            List<String> desc = SimplePdf.wrap(item.getDescription(), 9, unitRight - descX - 70);
            if (y - desc.size() * 11 < 150) {
                pdf.newPage();
                y = SimplePdf.A4_HEIGHT - 60;
            }
            pdf.text(qtyX + 4, y, 9, false, quantity(item.getQuantity()));
            pdf.textRight(unitRight, y, 9, false, money(item.getUnitPrice()));
            pdf.textRight(totalRight - 4, y, 9, false, money(item.getTotal()));
            for (String line : desc) {
                pdf.text(descX, y, 9, false, line);
                y -= 11;
            }
            y -= 4;
        }
        pdf.line(left, y + 6, right, y + 6, 0.5f);

        // Totales
        y -= 10;
        float labelRight = right - 90;
        String base = switch (doc.getTaxAffectation()) {
            case GRAVADO -> "Op. gravada";
            case EXONERADO -> "Op. exonerada";
            case INAFECTO -> "Op. inafecta";
        };
        if (doc.getDiscount() != null && doc.getDiscount().signum() > 0) {
            y = total(pdf, labelRight, right, y, "Descuentos", doc.getDiscount(), false);
        }
        y = total(pdf, labelRight, right, y, base, doc.getSubtotal(), false);
        y = total(pdf, labelRight, right, y, doc.getTaxAffectation() == TaxAffectation.GRAVADO ? "IGV 18%" : "IGV", doc.getTax(), false);
        y = total(pdf, labelRight, right, y, "Importe total", doc.getTotal(), true);

        y -= 6;
        pdf.text(left, y, 9, true, "SON: " + AmountInWords.soles(doc.getTotal()));
        y -= 26;

        String footer = "Representación impresa de la " + doc.getType().label().toLowerCase() + "."
                + (publicUrl != null ? " Consultala en " + publicUrl : "");
        for (String line : SimplePdf.wrap(footer, 8, right - left)) {
            pdf.text(left, y, 8, false, line);
            y -= 10;
        }
        if (doc.getHashCode() != null) pdf.text(left, y, 8, false, "Resumen: " + doc.getHashCode());
        return pdf.build();
    }

    private static float row(SimplePdf pdf, float left, float y, String label, String value) {
        pdf.text(left, y, 9, true, label);
        List<String> lines = SimplePdf.wrap(value == null ? "—" : value, 9, 380);
        for (String line : lines) {
            pdf.text(left + 120, y, 9, false, line);
            y -= 12;
        }
        return y;
    }

    private static float total(SimplePdf pdf, float labelRight, float right, float y, String label, BigDecimal value, boolean bold) {
        pdf.textRight(labelRight, y, bold ? 10 : 9, bold, label);
        pdf.textRight(right - 4, y, bold ? 10 : 9, bold, "S/ " + money(value));
        return y - (bold ? 15 : 13);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String quantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    // ─── XML ─────────────────────────────────────────────────────────────────

    public static byte[] xml(ElectronicDocument doc, ElectronicDocument related) {
        boolean credit = doc.getType() == DocumentType.NOTA_CREDITO;
        String root = credit ? "CreditNote" : "Invoice";
        String ns = credit ? "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2"
                : "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
        String[] scheme = switch (doc.getTaxAffectation()) {
            case GRAVADO -> new String[]{"1000", "IGV", "VAT"};
            case EXONERADO -> new String[]{"9997", "EXO", "VAT"};
            case INAFECTO -> new String[]{"9998", "INA", "FRE"};
        };
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        if (doc.getEnvironment() == Environment.TEST) {
            x.append("<!-- Documento de prueba generado por Fluxy: sin firma digital ni valor tributario. -->\n");
        }
        x.append('<').append(root).append(" xmlns=\"").append(ns).append("\"")
                .append(" xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\"")
                .append(" xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">\n");
        el(x, 1, "cbc:UBLVersionID", "2.1");
        el(x, 1, "cbc:CustomizationID", "2.0");
        el(x, 1, "cbc:ID", doc.getSeries() + "-" + doc.getNumber());
        el(x, 1, "cbc:IssueDate", issueDate(doc).toString());
        if (!credit) x.append("  <cbc:InvoiceTypeCode listID=\"0101\">").append(doc.getType().sunatCode()).append("</cbc:InvoiceTypeCode>\n");
        el(x, 1, "cbc:DocumentCurrencyCode", doc.getCurrency());
        if (credit && related != null) {
            x.append("  <cac:DiscrepancyResponse>\n");
            el(x, 2, "cbc:ReferenceID", related.getSeries() + "-" + related.getNumber());
            el(x, 2, "cbc:ResponseCode", doc.getCreditReason().sunatCode());
            el(x, 2, "cbc:Description", doc.getCreditDescription() != null ? doc.getCreditDescription() : doc.getCreditReason().label());
            x.append("  </cac:DiscrepancyResponse>\n  <cac:BillingReference>\n    <cac:InvoiceDocumentReference>\n");
            el(x, 3, "cbc:ID", related.getSeries() + "-" + related.getNumber());
            el(x, 3, "cbc:DocumentTypeCode", related.getType().sunatCode());
            x.append("    </cac:InvoiceDocumentReference>\n  </cac:BillingReference>\n");
        }
        party(x, "cac:AccountingSupplierParty", "6", doc.getIssuerRuc(), doc.getIssuerName());
        party(x, "cac:AccountingCustomerParty", doc.getCustomerDocumentType().sunatCode(),
                doc.getCustomerDocumentNumber() == null ? "-" : doc.getCustomerDocumentNumber(), doc.getCustomerName());
        x.append("  <cac:TaxTotal>\n");
        amount(x, 2, "cbc:TaxAmount", doc.getTax());
        x.append("    <cac:TaxSubtotal>\n");
        amount(x, 3, "cbc:TaxableAmount", doc.getSubtotal());
        amount(x, 3, "cbc:TaxAmount", doc.getTax());
        taxCategory(x, 3, scheme);
        x.append("    </cac:TaxSubtotal>\n  </cac:TaxTotal>\n");
        x.append("  <cac:LegalMonetaryTotal>\n");
        amount(x, 2, "cbc:LineExtensionAmount", doc.getSubtotal());
        amount(x, 2, "cbc:TaxInclusiveAmount", doc.getTotal());
        amount(x, 2, "cbc:PayableAmount", doc.getTotal());
        x.append("  </cac:LegalMonetaryTotal>\n");
        String lineTag = credit ? "cac:CreditNoteLine" : "cac:InvoiceLine";
        String qtyTag = credit ? "cbc:CreditedQuantity" : "cbc:InvoicedQuantity";
        for (ElectronicDocumentItem item : doc.getItems()) {
            x.append("  <").append(lineTag).append(">\n");
            el(x, 2, "cbc:ID", String.valueOf(item.getLineNumber()));
            x.append("    <").append(qtyTag).append(" unitCode=\"NIU\">").append(item.getQuantity().stripTrailingZeros().toPlainString())
                    .append("</").append(qtyTag).append(">\n");
            amount(x, 2, "cbc:LineExtensionAmount", item.getSubtotal());
            x.append("    <cac:TaxTotal>\n");
            amount(x, 3, "cbc:TaxAmount", item.getTax());
            x.append("      <cac:TaxSubtotal>\n");
            amount(x, 4, "cbc:TaxableAmount", item.getSubtotal());
            amount(x, 4, "cbc:TaxAmount", item.getTax());
            taxCategory(x, 4, scheme);
            x.append("      </cac:TaxSubtotal>\n    </cac:TaxTotal>\n    <cac:Item>\n");
            el(x, 3, "cbc:Description", item.getDescription());
            x.append("    </cac:Item>\n    <cac:Price>\n");
            amount(x, 3, "cbc:PriceAmount", item.getUnitValue());
            x.append("    </cac:Price>\n  </").append(lineTag).append(">\n");
        }
        x.append("</").append(root).append(">\n");
        return x.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void party(StringBuilder x, String tag, String schemeId, String id, String name) {
        x.append("  <").append(tag).append(">\n    <cac:Party>\n      <cac:PartyIdentification>\n");
        x.append("        <cbc:ID schemeID=\"").append(escape(schemeId)).append("\">").append(escape(id)).append("</cbc:ID>\n");
        x.append("      </cac:PartyIdentification>\n      <cac:PartyLegalEntity>\n");
        el(x, 4, "cbc:RegistrationName", name);
        x.append("      </cac:PartyLegalEntity>\n    </cac:Party>\n  </").append(tag).append(">\n");
    }

    private static void taxCategory(StringBuilder x, int depth, String[] scheme) {
        String pad = "  ".repeat(depth);
        x.append(pad).append("<cac:TaxCategory>\n").append(pad).append("  <cac:TaxScheme>\n");
        el(x, depth + 2, "cbc:ID", scheme[0]);
        el(x, depth + 2, "cbc:Name", scheme[1]);
        el(x, depth + 2, "cbc:TaxTypeCode", scheme[2]);
        x.append(pad).append("  </cac:TaxScheme>\n").append(pad).append("</cac:TaxCategory>\n");
    }

    private static void amount(StringBuilder x, int depth, String tag, BigDecimal value) {
        x.append("  ".repeat(depth)).append('<').append(tag).append(" currencyID=\"PEN\">")
                .append(value.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("</").append(tag).append(">\n");
    }

    private static void el(StringBuilder x, int depth, String tag, String value) {
        x.append("  ".repeat(depth)).append('<').append(tag).append('>').append(escape(value)).append("</").append(tag).append(">\n");
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
