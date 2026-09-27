package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.invoicing.entity.DocumentSeries;
import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.enums.Environment;
import com.fluxyBackend.invoicing.repository.DocumentSeriesRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 * Correlativos. El número nunca lo decide el panel: se reserva acá, con la fila de la serie
 * bloqueada (SELECT ... FOR UPDATE), dentro de la transacción que guarda el comprobante. Si esa
 * transacción falla, el número vuelve a quedar libre.
 */
@Service
@RequiredArgsConstructor
public class DocumentNumberService {

    private final DocumentSeriesRepository series;
    private final EntityManager entityManager;

    public record Reserved(String series, long number) {}

    /**
     * prefix: primera letra que tiene que tener la serie (B o F); ' ' si no importa.
     * preferred: serie pedida explícitamente, o null para la primera habilitada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reserved next(Long companyId, Environment environment, DocumentType type, char prefix, String preferred) {
        DocumentSeriesRepository.SeriesRef chosen = series.enabledRefs(companyId, environment, type).stream()
                .filter(s -> prefix == ' ' || s.getSeries().charAt(0) == prefix)
                .filter(s -> preferred == null || s.getSeries().equalsIgnoreCase(preferred))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SERIES_NOT_AVAILABLE",
                        preferred != null ? "La serie " + preferred + " no está habilitada."
                                : "No hay una serie habilitada para " + type.label().toLowerCase() + "."));
        DocumentSeries locked = series.findByIdForUpdate(chosen.getId()).orElseThrow();
        // Con el lock tomado se relee la fila: el valor que cuenta es el que dejó la otra caja.
        entityManager.refresh(locked);
        if (locked.getCurrentNumber() >= 99_999_999L) {
            throw BusinessException.conflict("SERIES_EXHAUSTED", "La serie " + locked.getSeries() + " llegó a su último número.");
        }
        locked.setCurrentNumber(locked.getCurrentNumber() + 1);
        series.save(locked);
        return new Reserved(locked.getSeries(), locked.getCurrentNumber());
    }
}
