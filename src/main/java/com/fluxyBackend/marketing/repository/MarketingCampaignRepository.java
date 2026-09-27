package com.fluxyBackend.marketing.repository;

import com.fluxyBackend.marketing.entity.MarketingCampaign;
import com.fluxyBackend.marketing.enums.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MarketingCampaignRepository extends JpaRepository<MarketingCampaign, Long> {

    List<MarketingCampaign> findByCompanyIdOrderByCreatedAtDescIdDesc(Long companyId);

    Optional<MarketingCampaign> findByIdAndCompanyId(Long id, Long companyId);

    Optional<MarketingCampaign> findByTrackingCode(String trackingCode);

    boolean existsByTrackingCode(String trackingCode);

    long countByCompanyIdAndStatusIn(Long companyId, Collection<CampaignStatus> statuses);

    List<MarketingCampaign> findByCompanyIdAndStatusIn(Long companyId, Collection<CampaignStatus> statuses);

    /** Programadas cuyo inicio ya llegó. */
    List<MarketingCampaign> findByStatusAndStartsAtLessThanEqual(CampaignStatus status, LocalDateTime now);

    /** Vigentes cuya fecha de fin ya pasó. */
    List<MarketingCampaign> findByStatusInAndEndsAtLessThanEqual(Collection<CampaignStatus> statuses, LocalDateTime now);

    @Modifying
    @Query("DELETE FROM MarketingCampaign c WHERE c.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
