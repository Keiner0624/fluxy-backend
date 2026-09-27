package com.fluxyBackend.domain;

import com.fluxyBackend.billing.EntitlementService;
import com.fluxyBackend.billing.Feature;
import com.fluxyBackend.billing.PlanCatalog;
import com.fluxyBackend.domain.VercelDomainClient.DnsConfig;
import com.fluxyBackend.domain.VercelDomainClient.ProjectDomain;
import com.fluxyBackend.domain.VercelDomainClient.VercelException;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import com.fluxyBackend.service.BusinessClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dominio propio de una tienda (plan Business): se conecta en Vercel, se sigue su estado hasta que
 * los DNS apuntan a Fluxy y se quita cuando el negocio lo pide o se elimina.
 *
 * Un dominio raíz (mitienda.com) se conecta junto con www.mitienda.com, que redirige a él. Si el
 * plan deja de incluir dominio propio, el dominio queda guardado pero la tienda lleva a los
 * compradores a su dirección en Fluxy; al volver a Business funciona otra vez sin reconfigurar.
 */
@Slf4j
@Service
public class CustomDomainService {

    /** Registro DNS que el negocio agrega en su proveedor de dominio. */
    public record DnsRecord(String type, String name, String value, String purpose) {}

    /**
     * Estado para el panel. status: NONE si no hay dominio. planIncludes: si el plan actual lo
     * incluye. available: si el servidor puede conectar dominios (Vercel configurado).
     */
    public record DomainView(String domain, String status, String statusLabel, String message, boolean planIncludes,
                             boolean available, boolean apex, List<DnsRecord> records, String url,
                             OffsetDateTime checkedAt) {}

    /** Lo que la tienda necesita para abrirse en su dominio. active=false: redirigir a storeUrl. */
    public record ResolvedStore(Long companyId, String slug, String name, String domain, boolean active, String storeUrl) {}

    public record ConnectRequest(String domain) {}

    private final CompanyRepository companies;
    private final VercelDomainClient vercel;
    private final CustomDomainRegistry registry;
    private final EntitlementService entitlements;
    private final AuditService audit;
    private final String storeBaseUrl;

    public CustomDomainService(CompanyRepository companies, VercelDomainClient vercel, CustomDomainRegistry registry,
                               EntitlementService entitlements, AuditService audit,
                               @Value("${app.frontend_url:http://localhost:5173}") String frontendUrl) {
        this.companies = companies;
        this.vercel = vercel;
        this.registry = registry;
        this.entitlements = entitlements;
        this.audit = audit;
        this.storeBaseUrl = frontendUrl.replaceAll("/+$", "");
    }

    // ─── Panel ────────────────────────────────────────────────────────────────

    /** Estado del dominio, consultando a Vercel si hay uno conectado. */
    public DomainView view(Member member) {
        Company company = company(member);
        if (company.getCustomDomain() == null) return empty(company);
        return refresh(company);
    }

    public DomainView connect(Member member, ConnectRequest request) {
        Company company = company(member);
        entitlements.require(company, Feature.CUSTOM_DOMAIN);
        if (company.getCustomDomain() != null) {
            throw BusinessException.conflict("DOMAIN_ALREADY_SET", "Quitá el dominio actual antes de conectar otro.");
        }
        String domain = CustomDomainRegistry.normalize(request == null ? null : request.domain());
        if (domain == null) {
            throw new BusinessException("Escribí un dominio válido, por ejemplo mitienda.com (sin https:// ni rutas).");
        }
        if (domain.startsWith("www.")) domain = domain.substring(4);
        if (registry.reserved(domain)) throw new BusinessException("Ese dominio es de Fluxy: usá uno propio.");
        String taken = domain;
        companies.findByCustomDomain(domain).filter(other -> !other.getId().equals(company.getId())).ifPresent(other -> {
            throw BusinessException.conflict("DOMAIN_TAKEN", "El dominio " + taken + " ya está conectado a otra tienda.");
        });
        if (!vercel.configured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DOMAINS_UNAVAILABLE",
                    "Los dominios propios no están disponibles en este momento. Probá más tarde.", Map.of());
        }

        ProjectDomain added = call(() -> vercel.add(taken, null));
        if (added.apex()) addWww(taken);

        company.setCustomDomain(domain);
        company.setCustomDomainApex(added.apex());
        company.setCustomDomainAddedAt(LocalDateTime.now());
        company.setCustomDomainStatus(CustomDomainStatus.PENDING_DNS);
        try {
            companies.saveAndFlush(company);
        } catch (DataIntegrityViolationException e) {
            // Otra tienda lo conectó en el mismo instante.
            throw BusinessException.conflict("DOMAIN_TAKEN", "El dominio " + domain + " ya está conectado a otra tienda.");
        }
        registry.evict(domain);
        audit.record(member, AuditAction.SETTINGS_UPDATED, "COMPANY", company.getId(), Map.of("customDomain", domain));
        return refresh(company);
    }

    /** Vuelve a consultar a Vercel (botón "Verificar ahora"). */
    public DomainView verify(Member member) {
        Company company = company(member);
        if (company.getCustomDomain() == null) throw new BusinessException("No tenés un dominio conectado.");
        return refresh(company);
    }

    /** Quita el dominio de Vercel y de la tienda. No pide plan: al bajar de plan también se puede limpiar. */
    public void remove(Member member) {
        Company company = company(member);
        String domain = company.getCustomDomain();
        if (domain == null) throw new BusinessException("No tenés un dominio conectado.");
        if (vercel.configured()) {
            call(() -> {
                vercel.remove(domain);
                if (!Boolean.FALSE.equals(company.getCustomDomainApex())) vercel.remove("www." + domain);
                return null;
            });
        }
        clear(company);
        companies.save(company);
        registry.evict(domain);
        audit.record(member, AuditAction.SETTINGS_UPDATED, "COMPANY", company.getId(), Map.of("customDomainRemoved", domain));
    }

    /**
     * Libera el dominio de una empresa que se elimina: lo quita de Vercel si se puede y limpia los
     * campos (quien llama guarda la empresa). Nunca falla: un error solo se registra.
     */
    public void release(Company company) {
        String domain = company.getCustomDomain();
        if (domain == null) return;
        if (vercel.configured()) {
            try {
                vercel.remove(domain);
                if (!Boolean.FALSE.equals(company.getCustomDomainApex())) vercel.remove("www." + domain);
            } catch (RuntimeException e) {
                log.warn("No se pudo quitar el dominio {} de Vercel: {}", domain, e.getMessage());
            }
        }
        clear(company);
        registry.evict(domain);
    }

    // ─── Tienda pública ──────────────────────────────────────────────────────

    /** Qué tienda abrir en un dominio. 404 si ninguna lo tiene. */
    public ResolvedStore resolve(String host) {
        Company company = registry.companyFor(host)
                .orElseThrow(() -> new NotFoundException("No hay ninguna tienda en este dominio."));
        boolean active = PlanCatalog.has(company, Feature.CUSTOM_DOMAIN);
        return new ResolvedStore(company.getId(), company.getSlug(), company.getName(), company.getCustomDomain(), active,
                storeBaseUrl + "/store/" + company.getSlug());
    }

    // ─── Revisión periódica ──────────────────────────────────────────────────

    /**
     * Dominios que esperan DNS: cada pasada durante 14 días desde que se conectaron. Activos: una
     * vez por día, para enterarse si alguien cambió los DNS.
     */
    public int checkDue() {
        if (!vercel.configured()) return 0;
        LocalDateTime now = LocalDateTime.now();
        int checked = 0;
        for (Company company : companies.findByCustomDomainIsNotNull()) {
            LocalDateTime last = company.getCustomDomainCheckedAt();
            boolean active = company.getCustomDomainStatus() == CustomDomainStatus.ACTIVE;
            boolean recent = company.getCustomDomainAddedAt() == null || company.getCustomDomainAddedAt().isAfter(now.minusDays(14));
            boolean due = active
                    ? last == null || last.isBefore(now.minusHours(24))
                    : recent ? last == null || last.isBefore(now.minusMinutes(9)) : last == null || last.isBefore(now.minusHours(24));
            if (!due) continue;
            try {
                refresh(company);
                checked++;
            } catch (RuntimeException e) {
                log.warn("No se pudo revisar el dominio {}: {}", company.getCustomDomain(), e.getMessage());
            }
        }
        return checked;
    }

    // ─── Estado ───────────────────────────────────────────────────────────────

    /** Consulta a Vercel, guarda el estado y arma la vista con los registros DNS que faltan. */
    DomainView refresh(Company company) {
        String domain = company.getCustomDomain();
        if (!vercel.configured()) {
            return view(company, company.getCustomDomainStatus(), null, null,
                    "No se puede consultar el dominio en este momento.");
        }
        CustomDomainStatus status;
        ProjectDomain info = null;
        DnsConfig dns = null;
        String problem = null;
        try {
            info = vercel.find(domain).orElse(null);
            if (info == null) {
                // Lo quitaron de Vercel a mano: se vuelve a agregar.
                info = vercel.add(domain, null);
                if (info.apex()) addWww(domain);
            }
            if (!info.verified()) info = vercel.verify(domain).orElse(info);
            if (info.apex() && vercel.find("www." + domain).isEmpty()) addWww(domain);
            dns = vercel.config(domain);
            status = !info.verified() ? CustomDomainStatus.VERIFICATION_REQUIRED
                    : dns.misconfigured() ? CustomDomainStatus.PENDING_DNS : CustomDomainStatus.ACTIVE;
            company.setCustomDomainApex(info.apex());
        } catch (VercelException e) {
            if (e.status() == 503 || e.status() == 502) {
                // Vercel no respondió: se conserva el último estado conocido.
                return view(company, company.getCustomDomainStatus(), null, null, e.getMessage());
            }
            status = CustomDomainStatus.ERROR;
            problem = e.getMessage();
        }
        CustomDomainStatus previous = company.getCustomDomainStatus();
        company.setCustomDomainStatus(status);
        company.setCustomDomainCheckedAt(LocalDateTime.now());
        companies.save(company);
        if (previous != status) {
            registry.evict(domain);
            log.info("Dominio {}: {} → {}", domain, previous, status);
        }
        return view(company, status, info, dns, problem);
    }

    private DomainView view(Company company, CustomDomainStatus status, ProjectDomain info, DnsConfig dns, String problem) {
        String domain = company.getCustomDomain();
        boolean planIncludes = PlanCatalog.has(company, Feature.CUSTOM_DOMAIN);
        boolean apex = info != null ? info.apex() : !Boolean.FALSE.equals(company.getCustomDomainApex());
        List<DnsRecord> records = records(domain, apex, info, dns, status);
        String message;
        if (problem != null) {
            message = problem;
        } else if (!planIncludes) {
            message = "Tu plan actual no incluye dominio propio: quienes entren a " + domain
                    + " son llevados a tu tienda en Fluxy. Al volver al plan Business se usa de nuevo, sin configurar nada.";
        } else if (status == null) {
            message = "Todavía no pudimos consultar el dominio.";
        } else {
            message = switch (status) {
                case ACTIVE -> "Tu tienda ya se abre en https://" + domain + ". El certificado de seguridad (HTTPS) es automático.";
                case PENDING_DNS -> "Agregá estos registros en el panel de la empresa donde compraste el dominio. "
                        + "Suelen tardar unos minutos, y a veces hasta 48 horas. Lo revisamos solos cada 10 minutos.";
                case VERIFICATION_REQUIRED -> "Este dominio figura en otra cuenta de Vercel. Agregá el registro TXT para "
                        + "confirmar que es tuyo; después, los registros para apuntarlo a Fluxy.";
                case ERROR -> "No pudimos conectar el dominio. Quitalo y volvé a intentarlo, o escribinos a soporte.";
            };
        }
        return new DomainView(domain, status == null ? "PENDING_DNS" : status.name(),
                status == null ? CustomDomainStatus.PENDING_DNS.label() : status.label(), message, planIncludes,
                vercel.configured(), apex, records, "https://" + domain,
                BusinessClock.withOffset(company.getCustomDomainCheckedAt()));
    }

    private static List<DnsRecord> records(String domain, boolean apex, ProjectDomain info, DnsConfig dns,
                                           CustomDomainStatus status) {
        List<DnsRecord> records = new ArrayList<>();
        if (info != null && !info.verified()) {
            for (VercelDomainClient.Verification v : info.verification()) {
                records.add(new DnsRecord(v.type(), host(v.domain(), info.apexName()), v.value(), "Confirma que el dominio es tuyo"));
            }
        }
        String aRecord = dns == null ? VercelDomainClient.DEFAULT_A_RECORD : dns.aRecord();
        String cname = dns == null ? VercelDomainClient.DEFAULT_CNAME : dns.cname();
        if (apex) {
            records.add(new DnsRecord("A", "@", aRecord, "Apunta " + domain + " a Fluxy"));
            records.add(new DnsRecord("CNAME", "www", cname, "Apunta www." + domain + " a Fluxy"));
        } else {
            String apexName = info == null ? null : info.apexName();
            records.add(new DnsRecord("CNAME", host(domain, apexName), cname, "Apunta " + domain + " a Fluxy"));
        }
        return status == CustomDomainStatus.ACTIVE ? List.of() : records;
    }

    /** Nombre del registro relativo al dominio raíz: "www", "tienda" o "@". */
    private static String host(String fullName, String apexName) {
        if (fullName == null || fullName.isBlank()) return "@";
        if (apexName == null || apexName.isBlank()) {
            int dot = fullName.indexOf('.');
            return dot > 0 ? fullName.substring(0, dot) : fullName;
        }
        if (fullName.equals(apexName)) return "@";
        return fullName.endsWith("." + apexName) ? fullName.substring(0, fullName.length() - apexName.length() - 1) : fullName;
    }

    private void addWww(String domain) {
        try {
            vercel.add("www." + domain, domain);
        } catch (VercelException e) {
            // Sin www la tienda igual funciona en el dominio raíz.
            log.warn("No se pudo conectar www.{}: {}", domain, e.getMessage());
        }
    }

    private DomainView empty(Company company) {
        return new DomainView(null, "NONE", "Sin dominio", null, PlanCatalog.has(company, Feature.CUSTOM_DOMAIN),
                vercel.configured(), true, List.of(), null, null);
    }

    private static void clear(Company company) {
        company.setCustomDomain(null);
        company.setCustomDomainStatus(null);
        company.setCustomDomainApex(null);
        company.setCustomDomainAddedAt(null);
        company.setCustomDomainCheckedAt(null);
    }

    private Company company(Member member) {
        return companies.findById(member.companyId()).orElseThrow(() -> new NotFoundException("Empresa no encontrada"));
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (VercelException e) {
            HttpStatus status = e.status() == 409 ? HttpStatus.CONFLICT
                    : e.status() == 400 ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
            throw new BusinessException(status, "DOMAIN_PROVIDER_ERROR", e.getMessage(), Map.of("providerCode", e.code()));
        }
    }
}
