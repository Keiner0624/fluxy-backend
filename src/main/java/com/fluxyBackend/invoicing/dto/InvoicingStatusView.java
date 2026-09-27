package com.fluxyBackend.invoicing.dto;

/** Estado resumido para quien emite o consulta comprobantes (sin datos de configuración sensibles). */
public record InvoicingStatusView(String status, String statusReason, String environment, String providerLabel, boolean planAllowed,
                                  boolean canIssueReceipt, boolean canIssueInvoice, boolean automaticIssuing, String issueTrigger,
                                  boolean printAutomatically, int paperWidth, String businessName, String ruc) {
}
