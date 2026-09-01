package com.fluxyBackend.DTOs;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void rejectsNegativeOrderQuantities() {
        OrderItemsRequest item = new OrderItemsRequest();
        item.productId = 1L;
        item.quantity = -2;

        CreateOrderRequest request = new CreateOrderRequest();
        request.customerName = "Cliente";
        request.items = List.of(item);

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void acceptsValidOrder() {
        OrderItemsRequest item = new OrderItemsRequest();
        item.productId = 1L;
        item.quantity = 2;

        CreateOrderRequest request = new CreateOrderRequest();
        request.customerName = "Cliente";
        request.items = List.of(item);

        assertTrue(validator.validate(request).isEmpty());
    }
}
