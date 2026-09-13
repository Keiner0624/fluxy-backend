package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Membership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MembershipRepository extends JpaRepository<Membership, Long> {
    Optional<Membership> findByUserIdAndCompanyId(Long userId, Long companyId);
    List<Membership> findByCompanyId(Long companyId);

    @Modifying
    @Query("DELETE FROM Membership m WHERE m.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
