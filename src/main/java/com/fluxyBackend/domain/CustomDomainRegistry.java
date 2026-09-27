package com.fluxyBackend.domain;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.repository.CompanyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Qué tienda corresponde a un dominio. Lo usan la tienda (para saber qué mostrar en
 * mitienda.com) y el CORS (para aceptar las llamadas públicas que llegan desde ese dominio).
 * Guarda el resultado unos minutos: el CORS lo consulta en cada petición entre orígenes.
 */
@Component
public class CustomDomainRegistry {

    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final long FOUND_TTL_MS = 5 * 60_000L;
    private static final long MISSING_TTL_MS = 60_000L;
    private static final int MAX_ENTRIES = 5_000;

    private record Entry(Long companyId, long expiresAt) {}

    private final CompanyRepository companies;
    private final Set<String> reserved = new HashSet<>();
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public CustomDomainRegistry(CompanyRepository companies,
                                @Value("${app.frontend_url:http://localhost:5173}") String frontendUrl,
                                @Value("${app.allowed_origins:}") String allowedOrigins) {
        this.companies = companies;
        reserved.add("fluxyweb.com");
        reserved.add("vercel.app");
        reserved.add(normalize(hostOf(frontendUrl)));
        Arrays.stream(allowedOrigins.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(CustomDomainRegistry::hostOf).map(CustomDomainRegistry::normalize).forEach(reserved::add);
        reserved.remove(null);
    }

    /**
     * Dominio en minúsculas, sin protocolo, puerto, ruta ni punto final, en ASCII (IDN). Null si
     * no es un dominio válido (una IP o "localhost" tampoco lo son).
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.strip().toLowerCase(Locale.ROOT)
                .replaceFirst("^[a-z]+://", "")
                .replaceFirst("[/?#].*$", "")
                .replaceFirst(":\\d+$", "")
                .replaceAll("\\.+$", "");
        if (value.isEmpty()) return null;
        try {
            value = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return DOMAIN.matcher(value).matches() ? value : null;
    }

    /** Dominios de Fluxy (y de Vercel) que ninguna tienda puede usar como propios. */
    public boolean reserved(String domain) {
        if (domain == null) return true;
        for (String host : reserved) {
            if (domain.equals(host) || domain.endsWith("." + host)) return true;
        }
        return false;
    }

    /** La empresa dueña del dominio: el exacto o, si llega www.mitienda.com, la de mitienda.com. */
    public Optional<Company> companyFor(String host) {
        String domain = normalize(host);
        if (domain == null) return Optional.empty();
        Optional<Company> exact = companies.findByCustomDomain(domain);
        if (exact.isPresent() || !domain.startsWith("www.")) return exact.filter(CustomDomainRegistry::usable);
        return companies.findByCustomDomain(domain.substring(4)).filter(CustomDomainRegistry::usable);
    }

    /** Si el origen (https://mitienda.com) es el dominio de una tienda: para el CORS de la tienda pública. */
    public boolean isStoreOrigin(String origin) {
        if (origin == null) return false;
        URI uri;
        try {
            uri = URI.create(origin.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPath() != null && !uri.getPath().isEmpty()) return false;
        String host = normalize(uri.getHost());
        if (host == null || reserved(host)) return false;

        long now = System.currentTimeMillis();
        Entry cached = cache.get(host);
        if (cached != null && cached.expiresAt() > now) return cached.companyId() != null;
        Long companyId = companyFor(host).map(Company::getId).orElse(null);
        if (cache.size() >= MAX_ENTRIES) cache.clear();
        cache.put(host, new Entry(companyId, now + (companyId == null ? MISSING_TTL_MS : FOUND_TTL_MS)));
        return companyId != null;
    }

    /** Olvida lo que había guardado del dominio (al conectarlo o quitarlo). */
    public void evict(String domain) {
        if (domain == null) return;
        cache.remove(domain);
        cache.remove("www." + domain);
    }

    private static boolean usable(Company company) {
        return company.getStatus() != Company.Status.ANONYMIZED;
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
