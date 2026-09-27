package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {
    List<Order> findByOwner(User owner);
    List<Order> findByCompany(Company company);
    Optional<Order> findByIdAndOwner(Long id, User owner);
    Optional<Order> findByIdAndCompany(Long id, Company company);
    Optional<Order> findByIdAndCompanyId(Long id, Long companyId);
    List<Order> findByCompanyId(Long companyId);
    List<Order> findTop5ByCompanyIdOrderByCreatedAtDescIdDesc(Long companyId);
    Page<Order> findByCompanyIdAndCustomer_Id(Long companyId, Long customerId, Pageable pageable);
    long countByCompanyIdAndStatus(Long companyId, OrderStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id AND o.company.id = :companyId")
    Optional<Order> findByIdAndCompanyIdForUpdate(@Param("id") Long id, @Param("companyId") Long companyId);

    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items i LEFT JOIN FETCH i.prodcut WHERE o.id = :id")
    Optional<Order> findDetailedById(@Param("id") Long id);

    /** Pedidos de un rango con sus productos, para métricas y reportes. */
    @Query("""
            SELECT DISTINCT o FROM Order o
            LEFT JOIN FETCH o.items i
            LEFT JOIN FETCH i.prodcut
            WHERE o.company.id = :companyId AND o.createdAt >= :from AND o.createdAt < :to
            """)
    List<Order> findDetailedInRange(@Param("companyId") Long companyId,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    @Query("SELECT o FROM Order o WHERE o.company.id = :companyId AND o.createdAt >= :from AND o.createdAt < :to")
    List<Order> findInRange(@Param("companyId") Long companyId,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to);

    @Query("SELECT o.status AS status, COUNT(o) AS total FROM Order o WHERE o.company.id = :companyId GROUP BY o.status")
    List<StatusCount> countByStatus(@Param("companyId") Long companyId);

    @Query("""
            SELECT o.customer.id AS customerId,
                   COUNT(o) AS orders,
                   SUM(CASE WHEN o.status IN :sale THEN 1 ELSE 0 END) AS saleOrders,
                   SUM(CASE WHEN o.status IN :sale THEN o.total ELSE 0.0 END) AS spent,
                   MAX(o.createdAt) AS lastOrderAt,
                   MIN(o.createdAt) AS firstOrderAt
            FROM Order o
            WHERE o.company.id = :companyId AND o.customer IS NOT NULL
            GROUP BY o.customer.id
            """)
    List<CustomerStats> customerStats(@Param("companyId") Long companyId,
                                      @Param("sale") Collection<OrderStatus> sale);

    /** Las mismas estadísticas, de un solo cliente (perfil). */
    @Query("""
            SELECT o.customer.id AS customerId,
                   COUNT(o) AS orders,
                   SUM(CASE WHEN o.status IN :sale THEN 1 ELSE 0 END) AS saleOrders,
                   SUM(CASE WHEN o.status IN :sale THEN o.total ELSE 0.0 END) AS spent,
                   MAX(o.createdAt) AS lastOrderAt,
                   MIN(o.createdAt) AS firstOrderAt
            FROM Order o
            WHERE o.company.id = :companyId AND o.customer.id = :customerId
            GROUP BY o.customer.id
            """)
    List<CustomerStats> customerStatsOf(@Param("companyId") Long companyId, @Param("customerId") Long customerId,
                                        @Param("sale") Collection<OrderStatus> sale);

    /** Compras (pedidos no cancelados) por cliente: base de los segmentos automáticos. */
    @Query("""
            SELECT o.customer.id AS customerId, COUNT(o) AS orders, SUM(o.total) AS spent,
                   MIN(o.createdAt) AS firstOrderAt, MAX(o.createdAt) AS lastOrderAt
            FROM Order o
            WHERE o.company.id = :companyId AND o.customer IS NOT NULL AND o.status <> :cancelled
            GROUP BY o.customer.id
            """)
    List<PurchaseStats> purchaseStats(@Param("companyId") Long companyId, @Param("cancelled") OrderStatus cancelled);

    @Query("""
            SELECT o.customer.id AS customerId, COUNT(o) AS orders, SUM(o.total) AS spent,
                   MIN(o.createdAt) AS firstOrderAt, MAX(o.createdAt) AS lastOrderAt
            FROM Order o
            WHERE o.company.id = :companyId AND o.customer.id = :customerId AND o.status <> :cancelled
            GROUP BY o.customer.id
            """)
    List<PurchaseStats> purchaseStatsOf(@Param("companyId") Long companyId, @Param("customerId") Long customerId,
                                        @Param("cancelled") OrderStatus cancelled);

    /** Unidades compradas de cada producto por un cliente, de más a menos (preferencias). */
    @Query("""
            SELECT p.id AS productId, p.name AS name, c.name AS category, SUM(i.quantity) AS units
            FROM OrderItem i JOIN i.order o JOIN i.prodcut p LEFT JOIN p.category c
            WHERE o.company.id = :companyId AND o.customer.id = :customerId AND o.status <> :cancelled
            GROUP BY p.id, p.name, c.name
            ORDER BY SUM(i.quantity) DESC, p.name
            """)
    List<CustomerProduct> customerProducts(@Param("companyId") Long companyId, @Param("customerId") Long customerId,
                                           @Param("cancelled") OrderStatus cancelled);

    @Query("SELECT o FROM Order o WHERE o.company.id = :companyId AND o.customer IS NULL ORDER BY o.id")
    List<Order> findWithoutCustomer(@Param("companyId") Long companyId);

    @Query("SELECT DISTINCT o.company.id FROM Order o WHERE o.customer IS NULL AND o.company IS NOT NULL")
    List<Long> findCompanyIdsWithOrdersWithoutCustomer();

    @Modifying
    @Query("DELETE FROM OrderItem oi WHERE oi.order.id IN (SELECT o.id FROM Order o WHERE o.company.id = :companyId)")
    void deleteOrderItemsByCompanyId(@Param("companyId") Long companyId);

    @Modifying
    @Query("DELETE FROM Order o WHERE o.company.id = :companyId")
    void deleteOrdersByCompanyId(@Param("companyId") Long companyId);

    interface StatusCount {
        OrderStatus getStatus();
        Long getTotal();
    }

    interface PurchaseStats {
        Long getCustomerId();
        long getOrders();
        Double getSpent();
        LocalDateTime getFirstOrderAt();
        LocalDateTime getLastOrderAt();
    }

    interface CustomerProduct {
        Long getProductId();
        String getName();
        String getCategory();
        Long getUnits();
    }

    interface CustomerStats {
        Long getCustomerId();
        Long getOrders();
        Long getSaleOrders();
        Double getSpent();
        LocalDateTime getLastOrderAt();
        LocalDateTime getFirstOrderAt();
    }
}
