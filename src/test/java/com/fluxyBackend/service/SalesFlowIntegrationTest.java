package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.DTOs.OrderItemsRequest;
import com.fluxyBackend.entity.*;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.repository.*;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pedido → cliente → stock → cobro → cancelación, contra la base de pruebas. */
@SpringBootTest
@Transactional
class SalesFlowIntegrationTest {

    @Autowired private OrderService orderService;
    @Autowired private OrderPaymentService paymentService;
    @Autowired private AccessService accessService;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private OrderPaymentRepository paymentRepository;
    @Autowired private InventoryMovementRepository movementRepository;
    @Autowired private EntityManager entityManager;

    private Company company;
    private Prodcut product;
    private Member owner;

    @BeforeEach
    void setUp() {
        company = companyRepository.save(Company.builder().name("Tienda flujo").slug("tienda-flujo").build());
        User user = userRepository.save(User.builder().fullName("Dueña").email("duena-flujo@fluxy.invalid")
                .password("encoded").role(Role.BUSINESS_OWNER).company(company).build());
        product = productRepository.save(Prodcut.builder().name("Pollo").price(10.0).stock(5)
                .owner(user).company(company).build());
        owner = accessService.resolve(user.getEmail());
    }

    private Order order(String phone, int quantity) {
        OrderItemsRequest item = new OrderItemsRequest();
        item.productId = product.getId();
        item.quantity = quantity;
        CreateOrderRequest request = new CreateOrderRequest();
        request.customerName = "Ana Pérez";
        request.customerPhone = phone;
        request.items = List.of(item);
        request.paymentMethod = "yape";
        return orderService.createOrderAsClient(request, company);
    }

    @Test
    void elPedidoCreaAlClienteDescuentaStockYDejaElCobroPendiente() {
        Order first = order("987 654 321", 2);
        Order second = order("+51 987654321", 1);
        entityManager.flush();

        assertThat(first.getCustomerId()).isNotNull().isEqualTo(second.getCustomerId());
        assertThat(customerRepository.findByCompanyId(company.getId())).hasSize(1);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(2);

        List<OrderPayment> payments = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(first.getId());
        assertThat(payments).singleElement().satisfies(p -> {
            assertThat(p.getStatus()).isEqualTo(OrderPayment.Status.PENDING);
            assertThat(p.getAmount()).isEqualTo(20.0);
            assertThat(p.getMethod()).isEqualTo("yape");
        });
        assertThat(movementRepository.findAll()).filteredOn(m -> m.getOrderId() != null
                        && m.getOrderId().equals(first.getId()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getType()).isEqualTo(InventoryMovement.Type.SALE);
                    assertThat(m.getStockBefore()).isEqualTo(5);
                    assertThat(m.getStockAfter()).isEqualTo(3);
                });
    }

    @Test
    void cancelarExigeMotivoDevuelveElStockYRechazaLoPendiente() {
        Order order = order("987654321", 3);

        assertThatThrownBy(() -> orderService.changeStatus(owner, order.getId(),
                new OrderService.StatusRequest("CANCELLED", "  ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("motivo");

        OrderService.OrderDetail detail = orderService.changeStatus(owner, order.getId(),
                new OrderService.StatusRequest("CANCELLED", "Cliente no respondió"));
        entityManager.flush();

        assertThat(detail.status()).isEqualTo("CANCELLED");
        assertThat(detail.nextStatuses()).isEmpty();
        assertThat(detail.history()).extracting(OrderService.StatusChangeView::toStatus)
                .containsExactly("PENDING", "CANCELLED");
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(detail.payments()).singleElement()
                .extracting(OrderPaymentService.PaymentView::status).isEqualTo("REJECTED");
    }

    @Test
    void unPedidoEntregadoNoVuelveAtras() {
        Order order = order("987654321", 1);
        orderService.changeStatus(owner, order.getId(), new OrderService.StatusRequest("DELIVERED", null));

        assertThatThrownBy(() -> orderService.changeStatus(owner, order.getId(),
                new OrderService.StatusRequest("PREPARING", null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void noSeCobraMasDeLoQueFaltaYLaConciliacionMuestraLaDiferencia() {
        Order order = order("987654321", 2);
        Long pendingId = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()).get(0).getId();
        orderService.changeStatus(owner, order.getId(), new OrderService.StatusRequest("CONFIRMED", null));
        // El cobro esperado se reemplaza por uno parcial registrado a mano.
        paymentService.update(owner, pendingId, new OrderPaymentService.UpdateRequest("REJECTED", null, null, null));

        paymentService.register(owner, new OrderPaymentService.RegisterRequest(order.getId(), "efectivo", 15.0,
                "APPROVED", null, null, null));
        assertThatThrownBy(() -> paymentService.register(owner, new OrderPaymentService.RegisterRequest(
                order.getId(), "efectivo", 10.0, "APPROVED", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("5.00");

        OrderPaymentService.Reconciliation reconciliation = paymentService.reconciliation(company.getId());
        assertThat(reconciliation.unpaidCount()).isEqualTo(1);
        assertThat(reconciliation.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.type()).isEqualTo("UNPAID");
            assertThat(issue.difference()).isEqualTo(5.0);
        });
    }

    @Test
    void unReembolsoNoSuperaLoCobrado() {
        Order order = order("987654321", 1);
        Long paymentId = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()).get(0).getId();
        paymentService.update(owner, paymentId, new OrderPaymentService.UpdateRequest("APPROVED", null, "OP-123", null));

        assertThatThrownBy(() -> paymentService.refund(owner, paymentId, new OrderPaymentService.RefundRequest(11.0, "error")))
                .isInstanceOf(BusinessException.class);
        OrderPaymentService.PaymentView refunded = paymentService.refund(owner, paymentId,
                new OrderPaymentService.RefundRequest(10.0, "Producto dañado"));
        assertThat(refunded.status()).isEqualTo("REFUNDED");
        assertThat(refunded.netAmount()).isZero();
    }

    @Test
    void unProductoOcultoNoSeVendeNiSeLista() {
        product.setStatus(Prodcut.Status.HIDDEN);
        productRepository.save(product);

        assertThatThrownBy(() -> order("987654321", 1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no está disponible");
        assertThat(productRepository.findVisibleByCompany(company, Prodcut.Status.HIDDEN)).isEmpty();
    }
}
