package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.DTOs.OrderItemsRequest;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class OrderServiceIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsStockReductionWhenClientCreatesOrder() {
        Company company = companyRepository.save(Company.builder()
                .name("Tienda")
                .slug("tienda-test")
                .build());
        User owner = userRepository.save(User.builder()
                .fullName("Owner")
                .email("owner-test@fluxy.invalid")
                .password("encoded")
                .role(Role.BUSINESS_OWNER)
                .company(company)
                .build());
        Prodcut product = productRepository.save(Prodcut.builder()
                .name("Producto")
                .price(10.0)
                .stock(5)
                .owner(owner)
                .company(company)
                .build());

        OrderItemsRequest item = new OrderItemsRequest();
        item.productId = product.getId();
        item.quantity = 2;
        CreateOrderRequest request = new CreateOrderRequest();
        request.customerName = "Cliente";
        request.items = List.of(item);

        Order order = orderService.createOrderAsClient(request, company);
        entityManager.flush();
        entityManager.clear();

        assertEquals(20.0, order.getTotal());
        assertEquals(3, productRepository.findById(product.getId()).orElseThrow().getStock());
    }
}
