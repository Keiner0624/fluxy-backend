package com.fluxyBackend.repository;

import com.fluxyBackend.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
    List<PushSubscription> findByUser_Company_Id(Long companyId);
    boolean existsByEndpointAndUser_Id(String endpoint, Long userId);
}