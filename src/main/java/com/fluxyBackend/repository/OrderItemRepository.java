package com.fluxyBackend.repository;

import com.fluxyBackend.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** Productos que aparecen en algún pedido: no se pueden borrar sin romper ese pedido. */
    @Query("SELECT DISTINCT oi.prodcut.id FROM OrderItem oi WHERE oi.prodcut.id IN :productIds")
    List<Long> findReferencedProductIds(@Param("productIds") Collection<Long> productIds);

    @Query("SELECT oi.order.id AS orderId, COUNT(oi) AS lines, SUM(oi.quantity) AS units FROM OrderItem oi WHERE oi.order.id IN :orderIds GROUP BY oi.order.id")
    List<OrderLines> countByOrderIds(@Param("orderIds") Collection<Long> orderIds);

    interface OrderLines {
        Long getOrderId();
        Long getLines();
        Long getUnits();
    }
}
