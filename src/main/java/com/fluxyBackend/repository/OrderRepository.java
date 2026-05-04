package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.security.core.parameters.P;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByOwner(User owner);
    List<Order> findByCompany(Company company);
    Optional<Order> findByIdAndOwner(Long id, User owner);
    Optional<Order> findByIdAndCompany(Long id, Company company);
    List<Order> findByCompanyId(Long companyId);

    @Modifying
    @Query("DELETE FROM OrderItem oi WHERE oi.order.id IN (SELECT o.id FROM Order o WHERE o.company.id = :companyId)")
    void deleteOrderItemsByCompanyId(@Param("companyId") Long companyId);

    @Modifying
    @Query("DELETE FROM Order o WHERE o.company.id = :companyId")
    void deleteOrdersByCompanyId(@Param("companyId") Long companyId);
}
