package com.fluxyBackend.invoicing.repository;

import com.fluxyBackend.invoicing.entity.ElectronicDocument;
import com.fluxyBackend.invoicing.enums.DocumentStatus;
import com.fluxyBackend.invoicing.enums.EmailStatus;
import com.fluxyBackend.invoicing.enums.Environment;
import com.fluxyBackend.invoicing.enums.ProviderCode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ElectronicDocumentRepository extends JpaRepository<ElectronicDocument, Long>,
        JpaSpecificationExecutor<ElectronicDocument> {

    /** Siempre por empresa: un controlador nunca busca un comprobante solo por id. */
    Optional<ElectronicDocument> findByIdAndCompanyId(Long id, Long companyId);

    Optional<ElectronicDocument> findByPublicToken(String publicToken);

    Optional<ElectronicDocument> findFirstByProviderAndProviderDocumentId(ProviderCode provider, String providerDocumentId);

    Optional<ElectronicDocument> findFirstByProviderAndIssuerRucAndSeriesAndNumber(ProviderCode provider, String issuerRuc,
                                                                                    String series, Long number);

    List<ElectronicDocument> findByCompanyIdAndOrderIdOrderByIdDesc(Long companyId, Long orderId);

    boolean existsByCompanyIdAndEnvironmentAndSeries(Long companyId, Environment environment, String series);

    /** Comprobantes de venta del pedido que siguen vigentes (no rechazados ni anulados). */
    @Query("""
            SELECT d FROM ElectronicDocument d
            WHERE d.companyId = :companyId AND d.orderId = :orderId
              AND d.type <> com.fluxyBackend.invoicing.enums.DocumentType.NOTA_CREDITO
              AND d.status IN :statuses
            """)
    List<ElectronicDocument> findActiveForOrder(@Param("companyId") Long companyId, @Param("orderId") Long orderId,
                                                @Param("statuses") Collection<DocumentStatus> statuses);

    /** Trabajo pendiente del procesador. */
    @Query("""
            SELECT d.id FROM ElectronicDocument d
            WHERE d.status IN :statuses AND d.nextAttemptAt IS NOT NULL AND d.nextAttemptAt <= :now
            ORDER BY d.nextAttemptAt
            """)
    List<Long> findDue(@Param("statuses") Collection<DocumentStatus> statuses, @Param("now") LocalDateTime now,
                       Pageable page);

    @Query("""
            SELECT d.id FROM ElectronicDocument d
            WHERE d.emailStatus = :status AND d.emailNextAttemptAt IS NOT NULL AND d.emailNextAttemptAt <= :now
            ORDER BY d.emailNextAttemptAt
            """)
    List<Long> findEmailDue(@Param("status") EmailStatus status, @Param("now") LocalDateTime now, Pageable page);

    /**
     * Toma un documento para enviarlo. Es condicional: si dos hilos lo intentan, solo uno cambia la
     * fila (devuelve 1). lease es cuándo se vuelve a intentar si el proceso se corta a mitad de camino.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ElectronicDocument d SET d.status = com.fluxyBackend.invoicing.enums.DocumentStatus.PROCESSING,
                   d.attempts = d.attempts + 1, d.nextAttemptAt = :lease, d.version = d.version + 1
            WHERE d.id = :id AND d.status IN :statuses AND d.nextAttemptAt IS NOT NULL AND d.nextAttemptAt <= :now
            """)
    int claim(@Param("id") Long id, @Param("statuses") Collection<DocumentStatus> statuses,
              @Param("now") LocalDateTime now, @Param("lease") LocalDateTime lease);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ElectronicDocument d SET d.emailNextAttemptAt = :lease, d.emailAttempts = d.emailAttempts + 1,
                   d.version = d.version + 1
            WHERE d.id = :id AND d.emailStatus = com.fluxyBackend.invoicing.enums.EmailStatus.PENDING
              AND d.emailNextAttemptAt IS NOT NULL AND d.emailNextAttemptAt <= :now
            """)
    int claimEmail(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("lease") LocalDateTime lease);

    long countByCompanyIdAndStatusIn(Long companyId, Collection<DocumentStatus> statuses);

    /** Para el aviso del menú: con error o rechazados en los últimos días. */
    @Query("""
            SELECT COUNT(d) FROM ElectronicDocument d
            WHERE d.companyId = :companyId
              AND (d.status = com.fluxyBackend.invoicing.enums.DocumentStatus.ERROR
                   OR (d.status = com.fluxyBackend.invoicing.enums.DocumentStatus.REJECTED AND d.rejectedAt >= :since))
            """)
    long countNeedingAttention(@Param("companyId") Long companyId, @Param("since") LocalDateTime since);

    long countByStatusIn(Collection<DocumentStatus> statuses);

    @Modifying
    @Query("DELETE FROM ElectronicDocumentItem i WHERE i.document.id IN (SELECT d.id FROM ElectronicDocument d WHERE d.companyId = :companyId)")
    void deleteItemsByCompanyId(@Param("companyId") Long companyId);

    @Modifying
    @Query("DELETE FROM ElectronicDocument d WHERE d.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
