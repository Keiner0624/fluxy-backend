package com.fluxyBackend.invoicing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retoma lo pendiente (envíos, reintentos, consultas de estado y correos) y revalida los RUC. */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoicingJobs {

    private final DocumentProcessor processor;
    private final DocumentEmailService emails;
    private final InvoicingConfigurationService configuration;

    @Scheduled(initialDelayString = "${app.invoicing.worker_initial_delay_ms:20000}",
            fixedDelayString = "${app.invoicing.worker_every_ms:15000}")
    public void work() {
        try {
            int documents = processor.processDue();
            int mails = emails.processDue();
            if (documents + mails > 0) log.info("Facturación: {} comprobante(s) y {} correo(s) procesados", documents, mails);
        } catch (RuntimeException e) {
            log.error("Error en la tarea de facturación", e);
        }
    }

    @Scheduled(cron = "0 20 4 * * *", zone = "America/Lima")
    public void revalidate() {
        int suspended = configuration.revalidateActive();
        if (suspended > 0) log.warn("Facturación: {} negocio(s) suspendidos al revalidar el RUC", suspended);
    }
}
