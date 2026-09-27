package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.customer.CustomerSource;
import com.fluxyBackend.customer.activity.CustomerActivityService;
import com.fluxyBackend.customer.activity.CustomerActivityType;
import com.fluxyBackend.customer.segmentation.CustomerSegmentType;
import com.fluxyBackend.customer.segmentation.CustomerSegmentationService;
import com.fluxyBackend.entity.Customer;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.marketing.repository.MarketingCampaignRepository;
import com.fluxyBackend.repository.CustomerRepository;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CRM ligero sobre los compradores de la tienda: datos de contacto, etiquetas manuales, notas
 * internas, origen, segmentos automáticos (CustomerSegmentationService) y línea de tiempo
 * (CustomerActivityService). Todo se consulta por empresa.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private static final int MAX_TAGS = 10;
    private static final int MAX_TAG_LENGTH = 30;

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final CustomerSegmentationService segmentation;
    private final CustomerActivityService activity;
    private final MarketingCampaignRepository campaigns;
    private final BusinessClock clock;

    // ─── Vista ────────────────────────────────────────────────────────────────

    /** Fila del listado: resumen liviano, sin pedidos ni notas. */
    public record CustomerSummary(Long id, String name, String phone, String email, List<String> tags,
                                  long ordersCount, long saleOrders, double totalSpent,
                                  OffsetDateTime lastOrderAt, OffsetDateTime createdAt,
                                  List<String> segments, String source, boolean marketingOptOut) {}

    public record CustomerOrder(Long id, OffsetDateTime createdAt, String status, double total, int items) {}

    /** Preferencias de compra calculadas de sus pedidos no cancelados. */
    public record Preferences(String favoriteCategory, String mostPurchasedProduct, long totalProductsPurchased) {}

    public record Segment(String type, String label, String rule) {}

    /**
     * Perfil. notes viene null para quien no tiene CUSTOMER_NOTES (el panel no decide qué se ve).
     * recentOrders son los últimos 20; el historial completo va paginado en /customers/{id}/orders.
     */
    public record CustomerDetail(Long id, String name, String phone, String email, String address, String notes,
                                 List<String> tags, long ordersCount, long saleOrders, double totalSpent,
                                 double averageTicket, OffsetDateTime lastOrderAt, OffsetDateTime createdAt,
                                 List<CustomerOrder> recentOrders, boolean marketingOptOut,
                                 Long daysSinceLastPurchase, List<Segment> segments, String source, String sourceLabel,
                                 Long sourceCampaignId, String sourceCampaignName, Preferences preferences,
                                 boolean notesVisible) {}

    /** marketingOptOut: true si pidió no recibir promociones; null lo deja como está. */
    public record CustomerRequest(String name, String phone, String email, String address, String notes,
                                  List<String> tags, Boolean marketingOptOut) {}

    /** Filtros del listado; todos opcionales. */
    public record Filters(String query, String tag, String segment, String source, Boolean marketingAllowed,
                          LocalDate lastPurchaseFrom, LocalDate lastPurchaseTo) {}

    // ─── Pedidos → clientes ──────────────────────────────────────────────────

    /** Compatibilidad: pedidos anteriores al CRM, sin origen conocido. */
    @Transactional
    public Customer resolveForOrder(Long companyId, String name, String phone, String address) {
        return resolveForOrder(companyId, name, phone, address, CustomerSource.POS);
    }

    /**
     * Cliente de un pedido: el existente con ese teléfono, o con ese nombre si no dejó teléfono; si
     * no hay, uno nuevo con el origen del pedido. Actualiza los datos de contacto con los del último pedido.
     */
    @Transactional
    public Customer resolveForOrder(Long companyId, String name, String phone, String address, CustomerSource source) {
        String cleanName = clean(name, 150);
        if (cleanName == null) cleanName = "Cliente sin nombre";
        String phoneKey = phoneKey(phone);
        String nameKey = nameKey(cleanName);

        Optional<Customer> existing = phoneKey != null
                ? customerRepository.findFirstByCompanyIdAndPhoneKey(companyId, phoneKey)
                : customerRepository.findFirstByCompanyIdAndPhoneKeyIsNullAndNameKey(companyId, nameKey);

        Customer customer = existing.orElseGet(Customer::new);
        boolean created = customer.getId() == null;
        if (created) {
            customer.setCompanyId(companyId);
            customer.setPhoneKey(phoneKey);
            customer.setSource(source);
        }
        customer.setName(cleanName);
        customer.setNameKey(nameKey);
        if (phoneKey != null) customer.setPhone(clean(phone, 30));
        String cleanAddress = clean(address, 300);
        if (cleanAddress != null) customer.setAddress(cleanAddress);
        Customer saved = customerRepository.save(customer);
        if (created) {
            activity.record(companyId, saved.getId(), CustomerActivityType.CUSTOMER_CREATED, null,
                    "Cliente registrado desde " + source.label().toLowerCase(Locale.ROOT), null, null);
        }
        return saved;
    }

    /** Asocia a un cliente los pedidos que se hicieron antes de existir este módulo. */
    @Transactional
    public int linkOrdersWithoutCustomer(Long companyId) {
        List<Order> orders = orderRepository.findWithoutCustomer(companyId);
        for (Order order : orders) {
            order.setCustomer(resolveForOrder(companyId, order.getCustomerName(),
                    order.getCustomerPhone(), order.getCustomerAddress(),
                    order.getOwner() == null ? CustomerSource.ONLINE_STORE : CustomerSource.POS));
        }
        return orders.size();
    }

    // ─── Consultas ────────────────────────────────────────────────────────────

    /**
     * Totales y segmentos salen de dos consultas agregadas (sin N+1); el filtrado y el orden se hacen
     * en memoria sobre ese resultado y la respuesta va paginada.
     */
    public PageResponse<CustomerSummary> list(Long companyId, Filters filters, String sort, String direction, int page, int size) {
        Map<Long, OrderRepository.CustomerStats> stats = statsByCustomer(companyId);
        Map<Long, CustomerSegmentationService.Stats> purchases = segmentation.statsByCustomer(companyId);
        LocalDateTime now = LocalDateTime.now();
        String query = filters.query();
        String q = query == null ? "" : nameKey(query);
        String qDigits = query == null ? "" : query.replaceAll("\\D", "");
        String tagFilter = filters.tag() == null || filters.tag().isBlank() ? null : normalizeTag(filters.tag());
        CustomerSegmentType segmentFilter = parse(CustomerSegmentType.class, filters.segment(), "Segmento inválido.");
        CustomerSource sourceFilter = parse(CustomerSource.class, filters.source(), "Origen inválido.");
        ZoneId zone = clock.zone(companyId);
        LocalDateTime from = filters.lastPurchaseFrom() == null ? null : clock.startOf(filters.lastPurchaseFrom(), zone);
        LocalDateTime to = filters.lastPurchaseTo() == null ? null : clock.startOf(filters.lastPurchaseTo().plusDays(1), zone);

        Comparator<CustomerSummary> comparator = switch (sort == null ? "" : sort) {
            case "totalSpent" -> Comparator.comparingDouble(CustomerSummary::totalSpent);
            case "ordersCount" -> Comparator.comparingLong(CustomerSummary::ordersCount);
            case "name" -> Comparator.comparing(c -> c.name().toLowerCase(Locale.ROOT));
            case "createdAt" -> Comparator.comparing(CustomerSummary::createdAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            default -> Comparator.comparing(CustomerSummary::lastOrderAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        };
        if (!"asc".equalsIgnoreCase(direction)) comparator = comparator.reversed();

        List<CustomerSummary> all = customerRepository.findByCompanyId(companyId).stream()
                .filter(c -> q.isEmpty()
                        || (c.getNameKey() != null && c.getNameKey().contains(q))
                        || (!qDigits.isEmpty() && c.getPhoneKey() != null && c.getPhoneKey().contains(qDigits))
                        || (c.getEmail() != null && c.getEmail().toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT))))
                .filter(c -> tagFilter == null || splitTags(c.getTags()).contains(tagFilter))
                .filter(c -> sourceFilter == null || sourceFilter == c.getSource())
                .filter(c -> filters.marketingAllowed() == null || filters.marketingAllowed() == !c.optedOutOfMarketing())
                .filter(c -> {
                    if (from == null && to == null) return true;
                    CustomerSegmentationService.Stats p = purchases.get(c.getId());
                    LocalDateTime last = p == null ? null : p.lastOrderAt();
                    return last != null && (from == null || !last.isBefore(from)) && (to == null || last.isBefore(to));
                })
                .map(c -> summary(c, stats.get(c.getId()), segmentation.classify(purchases.get(c.getId()), now)))
                .filter(c -> segmentFilter == null || c.segments().contains(segmentFilter.name()))
                .sorted(comparator)
                .toList();
        return PageResponse.slice(all, page, PageResponse.clampSize(size));
    }

    public List<String> allTags(Long companyId) {
        return customerRepository.findByCompanyId(companyId).stream()
                .flatMap(c -> splitTags(c.getTags()).stream())
                .distinct().sorted().toList();
    }

    /** Perfil de un cliente: solo consulta sus propios datos (no los de toda la empresa). */
    @Transactional(readOnly = true)
    public CustomerDetail detail(Long companyId, Long customerId, boolean notesVisible) {
        Customer customer = find(companyId, customerId);
        OrderRepository.CustomerStats stats = orderRepository.customerStatsOf(companyId, customerId, OrderStatus.SALE)
                .stream().findFirst().orElse(null);
        long orders = stats == null || stats.getOrders() == null ? 0 : stats.getOrders();
        long saleOrders = stats == null || stats.getSaleOrders() == null ? 0 : stats.getSaleOrders();
        double spent = stats == null || stats.getSpent() == null ? 0 : stats.getSpent();
        LocalDateTime now = LocalDateTime.now();
        CustomerSegmentationService.Stats purchases = segmentation.statsOf(companyId, customerId).orElse(null);
        List<Segment> segments = segmentation.classify(purchases, now).stream()
                .map(s -> new Segment(s.name(), s.label(), segmentation.rule(s))).toList();
        Long daysSince = purchases == null || purchases.lastOrderAt() == null ? null
                : ChronoUnit.DAYS.between(purchases.lastOrderAt().toLocalDate(), now.toLocalDate());

        List<CustomerOrder> recent = orderRepository.findByCompanyIdAndCustomer_Id(companyId, customerId,
                        PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::order)
                .getContent();

        String campaignName = customer.getSourceCampaignId() == null ? null
                : campaigns.findByIdAndCompanyId(customer.getSourceCampaignId(), companyId).map(c -> c.getName()).orElse(null);
        CustomerSource source = customer.getSource();

        return new CustomerDetail(customer.getId(), customer.getName(), customer.getPhone(), customer.getEmail(),
                customer.getAddress(), notesVisible ? customer.getNotes() : null, splitTags(customer.getTags()), orders,
                saleOrders, spent, saleOrders > 0 ? spent / saleOrders : 0,
                stats == null ? null : BusinessClock.withOffset(stats.getLastOrderAt()),
                BusinessClock.withOffset(customer.getCreatedAt()), recent, customer.optedOutOfMarketing(), daysSince,
                segments, source == null ? null : source.name(),
                source == null ? null : source == CustomerSource.CAMPAIGN && campaignName != null ? "Campaña " + campaignName : source.label(),
                customer.getSourceCampaignId(), campaignName, preferences(companyId, customerId), notesVisible);
    }

    /** Historial de pedidos paginado (independiente del perfil). */
    @Transactional(readOnly = true)
    public PageResponse<CustomerOrder> orders(Long companyId, Long customerId, int page, int size) {
        find(companyId, customerId);
        return PageResponse.of(orderRepository.findByCompanyIdAndCustomer_Id(companyId, customerId,
                PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size),
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))), this::order);
    }

    /** Línea de tiempo paginada. */
    public PageResponse<CustomerActivityService.View> activity(Long companyId, Long customerId, int page, int size) {
        find(companyId, customerId);
        return activity.page(companyId, customerId, page, size);
    }

    public List<Segment> segments(Long companyId, Long customerId) {
        find(companyId, customerId);
        return segmentation.classify(segmentation.statsOf(companyId, customerId).orElse(null), LocalDateTime.now()).stream()
                .map(s -> new Segment(s.name(), s.label(), segmentation.rule(s))).toList();
    }

    // ─── Escritura ────────────────────────────────────────────────────────────

    @Transactional
    public CustomerDetail create(com.fluxyBackend.security.access.Member member, CustomerRequest request) {
        Long companyId = member.companyId();
        String name = clean(request.name(), 150);
        if (name == null) throw new BusinessException("El nombre del cliente es obligatorio.");
        String phoneKey = phoneKey(request.phone());
        if (phoneKey != null && customerRepository.findFirstByCompanyIdAndPhoneKey(companyId, phoneKey).isPresent()) {
            throw BusinessException.conflict("CUSTOMER_PHONE_EXISTS", "Ya tenés un cliente con ese teléfono.");
        }
        Customer customer = new Customer();
        customer.setCompanyId(companyId);
        customer.setSource(CustomerSource.MANUAL);
        requireFieldPermissions(member, customer, request);
        apply(customer, request, name, phoneKey);
        Customer saved = customerRepository.save(customer);
        activity.record(companyId, saved.getId(), CustomerActivityType.CUSTOMER_CREATED, null,
                "Cliente creado a mano", null, member.displayName());
        return detail(companyId, saved.getId(), member.can(Permission.CUSTOMER_NOTES));
    }

    @Transactional
    public CustomerDetail update(com.fluxyBackend.security.access.Member member, Long customerId, CustomerRequest request) {
        Long companyId = member.companyId();
        Customer customer = find(companyId, customerId);
        String name = request.name() == null ? customer.getName() : clean(request.name(), 150);
        if (name == null) throw new BusinessException("El nombre del cliente es obligatorio.");
        String phoneKey = request.phone() == null ? customer.getPhoneKey() : phoneKey(request.phone());
        if (phoneKey != null && !phoneKey.equals(customer.getPhoneKey())) {
            customerRepository.findFirstByCompanyIdAndPhoneKey(companyId, phoneKey)
                    .filter(other -> !other.getId().equals(customerId))
                    .ifPresent(other -> {
                        throw BusinessException.conflict("CUSTOMER_PHONE_EXISTS",
                                "Ese teléfono ya pertenece a " + other.getName() + ".");
                    });
        }
        requireFieldPermissions(member, customer, request);
        Snapshot before = Snapshot.of(customer);
        apply(customer, request, name, phoneKey);
        customerRepository.save(customer);
        recordChanges(member, customer, before);
        return detail(companyId, customerId, member.can(Permission.CUSTOMER_NOTES));
    }

    /** Notas y etiquetas tienen su propio permiso; el panel las oculta, pero la regla vive acá. */
    private static void requireFieldPermissions(com.fluxyBackend.security.access.Member member, Customer customer, CustomerRequest request) {
        if (request.notes() != null && !Objects.equals(blankToNull(request.notes()), customer.getNotes())
                && !member.can(Permission.CUSTOMER_NOTES)) {
            throw AccessService.missing(Permission.CUSTOMER_NOTES);
        }
        if (request.tags() != null && !member.can(Permission.CUSTOMER_TAGS)
                && !Objects.equals(joinTags(request.tags()), customer.getTags())) {
            throw AccessService.missing(Permission.CUSTOMER_TAGS);
        }
    }

    private record Snapshot(String name, String phone, String email, String address, String notes, List<String> tags,
                            boolean optOut) {
        static Snapshot of(Customer c) {
            return new Snapshot(c.getName(), c.getPhone(), c.getEmail(), c.getAddress(), c.getNotes(),
                    splitTags(c.getTags()), c.optedOutOfMarketing());
        }
    }

    private void recordChanges(com.fluxyBackend.security.access.Member member, Customer after, Snapshot before) {
        Long companyId = member.companyId();
        String actor = member.displayName();
        List<String> tags = splitTags(after.getTags());
        for (String tag : tags) {
            if (!before.tags().contains(tag)) {
                activity.record(companyId, after.getId(), CustomerActivityType.TAG_ADDED, null, "Etiqueta \"" + tag + "\" agregada", null, actor);
            }
        }
        for (String tag : before.tags()) {
            if (!tags.contains(tag)) {
                activity.record(companyId, after.getId(), CustomerActivityType.TAG_REMOVED, null, "Etiqueta \"" + tag + "\" quitada", null, actor);
            }
        }
        if (!Objects.equals(before.notes(), after.getNotes()) && after.getNotes() != null) {
            activity.record(companyId, after.getId(), CustomerActivityType.NOTE_CREATED, null, "Nota interna actualizada", null, actor);
        }
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(before.name(), after.getName())) fields.add("nombre");
        if (!Objects.equals(before.phone(), after.getPhone())) fields.add("teléfono");
        if (!Objects.equals(before.email(), after.getEmail())) fields.add("correo");
        if (!Objects.equals(before.address(), after.getAddress())) fields.add("dirección");
        if (before.optOut() != after.optedOutOfMarketing()) {
            fields.add(after.optedOutOfMarketing() ? "no recibe promociones" : "vuelve a recibir promociones");
        }
        if (!fields.isEmpty()) {
            activity.record(companyId, after.getId(), CustomerActivityType.CUSTOMER_UPDATED, null,
                    "Datos actualizados: " + String.join(", ", fields), null, actor);
        }
    }

    private void apply(Customer customer, CustomerRequest request, String name, String phoneKey) {
        customer.setName(name);
        customer.setNameKey(nameKey(name));
        if (request.phone() != null) {
            customer.setPhone(clean(request.phone(), 30));
            customer.setPhoneKey(phoneKey);
        }
        if (request.email() != null) {
            String email = clean(request.email(), 150);
            if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new BusinessException("El correo del cliente no es válido.");
            }
            customer.setEmail(email == null ? null : email.toLowerCase(Locale.ROOT));
        }
        if (request.address() != null) customer.setAddress(clean(request.address(), 300));
        if (request.notes() != null) {
            String notes = request.notes().strip();
            if (notes.length() > 4000) throw new BusinessException("Las notas no pueden superar los 4000 caracteres.");
            customer.setNotes(notes.isEmpty() ? null : notes);
        }
        if (request.tags() != null) customer.setTags(joinTags(request.tags()));
        if (request.marketingOptOut() != null) customer.setMarketingOptOut(request.marketingOptOut());
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private Customer find(Long companyId, Long customerId) {
        return customerRepository.findByIdAndCompanyId(customerId, companyId)
                .orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
    }

    private Map<Long, OrderRepository.CustomerStats> statsByCustomer(Long companyId) {
        return orderRepository.customerStats(companyId, OrderStatus.SALE).stream()
                .collect(Collectors.toMap(OrderRepository.CustomerStats::getCustomerId, Function.identity()));
    }

    private Preferences preferences(Long companyId, Long customerId) {
        List<OrderRepository.CustomerProduct> products = orderRepository.customerProducts(companyId, customerId, OrderStatus.CANCELLED);
        if (products.isEmpty()) return new Preferences(null, null, 0);
        long total = products.stream().mapToLong(p -> p.getUnits() == null ? 0 : p.getUnits()).sum();
        String favoriteCategory = products.stream().filter(p -> p.getCategory() != null)
                .collect(Collectors.groupingBy(OrderRepository.CustomerProduct::getCategory,
                        Collectors.summingLong(p -> p.getUnits() == null ? 0 : p.getUnits())))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return new Preferences(favoriteCategory, products.get(0).getName(), total);
    }

    private CustomerOrder order(Order o) {
        return new CustomerOrder(o.getId(), BusinessClock.withOffset(o.getCreatedAt()),
                o.getStatus() == null ? null : o.getStatus().name(),
                o.getTotal() == null ? 0 : o.getTotal(),
                o.getItems() == null ? 0 : o.getItems().size());
    }

    private CustomerSummary summary(Customer c, OrderRepository.CustomerStats stats, Set<CustomerSegmentType> segments) {
        return new CustomerSummary(c.getId(), c.getName(), c.getPhone(), c.getEmail(), splitTags(c.getTags()),
                stats == null ? 0 : stats.getOrders(),
                stats == null || stats.getSaleOrders() == null ? 0 : stats.getSaleOrders(),
                stats == null || stats.getSpent() == null ? 0 : stats.getSpent(),
                stats == null ? null : BusinessClock.withOffset(stats.getLastOrderAt()),
                BusinessClock.withOffset(c.getCreatedAt()),
                segments.stream().map(Enum::name).toList(),
                c.getSource() == null ? null : c.getSource().name(),
                c.optedOutOfMarketing());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(message);
        }
    }

    /** Solo dígitos; los 9 dígitos de un celular peruano llevan el 51 delante. */
    static String phoneKey(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 6) return null;
        if (digits.length() == 9) digits = "51" + digits;
        return digits.length() > 20 ? digits.substring(0, 20) : digits;
    }

    static String nameKey(String name) {
        if (name == null) return "";
        String key = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
        return key.length() > 150 ? key.substring(0, 150) : key;
    }

    private static String clean(String value, int max) {
        if (value == null) return null;
        String cleaned = value.strip().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalizeTag(String tag) {
        String t = tag.strip().toLowerCase(Locale.ROOT).replaceAll("[,\\s]+", " ");
        return t.length() > MAX_TAG_LENGTH ? t.substring(0, MAX_TAG_LENGTH) : t;
    }

    public static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return Arrays.stream(tags.split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList();
    }

    private static String joinTags(List<String> tags) {
        LinkedHashSet<String> unique = tags.stream()
                .filter(Objects::nonNull)
                .map(CustomerService::normalizeTag)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (unique.size() > MAX_TAGS) {
            throw new BusinessException("Un cliente puede tener hasta " + MAX_TAGS + " etiquetas.");
        }
        return unique.isEmpty() ? null : String.join(",", unique);
    }

    /** Clientes nuevos desde una fecha (hora del servidor). */
    public long countNewSince(Long companyId, LocalDateTime since) {
        return customerRepository.countByCompanyIdAndCreatedAtGreaterThanEqual(companyId, since);
    }
}
