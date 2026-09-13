package com.fluxyBackend.repository;

import com.fluxyBackend.entity.TeamInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamInvitationRepository extends JpaRepository<TeamInvitation, Long> {
    Optional<TeamInvitation> findByTokenHash(String tokenHash);
    Optional<TeamInvitation> findByIdAndCompanyId(Long id, Long companyId);
    List<TeamInvitation> findByCompanyIdOrderByCreatedAtDesc(Long companyId);
    List<TeamInvitation> findByCompanyIdAndEmailIgnoreCase(Long companyId, String email);

    @Modifying
    @Query("DELETE FROM TeamInvitation i WHERE i.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
