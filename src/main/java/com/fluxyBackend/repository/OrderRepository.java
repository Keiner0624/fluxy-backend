package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByOwner(User owner);
    List<Order> findByCompany(Company company);
    Optional<Order> findByIdAndOwner(Long id, User owner);

    Optional<Order> findByIdAndCompany(Long id, Company company);
}
