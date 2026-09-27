package com.fluxyBackend.invoicing.provider;

/**
 * Texto del código QR de la representación impresa:
 * RUC | tipo | serie | número | IGV | total | fecha | tipo doc. cliente | número doc. cliente | hash |
 */
public final class SunatQr {

    private SunatQr() {
    }

    public static String text(IssueRequest r, String hash) {
        return String.join("|",
                r.issuerRuc(),
                r.type().sunatCode(),
                r.series(),
                String.valueOf(r.number()),
                r.tax().toPlainString(),
                r.total().toPlainString(),
                r.issueDate().toString(),
                r.customerDocumentType().sunatCode(),
                r.customerDocumentNumber() == null ? "" : r.customerDocumentNumber(),
                hash == null ? "" : hash) + "|";
    }
}
