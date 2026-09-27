package com.fluxyBackend.invoicing.dto;

/**
 * Datos y preferencias que el negocio puede cambiar. No incluye capacidades ni estado: si llegan
 * en el JSON se ignoran (el servidor las calcula).
 */
public record InvoicingSettingsRequest(String tradeName, String fiscalAddress, String taxRegime, Boolean automaticIssuing,
                                       String issueTrigger, Boolean emailEnabled, Boolean attachPdf, Boolean attachXml,
                                       Boolean printAutomatically, Integer paperWidth, String taxAffectation) {
}
