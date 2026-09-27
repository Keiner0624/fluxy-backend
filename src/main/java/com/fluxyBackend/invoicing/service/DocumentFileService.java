package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.invoicing.entity.ElectronicDocument;
import com.fluxyBackend.invoicing.enums.DocumentStatus;
import com.fluxyBackend.invoicing.provider.BillingProviderFactory;
import com.fluxyBackend.invoicing.provider.ElectronicBillingProvider;
import com.fluxyBackend.invoicing.provider.ProviderException;
import com.fluxyBackend.invoicing.repository.InvoicingConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

/**
 * PDF y XML de un comprobante. Nunca se entrega una URL permanente del proveedor: Fluxy valida
 * empresa y permiso (o el token público) y transmite el archivo.
 */
@Service
@RequiredArgsConstructor
public class DocumentFileService {

    private static final Set<DocumentStatus> WITH_FILES = EnumSet.of(DocumentStatus.ACCEPTED, DocumentStatus.CANCEL_PENDING,
            DocumentStatus.CANCELLED);

    private final BillingProviderFactory providers;
    private final InvoicingConfigurationRepository configurations;
    private final InvoicingConfigurationService configuration;

    public record File(byte[] content, String fileName, String contentType) {}

    public File pdf(ElectronicDocument doc, ElectronicDocument related, String publicUrl) {
        requireFiles(doc);
        byte[] fromProvider = fromProvider(doc, doc.getPdfStorageKey());
        byte[] content = fromProvider != null ? fromProvider : DocumentRenderer.pdf(doc, related, publicUrl);
        return new File(content, fileName(doc, "pdf"), "application/pdf");
    }

    public File xml(ElectronicDocument doc, ElectronicDocument related) {
        requireFiles(doc);
        byte[] fromProvider = fromProvider(doc, doc.getXmlStorageKey());
        byte[] content = fromProvider != null ? fromProvider : DocumentRenderer.xml(doc, related);
        return new File(content, fileName(doc, "xml"), "application/xml");
    }

    private byte[] fromProvider(ElectronicDocument doc, String storageKey) {
        if (storageKey == null) return null;
        ElectronicBillingProvider provider = providers.get(doc.getProvider());
        var config = configurations.findByCompanyId(doc.getCompanyId()).orElse(null);
        if (config == null || config.getProvider() != doc.getProvider()) return null;
        try {
            return provider.download(configuration.context(config), storageKey);
        } catch (ProviderException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                    "No se pudo obtener el archivo del proveedor. Probá en unos minutos.");
        }
    }

    private static void requireFiles(ElectronicDocument doc) {
        if (!WITH_FILES.contains(doc.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "DOCUMENT_NOT_ACCEPTED", "El comprobante todavía no fue aceptado.");
        }
    }

    /** 20123456789-03-B001-00000158.pdf, el nombre habitual de los comprobantes electrónicos. */
    public static String fileName(ElectronicDocument doc, String extension) {
        return doc.getIssuerRuc() + "-" + doc.getType().sunatCode() + "-" + doc.fullNumber() + "." + extension;
    }
}
