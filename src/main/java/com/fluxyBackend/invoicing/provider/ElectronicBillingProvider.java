package com.fluxyBackend.invoicing.provider;

import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.enums.ProviderCode;

import java.util.Set;

/**
 * Lo único que Fluxy conoce de un proveedor de facturación. Pedidos, pagos y el panel nunca
 * llaman a su API: cambiar de proveedor es escribir otra implementación.
 *
 * Contrato de errores: un rechazo de validación vuelve como {@link ProviderResult.Outcome#REJECTED};
 * una falla técnica (red, tiempo límite, 5xx) se lanza como {@link ProviderException} y se reintenta.
 * issue debe ser idempotente por serie y número: si el documento ya existe en el proveedor,
 * devuelve su estado en lugar de fallar.
 */
public interface ElectronicBillingProvider {

    ProviderCode code();

    Set<DocumentType> supportedTypes();

    /** true si necesita ruta y token del negocio. */
    boolean requiresCredentials();

    ProviderResult issue(ProviderContext context, IssueRequest request);

    ProviderResult getStatus(ProviderContext context, IssueRequest request);

    ProviderHealth healthCheck(ProviderContext context);

    /**
     * Descarga el PDF o XML guardado en el proveedor. null si el proveedor no guarda archivos
     * (Fluxy genera la representación).
     */
    byte[] download(ProviderContext context, String storageKey);
}
