package com.fluxyBackend.service;

import com.fluxyBackend.entity.Complaint;
import com.fluxyBackend.entity.LegalAcceptance;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.repository.ComplaintRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Libro de Reclamaciones virtual: registra la hoja, entrega un código de constancia,
 * envía la copia al consumidor y controla el plazo de respuesta.
 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository repository;
    private final EmailService emailService;

    /** Plazo de respuesta en días hábiles. Sábados y domingos no cuentan; los feriados sí, así el plazo nunca se pasa. */
    @Value("${app.complaints.response_business_days:15}")
    private int responseBusinessDays;

    @Value("${app.complaints.notify_email:}")
    private String notifyEmail;

    @Value("${app.legal.provider_name:Fluxy}")
    private String providerName;

    @Value("${app.legal.provider_tax_id:}")
    private String providerTaxId;

    @Value("${app.legal.provider_address:}")
    private String providerAddress;

    public record ComplaintRequest(
            @NotNull Complaint.Type type,
            @NotBlank @Size(max = 150) String consumerName,
            @NotNull Complaint.DocumentType documentType,
            @NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "solo letras, números y guiones") String documentNumber,
            @NotBlank @Size(max = 300) String address,
            @Size(max = 30) String phone,
            @NotBlank @Email @Size(max = 254) String email,
            boolean minor,
            @Size(max = 150) String guardianName,
            @NotNull Complaint.ItemType itemType,
            @NotBlank @Size(max = 300) String itemDescription,
            @PositiveOrZero @Max(100_000_000) Double amount,
            @NotBlank @Size(max = 3000) String detail,
            @NotBlank @Size(max = 1500) String consumerRequest,
            boolean accepted) {}

    public record ProviderInfo(String name, String taxId, String address) {}

    public record Receipt(String code, Complaint.Type type, OffsetDateTime receivedAt, LocalDate dueDate, String email,
                          ProviderInfo provider) {}

    public record ComplaintView(Long id, String code, Complaint.Type type, String consumerName,
                                Complaint.DocumentType documentType, String documentNumber, String address, String phone,
                                String email, boolean minor, String guardianName, Complaint.ItemType itemType,
                                String itemDescription, Double amount, String detail, String consumerRequest,
                                OffsetDateTime receivedAt, LocalDate dueDate, boolean overdue, Complaint.Status status,
                                String response, OffsetDateTime respondedAt, String respondedBy) {}

    public ProviderInfo provider() {
        return new ProviderInfo(providerName, blankToNull(providerTaxId), blankToNull(providerAddress));
    }

    @Transactional
    public Receipt submit(ComplaintRequest request, String ipHash) {
        if (!request.accepted()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CONFIRMATION_REQUIRED",
                    "Confirmá que los datos son verdaderos para registrar la hoja.");
        }
        if (request.minor() && isBlank(request.guardianName())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "GUARDIAN_REQUIRED",
                    "Si sos menor de edad, indicá el nombre de tu padre, madre o apoderado.");
        }

        Complaint complaint = new Complaint();
        complaint.setType(request.type());
        complaint.setConsumerName(request.consumerName().strip());
        complaint.setDocumentType(request.documentType());
        complaint.setDocumentNumber(request.documentNumber().strip().toUpperCase(Locale.ROOT));
        complaint.setAddress(request.address().strip());
        complaint.setPhone(blankToNull(request.phone()));
        complaint.setEmail(request.email().strip().toLowerCase(Locale.ROOT));
        complaint.setMinor(request.minor());
        complaint.setGuardianName(request.minor() ? request.guardianName().strip() : null);
        complaint.setItemType(request.itemType());
        complaint.setItemDescription(request.itemDescription().strip());
        complaint.setAmount(request.amount());
        complaint.setDetail(request.detail().strip());
        complaint.setConsumerRequest(request.consumerRequest().strip());
        LocalDate today = LocalDate.now(BusinessClock.DEFAULT_ZONE);
        complaint.setReceivedAt(Instant.now());
        complaint.setDueDate(addBusinessDays(today, responseBusinessDays));
        complaint.setIpHash(ipHash);
        complaint.setLegalVersion(LegalAcceptance.TERMS_VERSION);
        repository.saveAndFlush(complaint);
        complaint.setCode(String.format("LR-%d-%06d", today.getYear(), complaint.getId()));

        ComplaintView view = view(complaint);
        ProviderInfo provider = provider();
        CompletableFuture.runAsync(() -> emailService.sendComplaintCopy(view, provider));
        if (!isBlank(notifyEmail)) {
            String to = notifyEmail.strip();
            CompletableFuture.runAsync(() -> emailService.sendComplaintAlert(to, view));
        }
        return new Receipt(complaint.getCode(), complaint.getType(), view.receivedAt(), complaint.getDueDate(),
                complaint.getEmail(), provider);
    }

    @Transactional(readOnly = true)
    public Page<ComplaintView> list(Complaint.Status status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "dueDate").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Complaint> result = status == null ? repository.findAll(pageable) : repository.findByStatus(status, pageable);
        return result.map(ComplaintService::view);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return repository.countByStatus(Complaint.Status.PENDING);
    }

    @Transactional
    public ComplaintView respond(Long id, String response, String respondedBy) {
        Complaint complaint = repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "COMPLAINT_NOT_FOUND",
                        "No existe esa hoja de reclamación."));
        if (complaint.getStatus() == Complaint.Status.ANSWERED) {
            throw BusinessException.conflict("COMPLAINT_ALREADY_ANSWERED", "Esta hoja ya fue respondida.");
        }
        if (isBlank(response) || response.strip().length() < 10) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RESPONSE_REQUIRED", "Escribí la respuesta al consumidor.");
        }
        if (response.length() > 3000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RESPONSE_TOO_LONG",
                    "La respuesta admite hasta 3000 caracteres.");
        }
        complaint.setResponse(response.strip());
        complaint.setRespondedAt(Instant.now());
        complaint.setRespondedBy(respondedBy);
        complaint.setStatus(Complaint.Status.ANSWERED);
        ComplaintView view = view(complaint);
        ProviderInfo provider = provider();
        CompletableFuture.runAsync(() -> emailService.sendComplaintResponse(view, provider));
        return view;
    }

    static LocalDate addBusinessDays(LocalDate from, int days) {
        LocalDate date = from;
        int added = 0;
        while (added < days) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) added++;
        }
        return date;
    }

    static ComplaintView view(Complaint c) {
        boolean overdue = c.getStatus() == Complaint.Status.PENDING
                && LocalDate.now(BusinessClock.DEFAULT_ZONE).isAfter(c.getDueDate());
        return new ComplaintView(c.getId(), c.getCode(), c.getType(), c.getConsumerName(), c.getDocumentType(),
                c.getDocumentNumber(), c.getAddress(), c.getPhone(), c.getEmail(), c.isMinor(), c.getGuardianName(),
                c.getItemType(), c.getItemDescription(), c.getAmount(), c.getDetail(), c.getConsumerRequest(),
                offset(c.getReceivedAt()), c.getDueDate(), overdue, c.getStatus(), c.getResponse(),
                offset(c.getRespondedAt()), c.getRespondedBy());
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atZone(BusinessClock.DEFAULT_ZONE).toOffsetDateTime();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.strip();
    }
}
