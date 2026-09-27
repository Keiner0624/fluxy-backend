package com.fluxyBackend.marketing.dto;

/**
 * Enlace rastreable de una campaña para un canal.
 *
 * @param url      enlace completo a la tienda
 * @param path     ruta de la tienda (/store/slug), por si el panel conoce otro dominio público
 * @param query    parámetros del enlace, empezando con "?"
 * @param text     mensaje listo para pegar, con el enlace al final
 * @param shareUrl enlace para compartir directo (WhatsApp, Facebook); null si el canal no tiene
 * @param autoPublish siempre false en V1: la publicación la hace el comercio
 */
public record CampaignLink(String channel, String url, String path, String query, String text, String shareUrl,
                           boolean autoPublish) {
}
