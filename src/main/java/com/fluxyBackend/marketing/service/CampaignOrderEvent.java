package com.fluxyBackend.marketing.service;

/** Pedido de la tienda que trae la sesión anónima del navegador, para atribuirlo a una campaña. */
public record CampaignOrderEvent(Long orderId, Long companyId, String sessionId) {
}
