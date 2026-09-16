package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Complaint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    Page<Complaint> findByStatus(Complaint.Status status, Pageable pageable);
    Optional<Complaint> findByCode(String code);
    long countByStatus(Complaint.Status status);
}
