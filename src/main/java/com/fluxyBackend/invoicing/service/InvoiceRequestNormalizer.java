package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.validation.Receiver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Guarda en el pedido el comprobante que pidió el cliente al comprar, ya validado. Si la tienda
 * no emite comprobantes, los datos se ignoran; si pide factura y el negocio no puede emitirla,
 * se rechaza antes de crear el pedido.
 */
@Component
@RequiredArgsConstructor
public class InvoiceRequestNormalizer {

    private final InvoicingConfigurationService configuration;

    public void apply(Order order, CreateOrderRequest request, Company company) {
        if (request.invoiceType == null || request.invoiceType.isBlank()) return;
        Optional<InvoicingConfigurationService.StoreOptions> options = configuration.storeOptions(company);
        if (options.isEmpty()) return;
        DocumentType type = DocumentType.valueOf(request.invoiceType);
        if (type == DocumentType.FACTURA && !options.get().invoice()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVOICE_NOT_AVAILABLE", "Este negocio no emite facturas.");
        }
        if (type == DocumentType.BOLETA && !options.get().receipt()) return;
        String name = request.buyerLegalName != null && !request.buyerLegalName.isBlank()
                ? request.buyerLegalName : type == DocumentType.BOLETA ? request.customerName : null;
        BigDecimal total = DocumentCalculator.money(order.getTotal() == null ? 0 : order.getTotal());
        Receiver receiver = Receiver.validate(type, request.buyerDocumentType, request.buyerDocumentNumber, name,
                request.buyerFiscalAddress, request.buyerEmail, total);
        order.setInvoiceType(type.name());
        order.setBuyerDocumentType(receiver.documentType().name());
        order.setBuyerDocumentNumber(receiver.documentNumber());
        order.setBuyerLegalName(receiver.name());
        order.setBuyerFiscalAddress(receiver.address());
        order.setBuyerEmail(receiver.email());
    }
}
