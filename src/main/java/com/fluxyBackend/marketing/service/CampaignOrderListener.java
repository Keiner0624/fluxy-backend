package com.fluxyBackend.marketing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Atribuye el pedido cuando ya está guardado: si la atribución falla, el pedido sigue en pie
 * y el comprador no se entera.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CampaignOrderListener {

    private final CampaignTrackingService tracking;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrder(CampaignOrderEvent event) {
        try {
            tracking.attributeOrder(event.orderId(), event.companyId(), event.sessionId());
        } catch (Exception e) {
            log.warn("No se pudo atribuir el pedido #{} a una campaña", event.orderId(), e);
        }
    }
}
