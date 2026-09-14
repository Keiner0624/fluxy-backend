package com.fluxyBackend.repository;

import com.fluxyBackend.entity.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {
    Optional<UserIdentity> findByProviderAndProviderUserId(UserIdentity.Provider provider, String providerUserId);
    Optional<UserIdentity> findByUserIdAndProvider(Long userId, UserIdentity.Provider provider);
    List<UserIdentity> findByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM UserIdentity i WHERE i.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
