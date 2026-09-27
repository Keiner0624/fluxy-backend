package com.fluxyBackend.invoicing.dto;

/** Crear o ajustar una serie. currentNumber: último número ya usado (para continuar otra numeración). */
public record SeriesRequest(String documentType, String series, Boolean enabled, Long currentNumber) {
}
