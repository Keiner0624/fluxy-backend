package com.fluxyBackend.invoicing.controller;

import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.invoicing.dto.PublicDocumentView;
import com.fluxyBackend.invoicing.entity.ElectronicDocument;
import com.fluxyBackend.invoicing.service.DocumentFileService;
import com.fluxyBackend.invoicing.service.ElectronicDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Consulta pública por el enlace del correo o el QR. Usa un token aleatorio y revocable, nunca el
 * id del comprobante. Muestra lo mínimo: sin correo ni dirección del cliente.
 */
@Tag(name = "Tienda pública", description = "Catálogo y pedidos de clientes sin autenticación.")
@RestController
@RequestMapping("/store/documents")
@RequiredArgsConstructor
public class PublicDocumentController {

    private final ElectronicDocumentService documents;
    private final DocumentFileService files;

    @Operation(summary = "Consultar un comprobante por su enlace público")
    @GetMapping("/{token}")
    public PublicDocumentView get(@PathVariable String token) {
        return documents.publicView(load(token));
    }

    @Operation(summary = "PDF de un comprobante por su enlace público")
    @GetMapping("/{token}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String token) {
        ElectronicDocument doc = load(token);
        return ElectronicDocumentController.file(files.pdf(doc, documents.loadRelated(doc), documents.publicUrl(doc)), true);
    }

    private ElectronicDocument load(String token) {
        return documents.loadPublic(token).orElseThrow(() -> new NotFoundException("Comprobante no encontrado"));
    }
}
