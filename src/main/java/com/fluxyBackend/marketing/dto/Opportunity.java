package com.fluxyBackend.marketing.dto;

/**
 * Situación detectada con datos del negocio y la campaña que se sugiere. reason explica
 * con números por qué aparece; suggestion precarga el asistente.
 */
public record Opportunity(String key, String priority, String title, String reason, String actionLabel,
                          Suggestion suggestion) {

    public record Suggestion(String type, String objective, Long targetId, String segment, String name) {}
}
