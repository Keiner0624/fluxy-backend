package com.fluxyBackend.marketing.repository;

import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.marketing.entity.CampaignEvent;
import com.fluxyBackend.marketing.enums.CampaignChannel;
import com.fluxyBackend.marketing.enums.CampaignEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CampaignEventRepository extends JpaRepository<CampaignEvent, Long> {

    boolean existsByCampaignIdAndDedupeKey(Long campaignId, String dedupeKey);

    boolean existsByOrderIdAndType(Long orderId, CampaignEventType type);

    /** Últimas visitas de una sesión en la tienda, de la más reciente a la más vieja. */
    List<CampaignEvent> findTop10ByCompanyIdAndSessionIdAndTypeAndOccurredAtAfterOrderByOccurredAtDescIdDesc(
            Long companyId, String sessionId, CampaignEventType type, LocalDateTime since);

    Optional<CampaignEvent> findFirstByCampaignIdAndSessionIdAndTypeAndOccurredAtAfterOrderByOccurredAtDescIdDesc(
            Long campaignId, String sessionId, CampaignEventType type, LocalDateTime since);

    /** Eventos de una campaña en un rango, livianos, para armar el embudo y la serie diaria. */
    @Query("""
            SELECT e.type AS type, e.sessionId AS sessionId, e.source AS source, e.productId AS productId,
                   e.orderId AS orderId, e.occurredAt AS occurredAt
            FROM CampaignEvent e
            WHERE e.campaignId = :campaignId AND e.occurredAt >= :from AND e.occurredAt < :to
            """)
    List<EventRow> rows(@Param("campaignId") Long campaignId,
                        @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to);

    /** Visitas y demás pasos por campaña, para la lista y el resumen. */
    @Query("""
            SELECT e.campaignId AS campaignId, e.type AS type, COUNT(e) AS events, COUNT(DISTINCT e.sessionId) AS sessions
            FROM CampaignEvent e
            WHERE e.companyId = :companyId AND e.occurredAt >= :from AND e.occurredAt < :to
            GROUP BY e.campaignId, e.type
            """)
    List<TypeCount> countsByCampaign(@Param("companyId") Long companyId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    /** Pedidos atribuidos por campaña y estado actual del pedido. */
    @Query("""
            SELECT e.campaignId AS campaignId, o.status AS status, COUNT(o) AS orders, SUM(o.total) AS total
            FROM CampaignEvent e, Order o
            WHERE o.id = e.orderId AND e.type = :type AND e.companyId = :companyId
              AND e.occurredAt >= :from AND e.occurredAt < :to
            GROUP BY e.campaignId, o.status
            """)
    List<OrderCount> ordersByCampaign(@Param("companyId") Long companyId,
                                      @Param("type") CampaignEventType type,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    /** Estado y total actual de los pedidos atribuidos. */
    @Query("SELECT o.id AS id, o.status AS status, o.total AS total, o.couponCode AS couponCode FROM Order o WHERE o.id IN :ids")
    List<OrderRow> orders(@Param("ids") Collection<Long> ids);

    /** Productos vendidos en los pedidos atribuidos. */
    @Query("""
            SELECT i.prodcut.id AS productId, i.prodcut.name AS name, SUM(i.quantity) AS units, SUM(i.subTotal) AS revenue
            FROM OrderItem i
            WHERE i.order.id IN :ids AND i.order.status <> :cancelled
            GROUP BY i.prodcut.id, i.prodcut.name
            ORDER BY SUM(i.subTotal) DESC
            """)
    List<ProductRow> products(@Param("ids") Collection<Long> ids, @Param("cancelled") OrderStatus cancelled);

    /** Detalles de producto vistos desde campañas, para detectar interés sin compras. */
    @Query("""
            SELECT e.productId AS productId, COUNT(DISTINCT e.sessionId) AS sessions
            FROM CampaignEvent e
            WHERE e.companyId = :companyId AND e.type = :type AND e.productId IS NOT NULL AND e.occurredAt >= :since
            GROUP BY e.productId
            """)
    List<ProductInterest> productInterest(@Param("companyId") Long companyId,
                                          @Param("type") CampaignEventType type,
                                          @Param("since") LocalDateTime since);

    @Modifying
    @Query("DELETE FROM CampaignEvent e WHERE e.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);

    interface EventRow {
        CampaignEventType getType();
        String getSessionId();
        CampaignChannel getSource();
        Long getProductId();
        Long getOrderId();
        LocalDateTime getOccurredAt();
    }

    interface TypeCount {
        Long getCampaignId();
        CampaignEventType getType();
        long getEvents();
        long getSessions();
    }

    interface OrderCount {
        Long getCampaignId();
        OrderStatus getStatus();
        long getOrders();
        Double getTotal();
    }

    interface OrderRow {
        Long getId();
        OrderStatus getStatus();
        Double getTotal();
        String getCouponCode();
    }

    interface ProductRow {
        Long getProductId();
        String getName();
        Long getUnits();
        Double getRevenue();
    }

    interface ProductInterest {
        Long getProductId();
        long getSessions();
    }
}
