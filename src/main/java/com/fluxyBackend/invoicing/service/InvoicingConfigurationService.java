package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.billing.EntitlementService;
import com.fluxyBackend.billing.Feature;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.invoicing.dto.InvoicingConfigurationView;
import com.fluxyBackend.invoicing.dto.InvoicingSettingsRequest;
import com.fluxyBackend.invoicing.dto.InvoicingStatusView;
import com.fluxyBackend.invoicing.dto.ProviderSettingsRequest;
import com.fluxyBackend.invoicing.dto.SeriesRequest;
import com.fluxyBackend.invoicing.dto.VerifyRucRequest;
import com.fluxyBackend.invoicing.entity.DocumentSeries;
import com.fluxyBackend.invoicing.entity.InvoicingConfiguration;
import com.fluxyBackend.invoicing.entity.TaxProfile;
import com.fluxyBackend.invoicing.enums.*;
import com.fluxyBackend.invoicing.provider.BillingProviderFactory;
import com.fluxyBackend.invoicing.provider.ElectronicBillingProvider;
import com.fluxyBackend.invoicing.provider.ProviderContext;
import com.fluxyBackend.invoicing.provider.ProviderHealth;
import com.fluxyBackend.invoicing.provider.implementations.NubefactBillingProvider;
import com.fluxyBackend.invoicing.repository.DocumentSeriesRepository;
import com.fluxyBackend.invoicing.repository.ElectronicDocumentRepository;
import com.fluxyBackend.invoicing.repository.InvoicingConfigurationRepository;
import com.fluxyBackend.invoicing.repository.TaxProfileRepository;
import com.fluxyBackend.invoicing.validation.TaxIdValidator;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.SecretCipher;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import com.fluxyBackend.service.BusinessClock;
import com.fluxyBackend.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Alta y mantenimiento de la facturación de un negocio.
 *
 * Regla central: lo que habilita emitir (perfil verificado, capacidades, estado ACTIVE) lo decide
 * este servicio con datos del servidor. Las peticiones del panel solo aportan datos a verificar;
 * cualquier campo como canIssueInvoice o verificationStatus que llegue en un JSON se ignora.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoicingConfigurationService {

    public static final String NOT_ACTIVE = "INVOICING_NOT_ACTIVE";
    public static final String NOT_READY = "INVOICING_NOT_READY";
    public static final String TYPE_NOT_ALLOWED = "DOCUMENT_TYPE_NOT_ALLOWED";
    static final Duration RECENT_AUTH = Duration.ofMinutes(10);
    static final Duration REVALIDATE_EVERY = Duration.ofDays(7);

    private final InvoicingConfigurationRepository configurations;
    private final TaxProfileRepository profiles;
    private final DocumentSeriesRepository seriesRepository;
    private final ElectronicDocumentRepository documents;
    private final CompanyRepository companies;
    private final UserRepository users;
    private final BillingProviderFactory providers;
    private final RucLookup rucLookup;
    private final SecretCipher cipher;
    private final SessionService sessions;
    private final EntitlementService entitlements;
    private final AuditService audit;
    private final EmailService email;

    // ─── Lectura ─────────────────────────────────────────────────────────────

    @Transactional
    public InvoicingConfigurationView view(Member member) {
        InvoicingConfiguration config = getOrCreate(member.companyId());
        TaxProfile profile = profile(member.companyId());
        return view(member.company(), config, profile);
    }

    @Transactional(readOnly = true)
    public InvoicingStatusView status(Member member) {
        InvoicingConfiguration config = configurations.findByCompanyId(member.companyId()).orElse(null);
        TaxProfile profile = profiles.findByCompanyId(member.companyId()).orElse(null);
        boolean plan = entitlements.has(member.company(), Feature.ELECTRONIC_INVOICING);
        if (config == null || profile == null) {
            return new InvoicingStatusView(ConfigurationStatus.DRAFT.name(), null, Environment.TEST.name(), ProviderCode.SANDBOX.label(), plan,
                    false, false, false, IssueTrigger.PAYMENT_CONFIRMED.name(), false, 80, null, null);
        }
        boolean active = config.getStatus() == ConfigurationStatus.ACTIVE && plan;
        return new InvoicingStatusView(config.getStatus().name(), config.getStatusReason(), config.getProvider().environment().name(),
                config.getProvider().label(), plan, active && profile.isCanIssueReceipt(), active && profile.isCanIssueInvoice(),
                config.isAutomaticIssuing(), config.getIssueTrigger().name(), config.isPrintAutomatically(), config.getPaperWidth(),
                config.getBusinessName(), config.getTaxId());
    }

    /** Para la tienda pública y el checkout: qué comprobantes se pueden pedir. */
    @Transactional(readOnly = true)
    public Optional<StoreOptions> storeOptions(Company company) {
        if (!entitlements.has(company, Feature.ELECTRONIC_INVOICING)) return Optional.empty();
        InvoicingConfiguration config = configurations.findByCompanyId(company.getId()).orElse(null);
        if (config == null || config.getStatus() != ConfigurationStatus.ACTIVE) return Optional.empty();
        TaxProfile profile = profiles.findByCompanyId(company.getId()).orElse(null);
        if (profile == null) return Optional.empty();
        return Optional.of(new StoreOptions(profile.isCanIssueReceipt(), profile.isCanIssueInvoice(),
                config.getProvider().environment() == Environment.TEST));
    }

    public record StoreOptions(boolean receipt, boolean invoice, boolean test) {}

    // ─── Escritura ───────────────────────────────────────────────────────────

    @Transactional
    public InvoicingConfigurationView updateSettings(Member member, InvoicingSettingsRequest request) {
        requirePlan(member.company());
        InvoicingConfiguration config = lock(member.companyId());
        TaxProfile profile = profile(member.companyId());
        if (request.tradeName() != null) config.setTradeName(clean(request.tradeName(), 200));
        if (request.fiscalAddress() != null) config.setFiscalAddress(clean(request.fiscalAddress(), 300));
        if (request.taxRegime() != null) profile.setTaxRegime(parse(TaxRegime.class, request.taxRegime(), "Régimen tributario inválido."));
        if (request.automaticIssuing() != null) config.setAutomaticIssuing(request.automaticIssuing());
        if (request.issueTrigger() != null) config.setIssueTrigger(parse(IssueTrigger.class, request.issueTrigger(), "Disparador inválido."));
        if (request.emailEnabled() != null) config.setEmailEnabled(request.emailEnabled());
        if (request.attachPdf() != null) config.setAttachPdf(request.attachPdf());
        if (request.attachXml() != null) config.setAttachXml(request.attachXml());
        if (request.printAutomatically() != null) config.setPrintAutomatically(request.printAutomatically());
        if (request.paperWidth() != null) {
            if (request.paperWidth() != 80 && request.paperWidth() != 58) throw new BusinessException("El ancho del ticket es 80 o 58 mm.");
            config.setPaperWidth(request.paperWidth());
        }
        if (request.taxAffectation() != null) {
            config.setTaxAffectation(parse(TaxAffectation.class, request.taxAffectation(), "Afectación al IGV inválida."));
        }
        recompute(config, profile);
        configurations.save(config);
        profiles.save(profile);
        audit.record(member, AuditAction.INVOICING_SETTINGS_UPDATED, "INVOICING", config.getId(), null);
        return view(member.company(), config, profile);
    }

    /**
     * Verifica el RUC en el servidor. Cambiar un RUC ya verificado exige haber confirmado la
     * identidad hace poco, y deja la facturación en REQUIRES_ACTION hasta volver a probar y activar.
     */
    @Transactional
    public InvoicingConfigurationView verifyRuc(Member member, VerifyRucRequest request, String sessionId) {
        requirePlan(member.company());
        String ruc = TaxIdValidator.digits(request.ruc());
        if (!TaxIdValidator.isValidRuc(ruc)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RUC_INVALID", "El RUC no es válido: revisá los 11 dígitos.");
        }
        InvoicingConfiguration config = lock(member.companyId());
        TaxProfile profile = profile(member.companyId());
        boolean changing = profile.getRuc() != null && !profile.getRuc().equals(ruc);
        if (changing && (profile.verified() || documents.countByCompanyIdAndStatusIn(member.companyId(), EnumSet.allOf(DocumentStatus.class)) > 0)) {
            sessions.requireRecentAuth(sessionId, RECENT_AUTH);
        }
        if (request.taxRegime() != null) profile.setTaxRegime(parse(TaxRegime.class, request.taxRegime(), "Régimen tributario inválido."));

        profile.setRuc(ruc);
        profile.setVerificationStatus(VerificationStatus.PENDING_VERIFICATION);
        profile.setLastCheckedAt(LocalDateTime.now());
        audit.record(member, AuditAction.TAX_PROFILE_VERIFICATION_STARTED, "TAX_PROFILE", member.companyId(),
                Map.of("ruc", TaxIdValidator.mask(ruc)));

        Environment environment = config.getProvider().environment();
        if (rucLookup.configured()) {
            try {
                applyLookup(profile, ruc);
            } catch (RucLookup.Unavailable e) {
                // Queda en ERROR con el motivo: sin respuesta del padrón no se da por válido.
            }
        } else if (environment == Environment.TEST) {
            // Modo de prueba sin padrón: se acepta el formato para probar el flujo; nunca sirve para producción.
            String name = clean(request.businessName(), 200);
            if (name == null) throw new BusinessException("Indicá la razón social para el modo de prueba.");
            profile.setBusinessName(name.toUpperCase(Locale.ROOT));
            profile.setRucStatus(null);
            profile.setRucCondition(null);
            profile.setVerificationSource(TaxProfile.SOURCE_SANDBOX);
            profile.setVerificationStatus(VerificationStatus.VERIFIED);
            profile.setVerificationMessage("Verificado solo para el modo de prueba (formato del RUC).");
            profile.setVerifiedAt(LocalDateTime.now());
        } else {
            profile.setVerificationStatus(VerificationStatus.ERROR);
            profile.setVerificationSource(null);
            profile.setVerificationMessage("La verificación de RUC no está disponible todavía. Probá más tarde o escribinos a soporte.");
        }

        if (changing || !Objects.equals(config.getTaxId(), ruc)) {
            config.setTaxId(ruc);
            // La cuenta del proveedor es de un RUC: al cambiarlo hay que volver a probar la conexión.
            if (config.getProvider().environment() == Environment.PRODUCTION) config.setConnectionOk(false);
            if (config.getStatus() == ConfigurationStatus.ACTIVE) {
                config.setStatus(ConfigurationStatus.REQUIRES_ACTION);
                config.setStatusReason("Cambió el RUC: volvé a probar la conexión y activar.");
            }
        }
        if (profile.verified()) {
            config.setBusinessName(profile.getBusinessName());
            if (config.getFiscalAddress() == null && profile.getFiscalAddress() != null) {
                config.setFiscalAddress(clean(profile.getFiscalAddress(), 300));
            }
        }
        recompute(config, profile);
        syncSeriesWithCapabilities(config, profile);
        configurations.save(config);
        profiles.save(profile);
        audit.record(member, profile.verified() ? AuditAction.TAX_PROFILE_VERIFIED : AuditAction.TAX_PROFILE_REJECTED,
                "TAX_PROFILE", member.companyId(), Map.of("ruc", TaxIdValidator.mask(ruc),
                        "status", profile.getVerificationStatus().name(),
                        "source", String.valueOf(profile.getVerificationSource())));
        return view(member.company(), config, profile);
    }

    /** Proveedor y credenciales. El token se cifra y nunca vuelve al panel; guardar uno pide identidad reciente. */
    @Transactional
    public InvoicingConfigurationView updateProvider(Member member, ProviderSettingsRequest request, String sessionId) {
        requirePlan(member.company());
        ProviderCode code = parse(ProviderCode.class, request.provider(), "Proveedor inválido.");
        ElectronicBillingProvider provider = providers.get(code);
        InvoicingConfiguration config = lock(member.companyId());
        TaxProfile profile = profile(member.companyId());
        boolean credentialsChanged = false;

        if (provider.requiresCredentials()) {
            String endpoint = clean(request.endpoint(), 300);
            String token = request.token() == null ? null : request.token().strip();
            if (endpoint != null || (token != null && !token.isEmpty())) sessions.requireRecentAuth(sessionId, RECENT_AUTH);
            if (endpoint != null) {
                if (provider instanceof NubefactBillingProvider nubefact && !nubefact.isAllowedEndpoint(endpoint)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "PROVIDER_ENDPOINT_INVALID",
                            "La ruta tiene que ser la dirección https que te da Nubefact.");
                }
                credentialsChanged |= !endpoint.equals(config.getProviderEndpoint());
                config.setProviderEndpoint(endpoint);
            }
            if (token != null && !token.isEmpty()) {
                if (token.length() < 16 || token.length() > 300) throw new BusinessException("El token no parece válido.");
                config.setProviderTokenCipher(cipher.encrypt(token));
                config.setProviderTokenHint(token.substring(token.length() - 4));
                credentialsChanged = true;
            }
        }
        boolean providerChanged = code != config.getProvider();
        if (providerChanged) {
            config.setProvider(code);
            if (!provider.requiresCredentials()) {
                config.setProviderEndpoint(null);
                config.setProviderTokenCipher(null);
                config.setProviderTokenHint(null);
            }
            // Una verificación hecha para el modo de prueba no alcanza para emitir de verdad.
            if (code.environment() == Environment.PRODUCTION && !TaxProfile.SOURCE_RUC_API.equals(profile.getVerificationSource())
                    && profile.getRuc() != null) {
                profile.setVerificationStatus(VerificationStatus.PENDING_VERIFICATION);
                profile.setVerificationMessage("Para emitir comprobantes reales hay que verificar el RUC en el padrón de SUNAT.");
            }
            ensureDefaultSeries(member.companyId(), code.environment(), profile);
        }
        if (providerChanged || credentialsChanged) {
            config.setConnectionOk(false);
            config.setConnectionCheckedAt(null);
            config.setConnectionMessage(null);
            if (config.getStatus() == ConfigurationStatus.ACTIVE) {
                config.setStatus(ConfigurationStatus.REQUIRES_ACTION);
                config.setStatusReason("Cambió el proveedor o sus credenciales: probá la conexión y volvé a activar.");
            }
        }
        recompute(config, profile);
        configurations.save(config);
        profiles.save(profile);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", code.name());
        meta.put("credentialsChanged", credentialsChanged);
        if (config.getProviderTokenHint() != null) meta.put("tokenHint", "…" + config.getProviderTokenHint());
        audit.record(member, AuditAction.INVOICING_PROVIDER_CHANGED, "INVOICING", config.getId(), meta);
        return view(member.company(), config, profile);
    }

    @Transactional
    public InvoicingConfigurationView testConnection(Member member) {
        requirePlan(member.company());
        InvoicingConfiguration config = lock(member.companyId());
        TaxProfile profile = profile(member.companyId());
        ElectronicBillingProvider provider = providers.get(config.getProvider());
        ProviderHealth health;
        try {
            health = provider.healthCheck(context(config));
        } catch (RuntimeException e) {
            health = new ProviderHealth(false, "No se pudo probar la conexión: " + e.getMessage());
        }
        config.setConnectionOk(health.ok());
        config.setConnectionCheckedAt(LocalDateTime.now());
        config.setConnectionMessage(clean(health.message(), 300));
        recompute(config, profile);
        configurations.save(config);
        profiles.save(profile);
        audit.record(member, AuditAction.INVOICING_CONNECTION_TESTED, "INVOICING", config.getId(),
                Map.of("provider", config.getProvider().name(), "ok", health.ok()));
        return view(member.company(), config, profile);
    }

    /** Solo pasa a ACTIVE si todo lo del checklist está en orden. */
    @Transactional
    public InvoicingConfigurationView activate(Member member) {
        requirePlan(member.company());
        InvoicingConfiguration config = lock(member.companyId());
        TaxProfile profile = profile(member.companyId());
        recompute(config, profile);
        List<InvoicingConfigurationView.CheckItem> checklist = checklist(member.company(), config, profile);
        List<String> missing = checklist.stream().filter(c -> !c.ok()).map(InvoicingConfigurationView.CheckItem::label).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, NOT_READY,
                    "Todavía no se puede activar: " + String.join(", ", missing).toLowerCase(Locale.ROOT) + ".",
                    Map.of("missing", missing));
        }
        ConfigurationStatus previous = config.getStatus();
        config.setStatus(ConfigurationStatus.ACTIVE);
        config.setStatusReason(null);
        config.setActivatedAt(LocalDateTime.now());
        configurations.save(config);
        profiles.save(profile);
        audit.record(member, AuditAction.INVOICING_ACTIVATION_CHANGED, "INVOICING", config.getId(),
                Map.of("from", previous.name(), "to", "ACTIVE", "provider", config.getProvider().name()));
        return view(member.company(), config, profile);
    }

    @Transactional
    public InvoicingConfigurationView pause(Member member) {
        InvoicingConfiguration config = lock(member.companyId());
        ConfigurationStatus previous = config.getStatus();
        if (previous != ConfigurationStatus.ACTIVE) throw new BusinessException("La facturación no está activa.");
        config.setStatus(ConfigurationStatus.PAUSED);
        config.setStatusReason("Pausada por el negocio.");
        configurations.save(config);
        audit.record(member, AuditAction.INVOICING_ACTIVATION_CHANGED, "INVOICING", config.getId(),
                Map.of("from", previous.name(), "to", "PAUSED"));
        return view(member.company(), config, profile(member.companyId()));
    }

    // ─── Series ──────────────────────────────────────────────────────────────

    @Transactional
    public InvoicingConfigurationView createSeries(Member member, SeriesRequest request) {
        requirePlan(member.company());
        InvoicingConfiguration config = lock(member.companyId());
        TaxProfile profile = profile(member.companyId());
        DocumentType type = parse(DocumentType.class, request.documentType(), "Tipo de comprobante inválido.");
        String series = normalizeSeries(type, request.series());
        Environment environment = config.getProvider().environment();
        if (seriesExists(member.companyId(), environment, series)) {
            throw BusinessException.conflict("SERIES_EXISTS", "La serie " + series + " ya existe.");
        }
        DocumentSeries row = new DocumentSeries();
        row.setCompanyId(member.companyId());
        row.setEnvironment(environment);
        row.setDocumentType(type);
        row.setSeries(series);
        row.setCurrentNumber(request.currentNumber() == null ? 0 : validNumber(request.currentNumber()));
        row.setEnabled(request.enabled() == null || request.enabled());
        if (row.isEnabled() && !typeAllowed(profile, type, series)) row.setEnabled(false);
        seriesRepository.save(row);
        audit.record(member, AuditAction.INVOICE_SERIES_CHANGED, "DOCUMENT_SERIES", row.getId(),
                Map.of("series", series, "type", type.name(), "enabled", row.isEnabled()));
        return view(member.company(), config, profile);
    }

    @Transactional
    public InvoicingConfigurationView updateSeries(Member member, Long id, SeriesRequest request) {
        requirePlan(member.company());
        InvoicingConfiguration config = lock(member.companyId());
        TaxProfile profile = profile(member.companyId());
        DocumentSeries row = seriesRepository.findByIdAndCompanyId(id, member.companyId())
                .orElseThrow(() -> new NotFoundException("Serie no encontrada"));
        if (request.enabled() != null) {
            if (request.enabled() && !typeAllowed(profile, row.getDocumentType(), row.getSeries())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, TYPE_NOT_ALLOWED,
                        "Tu perfil fiscal no permite emitir " + row.getDocumentType().label().toLowerCase(Locale.ROOT) + ".");
            }
            row.setEnabled(request.enabled());
        }
        if (request.currentNumber() != null && request.currentNumber() != row.getCurrentNumber()) {
            // Solo para continuar una numeración que venía de otro sistema, antes de emitir en Fluxy.
            if (documents.existsByCompanyIdAndEnvironmentAndSeries(member.companyId(), row.getEnvironment(), row.getSeries())) {
                throw BusinessException.conflict("SERIES_IN_USE", "La serie ya tiene comprobantes: su correlativo no se puede cambiar.");
            }
            row.setCurrentNumber(validNumber(request.currentNumber()));
        }
        seriesRepository.save(row);
        audit.record(member, AuditAction.INVOICE_SERIES_CHANGED, "DOCUMENT_SERIES", row.getId(),
                Map.of("series", row.getSeries(), "enabled", row.isEnabled(), "currentNumber", row.getCurrentNumber()));
        return view(member.company(), config, profile);
    }

    // ─── Autorización para emitir ────────────────────────────────────────────

    /**
     * Se llama antes de crear y antes de enviar cada comprobante: reconstruye el permiso desde lo
     * guardado (plan, configuración ACTIVE y perfil fiscal), nunca desde lo que mandó el cliente.
     */
    @Transactional(readOnly = true)
    public Authorization authorize(Long companyId, DocumentType type, DocumentType relatedType) {
        Company company = companies.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa no encontrada"));
        entitlements.require(company, Feature.ELECTRONIC_INVOICING);
        InvoicingConfiguration config = configurations.findByCompanyId(companyId).orElse(null);
        if (config == null || config.getStatus() != ConfigurationStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, NOT_ACTIVE,
                    "La facturación electrónica no está activa para este negocio.");
        }
        TaxProfile profile = profiles.findByCompanyId(companyId).orElse(null);
        DocumentType effective = type == DocumentType.NOTA_CREDITO ? relatedType : type;
        boolean allowed = profile != null && profile.verified()
                && (effective == DocumentType.BOLETA ? profile.isCanIssueReceipt() : profile.isCanIssueInvoice());
        if (!allowed) {
            throw new BusinessException(HttpStatus.FORBIDDEN, TYPE_NOT_ALLOWED,
                    "Tu perfil fiscal no permite emitir " + type.label().toLowerCase(Locale.ROOT) + ".");
        }
        return new Authorization(config, profile, company);
    }

    public record Authorization(InvoicingConfiguration config, TaxProfile profile, Company company) {}

    /** Credenciales descifradas, solo en memoria. */
    public ProviderContext context(InvoicingConfiguration config) {
        String token = config.getProviderTokenCipher() == null ? null : cipher.decrypt(config.getProviderTokenCipher());
        return new ProviderContext(config.getProviderEndpoint(), token, config.getTaxId());
    }

    // ─── Revalidación periódica ──────────────────────────────────────────────

    /**
     * Vuelve a consultar el RUC de los negocios que emiten de verdad. Si dejó de estar activo o
     * habido, la facturación se suspende (sin tocar lo ya emitido) y se avisa al dueño. Si el
     * servicio no responde no se cambia nada.
     */
    @Transactional
    public int revalidateActive() {
        if (!rucLookup.configured()) return 0;
        int suspended = 0;
        for (InvoicingConfiguration config : configurations.findByStatusAndProviderNot(ConfigurationStatus.ACTIVE, ProviderCode.SANDBOX)) {
            TaxProfile profile = profiles.findByCompanyId(config.getCompanyId()).orElse(null);
            if (profile == null || profile.getRuc() == null) continue;
            if (profile.getLastCheckedAt() != null && profile.getLastCheckedAt().isAfter(LocalDateTime.now().minus(REVALIDATE_EVERY))) continue;
            try {
                applyLookup(profile, profile.getRuc());
            } catch (RucLookup.Unavailable e) {
                continue;
            }
            if (!profile.verified()) {
                profile.setVerificationStatus(VerificationStatus.SUSPENDED);
                config.setStatus(ConfigurationStatus.SUSPENDED);
                config.setStatusReason(profile.getVerificationMessage());
                suspended++;
                audit.record(config.getCompanyId(), null, AuditAction.TAX_PROFILE_SUSPENDED, "TAX_PROFILE", config.getCompanyId(),
                        Map.of("ruc", TaxIdValidator.mask(profile.getRuc()), "reason", String.valueOf(profile.getVerificationMessage())));
                notifyOwner(config.getCompanyId(), "Facturación electrónica suspendida",
                        "Revisamos tu RUC y " + lower(profile.getVerificationMessage())
                                + " Mientras tanto no se emiten comprobantes; lo ya emitido no cambia.");
            }
            recompute(config, profile);
            profiles.save(profile);
            configurations.save(config);
        }
        return suspended;
    }

    // ─── Apoyo ───────────────────────────────────────────────────────────────

    private void applyLookup(TaxProfile profile, String ruc) {
        Optional<RucLookup.RucInfo> info;
        try {
            info = rucLookup.lookup(ruc);
        } catch (RucLookup.Unavailable e) {
            profile.setVerificationStatus(VerificationStatus.ERROR);
            profile.setVerificationMessage(e.getMessage() + " No se asume válido: probá de nuevo.");
            throw e;
        }
        profile.setLastCheckedAt(LocalDateTime.now());
        profile.setVerificationSource(TaxProfile.SOURCE_RUC_API);
        if (info.isEmpty()) {
            profile.setVerificationStatus(VerificationStatus.REQUIRES_ACTION);
            profile.setVerificationMessage("SUNAT no tiene registrado ese RUC.");
            return;
        }
        RucLookup.RucInfo data = info.get();
        profile.setBusinessName(clean(data.businessName(), 200));
        profile.setRucStatus(data.status());
        profile.setRucCondition(data.condition());
        profile.setFiscalAddress(clean(data.address(), 300));
        if (data.status() != null && !"ACTIVO".equals(data.status())) {
            profile.setVerificationStatus(VerificationStatus.REQUIRES_ACTION);
            profile.setVerificationMessage("El RUC figura como " + data.status() + " en SUNAT.");
        } else if (data.condition() != null && !"HABIDO".equals(data.condition())) {
            profile.setVerificationStatus(VerificationStatus.REQUIRES_ACTION);
            profile.setVerificationMessage("El domicilio fiscal figura como " + data.condition() + " en SUNAT.");
        } else {
            profile.setVerificationStatus(VerificationStatus.VERIFIED);
            profile.setVerificationMessage("RUC activo y habido según SUNAT.");
            profile.setVerifiedAt(LocalDateTime.now());
        }
    }

    /** Capacidades calculadas en el servidor. */
    void recompute(InvoicingConfiguration config, TaxProfile profile) {
        ElectronicBillingProvider provider = providers.get(config.getProvider());
        boolean verifiedForEnvironment = profile.verified()
                && (config.getProvider().environment() == Environment.TEST
                || TaxProfile.SOURCE_RUC_API.equals(profile.getVerificationSource()));
        profile.setElectronicIssuer(config.isConnectionOk());
        profile.setCanIssueReceipt(verifiedForEnvironment && provider.supportedTypes().contains(DocumentType.BOLETA));
        profile.setCanIssueInvoice(verifiedForEnvironment && profile.getTaxRegime() != TaxRegime.NRUS
                && provider.supportedTypes().contains(DocumentType.FACTURA));
    }

    private List<InvoicingConfigurationView.CheckItem> checklist(Company company, InvoicingConfiguration config, TaxProfile profile) {
        Environment environment = config.getProvider().environment();
        List<DocumentSeries> series = seriesRepository.findByCompanyIdAndEnvironmentOrderByDocumentTypeAscSeriesAsc(company.getId(), environment);
        boolean saleSeries = series.stream().anyMatch(s -> s.isEnabled()
                && ((s.getDocumentType() == DocumentType.BOLETA && profile.isCanIssueReceipt())
                || (s.getDocumentType() == DocumentType.FACTURA && profile.isCanIssueInvoice())));
        boolean verifiedForEnvironment = profile.isCanIssueReceipt() || profile.isCanIssueInvoice();
        List<InvoicingConfigurationView.CheckItem> items = new ArrayList<>();
        items.add(new InvoicingConfigurationView.CheckItem("plan", "Plan con facturación electrónica", entitlements.has(company, Feature.ELECTRONIC_INVOICING),
                "Disponible desde el plan Pro."));
        items.add(new InvoicingConfigurationView.CheckItem("ruc", "RUC verificado", verifiedForEnvironment,
                profile.getVerificationMessage() != null ? profile.getVerificationMessage() : "Ingresá y verificá tu RUC."));
        items.add(new InvoicingConfigurationView.CheckItem("fiscal", "Razón social y dirección fiscal",
                config.getBusinessName() != null && config.getFiscalAddress() != null, "Completá la dirección fiscal."));
        items.add(new InvoicingConfigurationView.CheckItem("connection", "Conexión con el proveedor probada", config.isConnectionOk(),
                config.getConnectionMessage() != null ? config.getConnectionMessage() : "Probá la conexión."));
        items.add(new InvoicingConfigurationView.CheckItem("series", "Serie habilitada para boleta o factura", saleSeries,
                "Habilitá al menos una serie que tu perfil fiscal permita."));
        return items;
    }

    private InvoicingConfigurationView view(Company company, InvoicingConfiguration config, TaxProfile profile) {
        ElectronicBillingProvider provider = providers.get(config.getProvider());
        Environment environment = config.getProvider().environment();
        List<DocumentSeries> rows = seriesRepository.findByCompanyIdAndEnvironmentOrderByDocumentTypeAscSeriesAsc(company.getId(), environment);
        List<InvoicingConfigurationView.Series> series = rows.stream().map(s -> new InvoicingConfigurationView.Series(s.getId(), s.getDocumentType().name(), s.getSeries(),
                s.getCurrentNumber(), s.getCurrentNumber() + 1, s.isEnabled(),
                documents.existsByCompanyIdAndEnvironmentAndSeries(company.getId(), environment, s.getSeries()),
                typeAllowed(profile, s.getDocumentType(), s.getSeries()))).toList();
        List<InvoicingConfigurationView.CheckItem> checklist = checklist(company, config, profile);
        InvoicingConfigurationView.Profile profileView = new InvoicingConfigurationView.Profile(profile.getRuc(), profile.getBusinessName(), profile.getRucStatus(),
                profile.getRucCondition(), profile.getFiscalAddress(), profile.getTaxRegime().name(),
                profile.getVerificationStatus().name(), profile.getVerificationMessage(), profile.getVerificationSource(),
                BusinessClock.withOffset(profile.getVerifiedAt()), BusinessClock.withOffset(profile.getLastCheckedAt()),
                profile.isElectronicIssuer(), profile.isCanIssueReceipt(), profile.isCanIssueInvoice());
        return new InvoicingConfigurationView(config.getStatus().name(), config.getStatusReason(), environment.name(), config.getProvider().name(),
                config.getProvider().label(), endpointHint(config.getProviderEndpoint()), config.getProviderTokenHint(),
                config.getProviderTokenCipher() != null && config.getProviderEndpoint() != null, provider.requiresCredentials(),
                config.isConnectionOk(), BusinessClock.withOffset(config.getConnectionCheckedAt()), config.getConnectionMessage(),
                config.getTaxId(), config.getBusinessName(), config.getTradeName(), config.getFiscalAddress(),
                config.isAutomaticIssuing(), config.getIssueTrigger().name(), config.isEmailEnabled(), config.isAttachPdf(),
                config.isAttachXml(), config.isPrintAutomatically(), config.getPaperWidth(), config.getTaxAffectation().name(),
                BusinessClock.withOffset(config.getActivatedAt()), profileView, series, checklist,
                checklist.stream().allMatch(InvoicingConfigurationView.CheckItem::ok) && config.getStatus() != ConfigurationStatus.ACTIVE,
                entitlements.has(company, Feature.ELECTRONIC_INVOICING), rucLookup.configured(),
                Arrays.stream(ProviderCode.values()).map(Enum::name).toList());
    }

    /** "api.nubefact.com/…f3c2": suficiente para reconocerla sin mostrar la ruta completa. */
    private static String endpointHint(String endpoint) {
        if (endpoint == null) return null;
        try {
            URI uri = URI.create(endpoint);
            String tail = endpoint.length() > 4 ? endpoint.substring(endpoint.length() - 4) : endpoint;
            return uri.getHost() + "/…" + tail;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    InvoicingConfiguration getOrCreate(Long companyId) {
        return configurations.findByCompanyId(companyId).orElseGet(() -> {
            InvoicingConfiguration config = new InvoicingConfiguration();
            config.setCompanyId(companyId);
            companies.findById(companyId).ifPresent(c -> config.setTradeName(c.getName()));
            InvoicingConfiguration saved = configurations.save(config);
            ensureDefaultSeries(companyId, saved.getProvider().environment(), profile(companyId));
            return saved;
        });
    }

    private InvoicingConfiguration lock(Long companyId) {
        getOrCreate(companyId);
        return configurations.findByCompanyIdForUpdate(companyId).orElseThrow();
    }

    TaxProfile profile(Long companyId) {
        return profiles.findByCompanyId(companyId).orElseGet(() -> {
            TaxProfile profile = new TaxProfile();
            profile.setCompanyId(companyId);
            return profiles.save(profile);
        });
    }

    /** B001, F001 y sus notas de crédito BC01 y FC01. Las de factura nacen apagadas si el perfil no las permite. */
    private void ensureDefaultSeries(Long companyId, Environment environment, TaxProfile profile) {
        if (!seriesRepository.findByCompanyIdAndEnvironmentOrderByDocumentTypeAscSeriesAsc(companyId, environment).isEmpty()) return;
        createDefault(companyId, environment, DocumentType.BOLETA, "B001", true);
        createDefault(companyId, environment, DocumentType.FACTURA, "F001", profile.isCanIssueInvoice());
        createDefault(companyId, environment, DocumentType.NOTA_CREDITO, "BC01", true);
        createDefault(companyId, environment, DocumentType.NOTA_CREDITO, "FC01", profile.isCanIssueInvoice());
    }

    private void createDefault(Long companyId, Environment environment, DocumentType type, String series, boolean enabled) {
        DocumentSeries row = new DocumentSeries();
        row.setCompanyId(companyId);
        row.setEnvironment(environment);
        row.setDocumentType(type);
        row.setSeries(series);
        row.setEnabled(enabled);
        seriesRepository.save(row);
    }

    /** Las series de factura sin usar siguen a la capacidad del perfil; las usadas no se tocan. */
    private void syncSeriesWithCapabilities(InvoicingConfiguration config, TaxProfile profile) {
        Environment environment = config.getProvider().environment();
        ensureDefaultSeries(config.getCompanyId(), environment, profile);
        for (DocumentSeries s : seriesRepository.findByCompanyIdAndEnvironmentOrderByDocumentTypeAscSeriesAsc(config.getCompanyId(), environment)) {
            boolean allowed = typeAllowed(profile, s.getDocumentType(), s.getSeries());
            if (!allowed && s.isEnabled()) {
                s.setEnabled(false);
                seriesRepository.save(s);
            } else if (allowed && !s.isEnabled() && s.getCurrentNumber() == 0
                    && ("F001".equals(s.getSeries()) || "FC01".equals(s.getSeries()))) {
                s.setEnabled(true);
                seriesRepository.save(s);
            }
        }
    }

    private static boolean typeAllowed(TaxProfile profile, DocumentType type, String series) {
        char prefix = series == null || series.isEmpty() ? ' ' : series.charAt(0);
        return switch (type) {
            case BOLETA -> true;
            case FACTURA -> profile.isCanIssueInvoice();
            case NOTA_CREDITO -> prefix == 'B' || profile.isCanIssueInvoice();
        };
    }

    private boolean seriesExists(Long companyId, Environment environment, String series) {
        return seriesRepository.findByCompanyIdAndEnvironmentOrderByDocumentTypeAscSeriesAsc(companyId, environment).stream()
                .anyMatch(s -> s.getSeries().equals(series));
    }

    static String normalizeSeries(DocumentType type, String value) {
        String series = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        boolean ok = switch (type) {
            case BOLETA -> series.matches("^B[A-Z0-9]{3}$");
            case FACTURA -> series.matches("^F[A-Z0-9]{3}$");
            case NOTA_CREDITO -> series.matches("^[BF][A-Z0-9]{3}$");
        };
        if (!ok) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SERIES_INVALID", switch (type) {
                case BOLETA -> "La serie de boletas son 4 caracteres y empieza con B (por ejemplo B001).";
                case FACTURA -> "La serie de facturas son 4 caracteres y empieza con F (por ejemplo F001).";
                case NOTA_CREDITO -> "La serie de notas de crédito empieza con B (para boletas) o F (para facturas), por ejemplo BC01.";
            });
        }
        return series;
    }

    private static long validNumber(Long value) {
        if (value == null || value < 0 || value > 99_999_999L) throw new BusinessException("El correlativo tiene que estar entre 0 y 99999999.");
        return value;
    }

    private void requirePlan(Company company) {
        entitlements.require(company, Feature.ELECTRONIC_INVOICING);
    }

    private void notifyOwner(Long companyId, String title, String text) {
        try {
            User owner = users.findFirstByCompanyIdAndRoleOrderByIdAsc(companyId, Role.BUSINESS_OWNER).orElse(null);
            if (owner != null) email.sendBillingNotice(owner.getEmail(), owner.getFullName(), title, text);
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar al dueño de la empresa {}: {}", companyId, e.getMessage());
        }
    }

    private static String lower(String text) {
        if (text == null || text.isEmpty()) return "";
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static String clean(String value, int max) {
        if (value == null) return null;
        String v = value.strip().replaceAll("\\s+", " ");
        if (v.isEmpty()) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(message);
        }
    }
}
