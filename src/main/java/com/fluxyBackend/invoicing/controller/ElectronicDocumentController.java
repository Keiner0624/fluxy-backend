package com.fluxyBackend.invoicing.controller;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.invoicing.dto.CreateDocumentRequest;
import com.fluxyBackend.invoicing.dto.CreditNoteRequest;
import com.fluxyBackend.invoicing.dto.DocumentDetail;
import com.fluxyBackend.invoicing.dto.DocumentRow;
import com.fluxyBackend.invoicing.dto.ResendEmailRequest;
import com.fluxyBackend.invoicing.entity.ElectronicDocument;
import com.fluxyBackend.invoicing.service.DocumentFileService;
import com.fluxyBackend.invoicing.service.ElectronicDocumentService;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** Comprobantes del negocio. Toda consulta y acción va por empresa (findByIdAndCompanyId). */
@Tag(name = "Facturación electrónica: comprobantes", description = "Boletas, facturas y notas de crédito.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/invoicing/documents")
@RequiredArgsConstructor
public class ElectronicDocumentController {

    private final ElectronicDocumentService documents;
    private final DocumentFileService files;
    private final AccessService access;

    @Operation(summary = "Listar comprobantes", description = "Filtros: type, status, q (número, cliente o documento), orderId y rango de fechas.")
    @GetMapping
    @RequirePermission(Permission.INVOICE_VIEW)
    public PageResponse<DocumentRow> list(@RequestParam(required = false) String type,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) Long orderId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "25") int size) {
        return documents.search(access.current(), type, status, q, orderId, from, to, page, size);
    }

    @Operation(summary = "Emitir boleta o factura de un pedido",
            description = "Admite Idempotency-Key: repetir la petición devuelve el mismo comprobante. Responde enseguida en PENDING; el envío sigue en segundo plano. 409 DOCUMENT_ALREADY_EXISTS si el pedido ya tiene uno.")
    @PostMapping
    @RequirePermission(Permission.INVOICE_CREATE)
    public DocumentDetail create(@RequestBody CreateDocumentRequest request) {
        return documents.createFromOrder(access.current(), request);
    }

    @Operation(summary = "Detalle e historial")
    @GetMapping("/{id}")
    @RequirePermission(Permission.INVOICE_VIEW)
    public DocumentDetail get(@PathVariable Long id) {
        return documents.get(access.current(), id);
    }

    @Operation(summary = "Descargar el PDF", description = "Se transmite desde Fluxy: nunca se expone la URL del proveedor.")
    @GetMapping("/{id}/pdf")
    @RequirePermission(Permission.INVOICE_VIEW)
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        ElectronicDocument doc = documents.loadForFiles(access.current().companyId(), id);
        return file(files.pdf(doc, documents.loadRelated(doc), documents.publicUrl(doc)), true);
    }

    @Operation(summary = "Descargar el XML")
    @GetMapping("/{id}/xml")
    @RequirePermission(Permission.INVOICE_VIEW)
    public ResponseEntity<byte[]> xml(@PathVariable Long id) {
        ElectronicDocument doc = documents.loadForFiles(access.current().companyId(), id);
        return file(files.xml(doc, documents.loadRelated(doc)), false);
    }

    @Operation(summary = "Reenviar por correo", description = "Opcionalmente a otro correo. No vuelve a emitir el comprobante.")
    @PostMapping("/{id}/resend-email")
    @RequirePermission(Permission.INVOICE_RESEND)
    public DocumentDetail resend(@PathVariable Long id,
                                 @RequestBody(required = false) ResendEmailRequest request) {
        return documents.resendEmail(access.current(), id, request);
    }

    @Operation(summary = "Emitir nota de crédito por el total",
            description = "reason: ANULACION, ERROR_RUC (solo facturas) o DEVOLUCION_TOTAL. El comprobante queda anulado cuando la nota es aceptada.")
    @PostMapping("/{id}/credit-notes")
    @RequirePermission(Permission.INVOICE_CREDIT_NOTE)
    public DocumentDetail creditNote(@PathVariable Long id,
                                     @RequestBody CreditNoteRequest request) {
        return documents.createCreditNote(access.current(), id, request);
    }

    @Operation(summary = "Reintentar un comprobante con error")
    @PostMapping("/{id}/retry")
    @RequirePermission(Permission.INVOICE_CREATE)
    public DocumentDetail retry(@PathVariable Long id) {
        return documents.retry(access.current(), id);
    }

    @Operation(summary = "Revocar el enlace público", description = "El enlace y el QR anteriores dejan de funcionar y se genera uno nuevo.")
    @PostMapping("/{id}/public-link/revoke")
    @RequirePermission(Permission.INVOICE_CREATE)
    public DocumentDetail revoke(@PathVariable Long id) {
        return documents.revokePublicLink(access.current(), id);
    }

    static ResponseEntity<byte[]> file(DocumentFileService.File file, boolean inline) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(file.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content());
    }
}
