package com.fluxyBackend.service;

import com.fluxyBackend.entity.CompanySettings;
import com.fluxyBackend.repository.CompanySettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Traduce entre la hora del servidor y la del negocio.
 *
 * Los pedidos guardan LocalDateTime en la hora del servidor, y Render corre en
 * UTC. Sin esta conversión "ventas de hoy" en Lima empezaría a las 7 de la
 * tarde del día anterior.
 */
@Component
@RequiredArgsConstructor
public class BusinessClock {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Lima");

    private final CompanySettingsRepository settingsRepository;

    public ZoneId zone(Long companyId) {
        return settingsRepository.findById(companyId)
                .map(CompanySettings::getTimezone)
                .map(BusinessClock::parseZone)
                .orElse(DEFAULT_ZONE);
    }

    public LocalDate today(ZoneId zone) {
        return LocalDate.now(zone);
    }

    /** Inicio del día del negocio expresado en hora del servidor, para consultar pedidos. */
    public LocalDateTime startOf(LocalDate businessDay, ZoneId zone) {
        return businessDay.atStartOfDay(zone)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    /** Día del negocio al que pertenece un instante guardado en hora del servidor. */
    public LocalDate businessDate(LocalDateTime serverTime, ZoneId zone) {
        return serverTime.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(zone)
                .toLocalDate();
    }

    /**
     * Agrega el desfase del servidor. Un LocalDateTime sin zona lo interpreta el
     * navegador en su hora local y mostraba los pedidos corridos cinco horas.
     */
    public static OffsetDateTime withOffset(LocalDateTime serverTime) {
        return serverTime == null ? null : serverTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException | NullPointerException e) {
            return DEFAULT_ZONE;
        }
    }
}
