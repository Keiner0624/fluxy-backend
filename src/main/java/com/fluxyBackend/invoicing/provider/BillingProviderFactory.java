package com.fluxyBackend.invoicing.provider;

import com.fluxyBackend.invoicing.enums.ProviderCode;
import com.fluxyBackend.service.CircuitBreaker;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Elige la implementación de cada proveedor y guarda su cortacircuito: si un proveedor falla
 * seguido, se deja de llamarlo un rato y los comprobantes quedan en ERROR para reintentar,
 * sin colgar hilos esperando tiempos límite.
 */
@Component
public class BillingProviderFactory {

    private final Map<ProviderCode, ElectronicBillingProvider> providers = new EnumMap<>(ProviderCode.class);
    private final Map<ProviderCode, CircuitBreaker> breakers = new EnumMap<>(ProviderCode.class);

    public BillingProviderFactory(List<ElectronicBillingProvider> implementations) {
        for (ElectronicBillingProvider provider : implementations) {
            providers.put(provider.code(), provider);
            breakers.put(provider.code(), new CircuitBreaker("invoicing-" + provider.code().name().toLowerCase(), 5, Duration.ofMinutes(2)));
        }
    }

    public ElectronicBillingProvider get(ProviderCode code) {
        ElectronicBillingProvider provider = providers.get(code);
        if (provider == null) throw new IllegalStateException("Proveedor de facturación no disponible: " + code);
        return provider;
    }

    public CircuitBreaker breaker(ProviderCode code) {
        return breakers.get(code);
    }
}
