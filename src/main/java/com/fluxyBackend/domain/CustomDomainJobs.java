package com.fluxyBackend.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Revisa en Vercel los dominios que esperan DNS (y, una vez por día, los activos). */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainJobs {

    private final CustomDomainService domains;

    @Scheduled(initialDelayString = "${app.domains.check_initial_delay_ms:60000}",
            fixedDelayString = "${app.domains.check_every_ms:600000}")
    public void checkPending() {
        try {
            int checked = domains.checkDue();
            if (checked > 0) log.info("Dominios propios: {} revisado(s)", checked);
        } catch (RuntimeException e) {
            log.warn("No se pudieron revisar los dominios propios: {}", e.getMessage());
        }
    }
}
