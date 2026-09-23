package com.fluxyBackend.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fin de periodo y avisos. Cada pasada es reintentable: una suscripción ya cerrada no vuelve a cerrarse.
 * El acceso no espera a esta tarea: EntitlementService ya trata como Free un plan vencido.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionLifecycleJob {

    private final SubscriptionService subscriptionService;

    @Scheduled(initialDelayString = "${app.billing.lifecycle_initial_delay_ms:30000}",
            fixedDelayString = "${app.billing.lifecycle_every_ms:300000}")
    public void closeEndedPeriods() {
        int applied = subscriptionService.applyDueTransitions();
        if (applied > 0) log.info("Suscripciones: {} fin(es) de periodo aplicados", applied);
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "America/Lima")
    public void remindUpcomingEnds() {
        int sent = subscriptionService.sendReminders();
        if (sent > 0) log.info("Suscripciones: {} aviso(s) de fin de periodo", sent);
    }
}
