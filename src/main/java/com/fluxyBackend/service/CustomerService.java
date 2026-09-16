package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.entity.Customer;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.repository.CustomerRepository;
import com.fluxyBackend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private static final int MAX_TAGS = 10;
    private static final int MAX_TAG_LENGTH = 30;

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;

    // ─── Vista ────────────────────────────────────────────────────────────────

    public record CustomerSummary(Long id, String name, String phone, String email, List<String> tags,
                                  long ordersCount, long saleOrders, double totalSpent,
                                  OffsetDateTime lastOrderAt, OffsetDateTime createdAt) {}

    public record CustomerOrder(Long id, OffsetDateTime createdAt, String status, double total, int items) {}

    public record CustomerDetail(Long id, String name, String phone, String email, String address, String notes,
                                 List<String> tags, long ordersCount, long saleOrders, double totalSpent,
                                 double averageTicket, OffsetDateTime lastOrderAt, OffsetDateTime createdAt,
                                 List<CustomerOrder> recentOrders) {}

    public record CustomerRequest(String name, String phone, String email, String address, String notes,
                                  List<String> tags) {}

    // ─── Pedidos → clientes ──────────────────────────────────────────────────

    /**
     * Cliente de un pedido: el existente con ese teléfono, o con ese nombre si
     * no dejó teléfono; si no hay, uno nuevo. Actualiza los datos de contacto
     * con los del último pedido.
     */
    @Transactional
    public Customer resolveForOrder(Long companyId, String name, String phone, String address) {
        String cleanName = clean(name, 150);
        if (cleanName == null) cleanName = "Cliente sin nombre";
        String phoneKey = phoneKey(phone);
        String nameKey = nameKey(cleanName);

        Optional<Customer> existing = phoneKey != null
                ? customerRepository.findFirstByCompanyIdAndPhoneKey(companyId, phoneKey)
                : customerRepository.findFirstByCompanyIdAndPhoneKeyIsNullAndNameKey(companyId, nameKey);

        Customer customer = existing.orElseGet(Customer::new);
        if (customer.getId() == null) {
            customer.setCompanyId(companyId);
            customer.setPhoneKey(phoneKey);
        }
        customer.setName(cleanName);
        customer.setNameKey(nameKey);
        if (phoneKey != null) customer.setPhone(clean(phone, 30));
        String cleanAddress = clean(address, 300);
        if (cleanAddress != null) customer.setAddress(cleanAddress);
        return customerRepository.save(customer);
    }

    /** Asocia a un cliente los pedidos que se hicieron antes de existir este módulo. */
    @Transactional
    public int linkOrdersWithoutCustomer(Long companyId) {
        List<Order> orders = orderRepository.findWithoutCustomer(companyId);
        for (Order order : orders) {
            order.setCustomer(resolveForOrder(companyId, order.getCustomerName(),
                    order.getCustomerPhone(), order.getCustomerAddress()));
        }
        return orders.size();
    }

    // ─── Consultas ────────────────────────────────────────────────────────────

    /**
     * El total gastado y la cantidad de pedidos se calculan agregando pedidos,
     * así que el filtrado y el orden se hacen en memoria sobre una sola consulta.
     * La respuesta igual va paginada.
     */
    public PageResponse<CustomerSummary> list(Long companyId, String query, String tag, String sort,
                                              String direction, int page, int size) {
        Map<Long, OrderRepository.CustomerStats> stats = statsByCustomer(companyId);
        String q = query == null ? "" : nameKey(query);
        String qDigits = query == null ? "" : query.replaceAll("\\D", "");
        String tagFilter = tag == null || tag.isBlank() ? null : normalizeTag(tag);

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
                .map(c -> summary(c, stats.get(c.getId())))
                .sorted(comparator)
                .toList();
        return PageResponse.slice(all, page, PageResponse.clampSize(size));
    }

    public List<String> allTags(Long companyId) {
        return customerRepository.findByCompanyId(companyId).stream()
                .flatMap(c -> splitTags(c.getTags()).stream())
                .distinct().sorted().toList();
    }

    // Transaccional: los ítems de cada pedido se cargan en forma diferida.
    @Transactional(readOnly = true)
    public CustomerDetail detail(Long companyId, Long customerId) {
        Customer customer = find(companyId, customerId);
        OrderRepository.CustomerStats stats = statsByCustomer(companyId).get(customerId);
        long orders = stats == null ? 0 : stats.getOrders();
        long saleOrders = stats == null || stats.getSaleOrders() == null ? 0 : stats.getSaleOrders();
        double spent = stats == null || stats.getSpent() == null ? 0 : stats.getSpent();

        List<CustomerOrder> recent = orderRepository.findByCompanyIdAndCustomer_Id(companyId, customerId,
                        PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(o -> new CustomerOrder(o.getId(), BusinessClock.withOffset(o.getCreatedAt()),
                        o.getStatus() == null ? null : o.getStatus().name(),
                        o.getTotal() == null ? 0 : o.getTotal(),
                        o.getItems() == null ? 0 : o.getItems().size()))
                .getContent();

        return new CustomerDetail(customer.getId(), customer.getName(), customer.getPhone(), customer.getEmail(),
                customer.getAddress(), customer.getNotes(), splitTags(customer.getTags()), orders, saleOrders,
                spent, saleOrders > 0 ? spent / saleOrders : 0,
                stats == null ? null : BusinessClock.withOffset(stats.getLastOrderAt()),
                BusinessClock.withOffset(customer.getCreatedAt()), recent);
    }

    // ─── Escritura ────────────────────────────────────────────────────────────

    @Transactional
    public CustomerDetail create(Long companyId, CustomerRequest request) {
        String name = clean(request.name(), 150);
        if (name == null) throw new BusinessException("El nombre del cliente es obligatorio.");
        String phoneKey = phoneKey(request.phone());
        if (phoneKey != null && customerRepository.findFirstByCompanyIdAndPhoneKey(companyId, phoneKey).isPresent()) {
            throw BusinessException.conflict("CUSTOMER_PHONE_EXISTS", "Ya tenés un cliente con ese teléfono.");
        }
        Customer customer = new Customer();
        customer.setCompanyId(companyId);
        apply(customer, request, name, phoneKey);
        return detail(companyId, customerRepository.save(customer).getId());
    }

    @Transactional
    public CustomerDetail update(Long companyId, Long customerId, CustomerRequest request) {
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
        apply(customer, request, name, phoneKey);
        customerRepository.save(customer);
        return detail(companyId, customerId);
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

    private CustomerSummary summary(Customer c, OrderRepository.CustomerStats stats) {
        return new CustomerSummary(c.getId(), c.getName(), c.getPhone(), c.getEmail(), splitTags(c.getTags()),
                stats == null ? 0 : stats.getOrders(),
                stats == null || stats.getSaleOrders() == null ? 0 : stats.getSaleOrders(),
                stats == null || stats.getSpent() == null ? 0 : stats.getSpent(),
                stats == null ? null : BusinessClock.withOffset(stats.getLastOrderAt()),
                BusinessClock.withOffset(c.getCreatedAt()));
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

    private static String normalizeTag(String tag) {
        String t = tag.strip().toLowerCase(Locale.ROOT).replaceAll("[,\\s]+", " ");
        return t.length() > MAX_TAG_LENGTH ? t.substring(0, MAX_TAG_LENGTH) : t;
    }

    static List<String> splitTags(String tags) {
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
