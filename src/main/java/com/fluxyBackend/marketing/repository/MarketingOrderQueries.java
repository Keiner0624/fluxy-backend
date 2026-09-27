package com.fluxyBackend.marketing.repository;

import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lecturas de pedidos que usa Marketing (segmentos, oportunidades, cupones). Solo consulta:
 * los pedidos se siguen escribiendo en OrderService.
 */
public interface MarketingOrderQueries extends Repository<Order, Long> {

    /** Historial de compra por cliente, sin pedidos cancelados. */
    @Query("""
            SELECT o.customer.id AS customerId, COUNT(o) AS orders, SUM(o.total) AS spent,
                   MIN(o.createdAt) AS firstOrderAt, MAX(o.createdAt) AS lastOrderAt
            FROM Order o
            WHERE o.company.id = :companyId AND o.customer IS NOT NULL AND o.status <> :cancelled
            GROUP BY o.customer.id
            """)
    List<BuyerStats> buyerStats(@Param("companyId") Long companyId, @Param("cancelled") OrderStatus cancelled);

    @Query("""
            SELECT DISTINCT i.order.customer.id FROM OrderItem i
            WHERE i.order.company.id = :companyId AND i.prodcut.category.id = :categoryId
              AND i.order.customer IS NOT NULL AND i.order.status <> :cancelled
            """)
    List<Long> categoryBuyers(@Param("companyId") Long companyId, @Param("categoryId") Long categoryId,
                              @Param("cancelled") OrderStatus cancelled);

    /** Unidades vendidas por producto desde una fecha. */
    @Query("""
            SELECT i.prodcut.id AS productId, SUM(i.quantity) AS units
            FROM OrderItem i
            WHERE i.order.company.id = :companyId AND i.order.createdAt >= :since AND i.order.status <> :cancelled
            GROUP BY i.prodcut.id
            """)
    List<ProductUnits> productUnits(@Param("companyId") Long companyId, @Param("since") LocalDateTime since,
                                    @Param("cancelled") OrderStatus cancelled);

    /** Pedidos e importe por categoría en un rango. */
    @Query("""
            SELECT i.prodcut.category.id AS categoryId, i.prodcut.category.name AS name,
                   COUNT(DISTINCT i.order.id) AS orders, SUM(i.subTotal) AS revenue
            FROM OrderItem i
            WHERE i.order.company.id = :companyId AND i.order.createdAt >= :from AND i.order.createdAt < :to
              AND i.order.status <> :cancelled
            GROUP BY i.prodcut.category.id, i.prodcut.category.name
            """)
    List<CategorySales> categorySales(@Param("companyId") Long companyId, @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to, @Param("cancelled") OrderStatus cancelled);

    /** Pedidos e importe de la tienda en un rango. */
    @Query("""
            SELECT COUNT(o) AS orders, SUM(o.total) AS total FROM Order o
            WHERE o.company.id = :companyId AND o.createdAt >= :from AND o.createdAt < :to AND o.status <> :cancelled
            """)
    SalesWindow sales(@Param("companyId") Long companyId, @Param("from") LocalDateTime from,
                      @Param("to") LocalDateTime to, @Param("cancelled") OrderStatus cancelled);

    /** Pedidos que usaron un cupón en un rango. code en mayúsculas. */
    @Query("""
            SELECT COUNT(o) AS orders, SUM(o.total) AS total, SUM(o.discountAmount) AS discount FROM Order o
            WHERE o.company.id = :companyId AND UPPER(o.couponCode) = :code
              AND o.createdAt >= :from AND o.createdAt < :to AND o.status <> :cancelled
            """)
    CouponUse couponUse(@Param("companyId") Long companyId, @Param("code") String code,
                        @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                        @Param("cancelled") OrderStatus cancelled);

    interface BuyerStats {
        Long getCustomerId();
        long getOrders();
        Double getSpent();
        LocalDateTime getFirstOrderAt();
        LocalDateTime getLastOrderAt();
    }

    interface ProductUnits {
        Long getProductId();
        Long getUnits();
    }

    interface CategorySales {
        Long getCategoryId();
        String getName();
        long getOrders();
        Double getRevenue();
    }

    interface SalesWindow {
        long getOrders();
        Double getTotal();
    }

    interface CouponUse {
        long getOrders();
        Double getTotal();
        Double getDiscount();
    }
}
