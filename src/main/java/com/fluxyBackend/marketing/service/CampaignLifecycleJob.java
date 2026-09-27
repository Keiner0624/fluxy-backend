package com.fluxyBackend.marketing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pasa a ACTIVE las campañas programadas y finaliza las vencidas. La atribución ya mira las
 * fechas por su cuenta, así que un atraso de la tarea no cuenta visitas fuera de vigencia.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CampaignLifecycleJob {

    private final CampaignService campaigns;

    @Scheduled(initialDelayString = "${app.marketing.lifecycle_initial_delay_ms:45000}",
            fixedDelayString = "${app.marketing.lifecycle_every_ms:300000}")
    public void applyTransitions() {
        int changed = campaigns.applyScheduledTransitions();
        if (changed > 0) log.info("Marketing: {} campaña(s) cambiaron de estado por fecha", changed);
    }
}
