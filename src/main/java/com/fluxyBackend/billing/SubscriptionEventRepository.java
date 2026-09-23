package com.fluxyBackend.billing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEvent, Long> {
    List<SubscriptionEvent> findByCompanyIdOrderByCreatedAtDescIdDesc(Long companyId, Pageable pageable);

    long countByCompanyIdAndType(Long companyId, SubscriptionEvent.Type type);
}
