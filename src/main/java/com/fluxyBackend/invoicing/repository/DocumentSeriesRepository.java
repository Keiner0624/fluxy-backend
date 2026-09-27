package com.fluxyBackend.invoicing.repository;

import com.fluxyBackend.invoicing.entity.DocumentSeries;
import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.enums.Environment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentSeriesRepository extends JpaRepository<DocumentSeries, Long> {

    List<DocumentSeries> findByCompanyIdAndEnvironmentOrderByDocumentTypeAscSeriesAsc(Long companyId, Environment environment);

    Optional<DocumentSeries> findByIdAndCompanyId(Long id, Long companyId);

    boolean existsByCompanyIdAndEnvironmentAndDocumentTypeAndSeries(Long companyId, Environment environment,
                                                                     DocumentType type, String series);

    /**
     * Candidatas para numerar, como proyección (no entidades): si la serie quedara cargada en el
     * contexto antes del bloqueo, Hibernate devolvería esa copia vieja al obtener el lock.
     */
    @Query("""
            SELECT s.id AS id, s.series AS series FROM DocumentSeries s
            WHERE s.companyId = :companyId AND s.environment = :environment AND s.documentType = :type AND s.enabled = true
            ORDER BY s.series
            """)
    List<SeriesRef> enabledRefs(@Param("companyId") Long companyId, @Param("environment") Environment environment,
                                @Param("type") DocumentType type);

    interface SeriesRef {
        Long getId();
        String getSeries();
    }

    /** Bloquea la serie para reservar el siguiente número sin carreras entre cajas. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM DocumentSeries s WHERE s.id = :id")
    Optional<DocumentSeries> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query("DELETE FROM DocumentSeries s WHERE s.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
