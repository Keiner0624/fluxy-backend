package com.fluxyBackend.invoicing.repository;

import com.fluxyBackend.invoicing.entity.DocumentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentEventRepository extends JpaRepository<DocumentEvent, Long> {

    List<DocumentEvent> findByDocumentIdAndCompanyIdOrderByCreatedAtAscIdAsc(Long documentId, Long companyId);

    @Modifying
    @Query("DELETE FROM DocumentEvent e WHERE e.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
