package com.fluxyBackend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Correos de Fluxy por la API transaccional de Brevo (POST /v3/smtp/email).
 * Variables: BREVO_API_KEY y MAIL_FROM (un remitente o dominio verificado en Brevo).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${brevo.api_key:}")
    private String brevoKey;

    @Value("${brevo.base_url:https://api.brevo.com}")
    private String brevoUrl;

    @Value("${mail.from}")
    private String mailFrom;

    @Value("${mail.from_name:Fluxy}")
    private String mailFromName;

    /** Tras 5 fallos seguidos no se llama a Brevo por un minuto. */
    private final CircuitBreaker breaker = new CircuitBreaker("brevo", 5, Duration.ofMinutes(1));
    /** Tiempos límite explícitos: un proveedor lento no deja colgado el hilo que envía. */
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public boolean isConfigured() {
        return brevoKey != null && !brevoKey.isBlank();
    }

    /** Aviso al arrancar: sin Brevo no salen códigos de verificación ni avisos de pedidos. */
    @jakarta.annotation.PostConstruct
    void warnIfNotConfigured() {
        if (!isConfigured()) {
            log.error("BREVO_API_KEY no está configurado: no se envían correos (verificación, pedidos, planes)");
        } else {
            log.info("Correos por Brevo desde {} (debe ser un remitente o dominio verificado en Brevo)", mailFrom);
        }
    }

    // ─── Método base para enviar emails ──────────────────────────────────────
    /** Archivo adjunto (comprobantes electrónicos). */
    public record Attachment(String fileName, String contentType, byte[] content) {}

    /** Correo con adjuntos. @return true si Brevo lo aceptó. */
    public boolean sendWithAttachments(String toEmail, String toName, String subject, String html,
                                       List<Attachment> attachments) {
        return send(toEmail, toName, subject, html, attachments);
    }

    /** @return true si Brevo aceptó el correo. */
    private boolean send(String toEmail, String toName, String subject, String html) {
        return send(toEmail, toName, subject, html, List.of());
    }

    private boolean send(String toEmail, String toName, String subject, String html, List<Attachment> attachments) {
        if (!isConfigured()) {
            log.warn("Brevo no configurado. No se envió el correo \"{}\"", subject);
            return false;
        }
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Correo \"{}\" sin destinatario: no se envió", subject);
            return false;
        }
        if (!breaker.allowRequest()) {
            log.warn("Brevo en pausa por fallos recientes. No se envió el correo \"{}\"", subject);
            return false;
        }
        try {
            Map<String, Object> recipient = new LinkedHashMap<>();
            recipient.put("email", toEmail.strip());
            if (toName != null && !toName.isBlank()) recipient.put("name", toName.strip());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sender", Map.of("name", mailFromName, "email", mailFrom));
            body.put("to", List.of(recipient));
            body.put("subject", subject);
            body.put("htmlContent", html);
            if (!attachments.isEmpty()) {
                List<Map<String, String>> files = new ArrayList<>();
                for (Attachment a : attachments) {
                    files.add(Map.of("name", a.fileName(), "content", Base64.getEncoder().encodeToString(a.content())));
                }
                body.put("attachment", files);
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(brevoUrl.replaceAll("/+$", "") + "/v3/smtp/email"))
                    .timeout(Duration.ofSeconds(20))
                    .header("api-key", brevoKey.strip())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                breaker.recordSuccess();
                log.info("Correo enviado — Asunto: {} ({})", subject, messageId(response.body()));
                return true;
            }
            // 4xx es configuración (clave inválida, remitente sin verificar): no es una falla del proveedor.
            if (status >= 500) breaker.recordFailure();
            log.error("Brevo rechazó el correo \"{}\": HTTP {} {}", subject, status, truncate(response.body()));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            breaker.recordFailure();
            log.error("Error al enviar el correo \"{}\": {}", subject, e.getMessage());
            return false;
        }
    }

    private static String messageId(String body) {
        try {
            JsonNode node = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
            return node.path("messageId").asString("sin id");
        } catch (Exception e) {
            return "sin id";
        }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 400 ? value.substring(0, 400) + "…" : value;
    }

    // ─── Seguridad de la cuenta ──────────────────────────────────────────────

    /** Código de verificación. Un reintento: el envío es idempotente para quien lo recibe. */
    public boolean sendVerificationCode(String toEmail, String toName, String code, String purposeLabel, long minutes) {
        String html = securityLayout("Tu código de verificación",
                "Usá este código para " + escape(purposeLabel) + ". Vence en " + minutes + " minutos.",
                "<div style=\"margin:18px 0 6px; font-size:32px; font-weight:700; letter-spacing:8px; color:#0b172a;\">"
                        + escape(code) + "</div>",
                "Si no fuiste vos, ignorá este correo: sin el código nadie puede usar tu cuenta.");
        String subject = code + " es tu código de Fluxy";
        return send(toEmail, toName, subject, html) || send(toEmail, toName, subject, html);
    }

    public void sendNewDeviceLogin(String toEmail, String toName, String device, String ipPrefix, String when) {
        String html = securityLayout("Nuevo inicio de sesión",
                "Se inició sesión en tu cuenta de Fluxy desde un dispositivo nuevo.",
                "<table style=\"margin:14px 0; font-size:14px; color:#526078;\">"
                        + "<tr><td style=\"padding:3px 14px 3px 0;\">Dispositivo</td><td style=\"color:#0b172a;\">" + escape(device) + "</td></tr>"
                        + "<tr><td style=\"padding:3px 14px 3px 0;\">Red aproximada</td><td style=\"color:#0b172a;\">" + escape(ipPrefix) + "</td></tr>"
                        + "<tr><td style=\"padding:3px 14px 3px 0;\">Fecha</td><td style=\"color:#0b172a;\">" + escape(when) + "</td></tr></table>",
                "Si no fuiste vos, cambiá tu contraseña y cerrá las demás sesiones desde Seguridad en tu panel.");
        send(toEmail, toName, "Nuevo inicio de sesión en Fluxy", html);
    }

    public void sendSecurityNotice(String toEmail, String toName, String title, String text) {
        String html = securityLayout(title, text, "",
                "Si no reconocés este cambio, restablecé tu contraseña de inmediato y escribinos.");
        send(toEmail, toName, title + " — Fluxy", html);
    }

    public boolean sendPasswordReset(String toEmail, String toName, String link, long minutes) {
        String html = securityLayout("Restablecé tu contraseña",
                "Hola " + escape(toName) + ", recibimos un pedido para restablecer tu contraseña. El enlace sirve una sola vez y vence en "
                        + minutes + " minutos.",
                "<a href=\"" + escape(link) + "\" style=\"display:inline-block; margin-top:14px; background:#1769e0; color:#ffffff; padding:11px 20px; border-radius:10px; font-weight:600; font-size:14px; text-decoration:none;\">Crear nueva contraseña</a>",
                "Si no lo pediste, ignorá este correo: tu contraseña no cambia.");
        return send(toEmail, toName, "Restablecé tu contraseña — Fluxy", html);
    }

    public void sendSecurityAlert(String adminEmail, String signal, String message) {
        String html = securityLayout("Alerta: " + signal, message, "",
                "Revisá los logs con el X-Request-Id y el registro de auditoría.");
        send(adminEmail, "Administración Fluxy", "[Fluxy] Alerta de seguridad: " + signal, html);
    }

    public void sendLifecycleNotice(String toEmail, String toName, String companyName, String title, String text) {
        String html = securityLayout(title, text,
                "<a href=\"" + escape(frontendUrl) + "/dashboard\" style=\"display:inline-block; margin-top:14px; background:#1769e0; color:#ffffff; padding:11px 20px; border-radius:10px; font-weight:600; font-size:14px; text-decoration:none;\">Ir a mi panel</a>",
                "Recibís este aviso porque sos titular de " + escape(companyName) + " en Fluxy.");
        send(toEmail, toName, title + " — " + companyName, html);
    }

    public void sendOwnershipTransferRequest(String toEmail, String toName, String companyName, String fromName) {
        String html = securityLayout("Te proponen ser dueño de " + escape(companyName),
                escape(fromName) + " quiere transferirte la propiedad del negocio. Vas a tener acceso total, incluida la facturación.",
                "<a href=\"" + escape(frontendUrl) + "/dashboard/team\" style=\"display:inline-block; margin-top:14px; background:#1769e0; color:#ffffff; padding:11px 20px; border-radius:10px; font-weight:600; font-size:14px; text-decoration:none;\">Revisar y aceptar</a>",
                "La propuesta vence en 72 horas.");
        send(toEmail, toName, "Transferencia de propiedad de " + companyName, html);
    }

    // ─── Libro de Reclamaciones ──────────────────────────────────────────────

    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final java.time.format.DateTimeFormatter DAY_TIME = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Copia de la hoja para el consumidor: es su constancia. */
    public void sendComplaintCopy(ComplaintService.ComplaintView c, ComplaintService.ProviderInfo provider) {
        String html = securityLayout("Registramos tu " + kind(c) + " " + escape(c.code()),
                "Esta es la copia de tu hoja del Libro de Reclamaciones de Fluxy. Te responderemos a este correo a más tardar el "
                        + c.dueDate().format(DAY) + ".",
                complaintTable(c, provider),
                "Guardá este correo como constancia. Código: " + escape(c.code()) + ".");
        send(c.email(), c.consumerName(), "Hoja de reclamación " + c.code() + " — Fluxy", html);
    }

    public void sendComplaintAlert(String toEmail, ComplaintService.ComplaintView c) {
        String html = securityLayout("Nueva hoja " + escape(c.code()),
                "Se registró un " + kind(c) + ". Hay que responder a más tardar el " + c.dueDate().format(DAY) + ".",
                complaintTable(c, null)
                        + "<a href=\"" + escape(frontendUrl) + "/admin\" style=\"display:inline-block; margin-top:14px; background:#1769e0; color:#ffffff; padding:11px 20px; border-radius:10px; font-weight:600; font-size:14px; text-decoration:none;\">Responder desde administración</a>",
                "Aviso interno del Libro de Reclamaciones.");
        send(toEmail, "Atención al cliente Fluxy", "[Fluxy] Libro de Reclamaciones: " + c.code(), html);
    }

    public void sendComplaintResponse(ComplaintService.ComplaintView c, ComplaintService.ProviderInfo provider) {
        String html = securityLayout("Respuesta a tu hoja " + escape(c.code()),
                "Hola " + escape(c.consumerName()) + ", esta es la respuesta de " + escape(provider.name())
                        + " a tu hoja del Libro de Reclamaciones.",
                "<div style=\"margin:14px 0; padding:14px 16px; border-radius:10px; background:#f3f6fb; color:#0b172a; font-size:14px; line-height:1.6; white-space:pre-line;\">"
                        + escape(c.response()) + "</div>",
                "Si tenés dudas sobre esta respuesta, respondé a este correo e indicá el código " + escape(c.code()) + ".");
        send(c.email(), c.consumerName(), "Respuesta a tu hoja " + c.code() + " — Fluxy", html);
    }

    private static String kind(ComplaintService.ComplaintView c) {
        return c.type() == com.fluxyBackend.entity.Complaint.Type.RECLAMO ? "reclamo" : "queja";
    }

    private static String complaintTable(ComplaintService.ComplaintView c, ComplaintService.ProviderInfo provider) {
        StringBuilder rows = new StringBuilder();
        if (provider != null) {
            row(rows, "Proveedor", provider.name());
            row(rows, "RUC", provider.taxId() == null ? "En trámite" : provider.taxId());
            if (provider.address() != null) row(rows, "Domicilio del proveedor", provider.address());
        }
        row(rows, "Código", c.code());
        row(rows, "Fecha", c.receivedAt().format(DAY_TIME));
        row(rows, "Tipo", c.type() == com.fluxyBackend.entity.Complaint.Type.RECLAMO ? "Reclamo" : "Queja");
        row(rows, "Consumidor", c.consumerName());
        row(rows, "Documento", c.documentType() + " " + c.documentNumber());
        row(rows, "Domicilio", c.address());
        if (c.phone() != null) row(rows, "Teléfono", c.phone());
        row(rows, "Correo", c.email());
        if (c.minor()) row(rows, "Padre, madre o apoderado", c.guardianName());
        row(rows, c.itemType() == com.fluxyBackend.entity.Complaint.ItemType.PRODUCTO ? "Producto" : "Servicio", c.itemDescription());
        if (c.amount() != null) row(rows, "Monto reclamado", String.format(java.util.Locale.ROOT, "S/ %.2f", c.amount()));
        row(rows, "Detalle", c.detail());
        row(rows, "Pedido del consumidor", c.consumerRequest());
        return "<table style=\"margin:14px 0; font-size:14px; color:#526078; border-collapse:collapse;\">" + rows + "</table>";
    }

    private static void row(StringBuilder rows, String label, String value) {
        rows.append("<tr><td style=\"padding:4px 14px 4px 0; vertical-align:top; white-space:nowrap;\">").append(escape(label))
                .append("</td><td style=\"padding:4px 0; color:#0b172a; white-space:pre-line;\">").append(escape(value)).append("</td></tr>");
    }

    /** Aviso de facturación con acceso directo a Plan y facturación. */
    public void sendBillingNotice(String toEmail, String toName, String title, String text) {
        String html = securityLayout(title, escape(text),
                "<a href=\"" + escape(frontendUrl) + "/dashboard/plans\" style=\"display:inline-block; margin-top:14px; background:#1769e0; color:#ffffff; padding:11px 20px; border-radius:10px; font-weight:600; font-size:14px; text-decoration:none;\">Ver Plan y facturación</a>",
                "Recibís este aviso porque sos titular del negocio en Fluxy.");
        send(toEmail, toName, title + " — Fluxy", html);
    }

    @Value("${app.frontend_url:http://localhost:5173}")
    private String frontendUrl;

    /** Cuerpo del correo de un comprobante electrónico que el negocio le manda a su cliente. */
    public static String documentHtml(String issuer, String typeLabel, String number, String total, String link, boolean test) {
        String button = link == null ? "" : "<a href=\"" + escape(link) + "\" style=\"display:inline-block; margin-top:14px; background:#1769e0; color:#ffffff; padding:11px 20px; border-radius:10px; font-weight:600; font-size:14px; text-decoration:none;\">Ver comprobante</a>";
        String text = escape(issuer) + " te envía tu " + escape(typeLabel.toLowerCase()) + " <b>" + escape(number) + "</b> por " + escape(total)
                + ". Adjuntamos el PDF; también podés verlo en línea."
                + (test ? "<br><br><b>Documento de prueba: no tiene valor tributario.</b>" : "");
        return securityLayout(typeLabel + " " + number, text, button,
                "Recibís este correo porque hiciste una compra en " + escape(issuer) + ". Fluxy lo envía en su nombre.");
    }

    private static String securityLayout(String title, String text, String extraHtml, String footer) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0; padding:0; background:#f7f9fc; font-family:'Segoe UI', Arial, sans-serif;">
              <div style="max-width:520px; margin:40px auto; background:#ffffff; border:1px solid #e5eaf1; border-radius:14px; overflow:hidden;">
                <div style="padding:24px 32px; border-bottom:1px solid #e5eaf1;">
                  <div style="font-size:18px; font-weight:700; color:#0b172a;">Fluxy</div>
                </div>
                <div style="padding:26px 32px;">
                  <p style="color:#0b172a; font-size:17px; font-weight:600; margin:0 0 10px;">%s</p>
                  <p style="color:#526078; font-size:14px; line-height:1.6; margin:0;">%s</p>
                  %s
                  <p style="color:#7d8ba1; font-size:12px; line-height:1.6; margin:22px 0 0;">%s</p>
                </div>
              </div>
            </body>
            </html>
        """.formatted(escape(title), text, extraHtml, footer);
    }

    // ─── Email de confirmación de pago y activación de plan ──────────────────
    public void sendPlanActivatedEmail(String toEmail, String toName, String planName, LocalDateTime expiresAt) {
        String planEmoji  = planName.equalsIgnoreCase("BUSINESS") ? "🚀" : "⚡";
        String planColor  = planName.equalsIgnoreCase("BUSINESS") ? "#34d399" : "#7c83fd";
        String planLabel  = planName.equalsIgnoreCase("BUSINESS") ? "Business" : "Pro";
        String expiraStr  = expiresAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        String beneficios = planName.equalsIgnoreCase("BUSINESS") ? """
            <li style="margin-bottom:8px;">♾️ Productos ilimitados</li>
            <li style="margin-bottom:8px;">💬 WhatsApp automático al recibir pedidos</li>
            <li style="margin-bottom:8px;">📊 Estadísticas y métricas completas</li>
            <li style="margin-bottom:8px;">🌐 Dominio personalizado para tu tienda</li>
            <li style="margin-bottom:8px;">🏷️ Sin branding de Fluxy en tu tienda</li>
            <li style="margin-bottom:8px;">🚀 Soporte prioritario 24/7</li>
        """ : """
            <li style="margin-bottom:8px;">📦 Hasta 100 productos en tu tienda</li>
            <li style="margin-bottom:8px;">💬 WhatsApp automático al recibir pedidos</li>
            <li style="margin-bottom:8px;">📊 Estadísticas y métricas completas</li>
            <li style="margin-bottom:8px;">🎨 Personalización avanzada de estilos</li>
            <li style="margin-bottom:8px;">⚡ Soporte por correo electrónico</li>
        """;

        String html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0; padding:0; background:#f4f4f8; font-family:'Segoe UI', Arial, sans-serif;">
              <div style="max-width:560px; margin:40px auto; background:white; border-radius:16px; overflow:hidden; box-shadow:0 4px 24px rgba(0,0,0,0.08);">
            
                <!-- Header -->
                <div style="background:linear-gradient(135deg,%s,%s); padding:36px 32px; text-align:center;">
                  <div style="font-size:48px; margin-bottom:12px;">%s</div>
                  <div style="font-size:28px; font-weight:900; color:white; letter-spacing:3px; margin-bottom:6px;">FLUXY</div>
                  <div style="color:rgba(255,255,255,0.85); font-size:15px; font-weight:600;">Plan %s activado exitosamente</div>
                </div>
            
                <!-- Cuerpo -->
                <div style="padding:36px 32px;">
                  <p style="color:#374151; font-size:17px; margin:0 0 8px; font-weight:700;">
                    ¡Hola, %s! 🎉
                  </p>
                  <p style="color:#6b7280; font-size:14px; margin:0 0 24px; line-height:1.7;">
                    Tu pago fue procesado correctamente y tu plan <strong style="color:%s;">%s %s</strong> ya está activo en tu cuenta de Fluxy.
                  </p>
            
                  <!-- Info del plan -->
                  <div style="background:#f9fafb; border:2px solid %s; border-radius:12px; padding:20px 24px; margin-bottom:24px;">
                    <div style="font-size:13px; color:#6b7280; text-transform:uppercase; letter-spacing:1px; margin-bottom:12px; font-weight:700;">Detalles de tu plan</div>
                    <div style="display:flex; justify-content:space-between; margin-bottom:8px;">
                      <span style="color:#374151; font-size:14px;">Plan activo:</span>
                      <span style="color:%s; font-weight:700; font-size:14px;">%s %s</span>
                    </div>
                    <div style="display:flex; justify-content:space-between;">
                      <span style="color:#374151; font-size:14px;">Válido hasta:</span>
                      <span style="color:#374151; font-weight:700; font-size:14px;">%s</span>
                    </div>
                  </div>
            
                  <!-- Beneficios -->
                  <p style="color:#374151; font-size:14px; font-weight:700; margin:0 0 12px;">Lo que tienes disponible:</p>
                  <ul style="color:#6b7280; font-size:14px; line-height:1.7; padding-left:20px; margin:0 0 28px;">
                    %s
                  </ul>
            
                  <!-- CTA -->
                  <div style="text-align:center; margin-bottom:24px;">
                    <a href="https://fluxyweb.com/dashboard" style="display:inline-block; background:linear-gradient(135deg,%s,%s); color:white; padding:14px 32px; border-radius:12px; font-weight:700; font-size:15px; text-decoration:none;">
                      Ir a mi panel →
                    </a>
                  </div>
            
                  <p style="color:#9ca3af; font-size:12px; text-align:center; margin:0; line-height:1.6;">
                    Te recordaremos 3 días antes de que venza tu plan para que puedas renovarlo.<br/>
                    Si tienes alguna duda escríbenos a <a href="mailto:notificaciones@fluxyweb.com" style="color:#7c83fd;">notificaciones@fluxyweb.com</a>
                  </p>
                </div>
            
                <!-- Footer -->
                <div style="background:#f9fafb; border-top:1px solid #e5e7eb; padding:20px 32px; text-align:center;">
                  <div style="font-size:12px; color:#9ca3af;">
                    © %d <strong style="color:#7c83fd;">Fluxy</strong> — Plataforma de tiendas online para negocios peruanos 🇵🇪
                  </div>
                </div>
              </div>
            </body>
            </html>
        """.formatted(
                planColor, planName.equalsIgnoreCase("BUSINESS") ? "#059669" : "#4f46e5",
                planEmoji,
                planLabel,
                toName,
                planColor, planEmoji, planLabel,
                planColor,
                planColor, planEmoji, planLabel,
                expiraStr,
                beneficios,
                planColor, planName.equalsIgnoreCase("BUSINESS") ? "#059669" : "#4f46e5",
                LocalDateTime.now().getYear()
        );

        send(toEmail, toName, planEmoji + " Plan " + planLabel + " activado — Fluxy", html);
    }

    // ─── Email de aviso de vencimiento próximo ────────────────────────────────
    public void sendPlanExpiringEmail(String toEmail, String toName, String planName, int daysLeft) {
        String planLabel = planName.equalsIgnoreCase("BUSINESS") ? "Business" : "Pro";
        String html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0; padding:0; background:#f4f4f8; font-family:'Segoe UI', Arial, sans-serif;">
              <div style="max-width:560px; margin:40px auto; background:white; border-radius:16px; overflow:hidden; box-shadow:0 4px 24px rgba(0,0,0,0.08);">
                <div style="background:linear-gradient(135deg,#f59e0b,#d97706); padding:32px; text-align:center;">
                  <div style="font-size:40px; margin-bottom:10px;">⏰</div>
                  <div style="font-size:24px; font-weight:900; color:white; letter-spacing:3px; margin-bottom:4px;">FLUXY</div>
                  <div style="color:rgba(255,255,255,0.9); font-size:14px;">Tu plan está por vencer</div>
                </div>
                <div style="padding:32px;">
                  <p style="color:#374151; font-size:16px; margin:0 0 16px;">
                    Hola <strong>%s</strong>, tu plan <strong>%s</strong> vence en <strong style="color:#f59e0b;">%d días</strong>.
                  </p>
                  <p style="color:#6b7280; font-size:14px; margin:0 0 24px; line-height:1.7;">
                    Para no perder acceso a tus funciones, renueva tu plan antes de que expire.
                  </p>
                  <div style="text-align:center; margin-bottom:24px;">
                    <a href="https://fluxyweb.com/dashboard/plans" style="display:inline-block; background:linear-gradient(135deg,#f59e0b,#d97706); color:white; padding:14px 32px; border-radius:12px; font-weight:700; font-size:15px; text-decoration:none;">
                      Renovar mi plan →
                    </a>
                  </div>
                  <p style="color:#9ca3af; font-size:12px; text-align:center; margin:0;">
                    Si ya renovaste, ignora este mensaje.
                  </p>
                </div>
                <div style="background:#f9fafb; border-top:1px solid #e5e7eb; padding:20px 32px; text-align:center;">
                  <div style="font-size:12px; color:#9ca3af;">© %d <strong style="color:#7c83fd;">Fluxy</strong></div>
                </div>
              </div>
            </body>
            </html>
        """.formatted(toName, planLabel, daysLeft, LocalDateTime.now().getYear());

        send(toEmail, toName, "⏰ Tu plan " + planLabel + " vence en " + daysLeft + " días — Fluxy", html);
    }

    // ─── Email de notificación de nuevo pedido al vendedor ────────────────────
    public void sendOrderNotification(String toEmail, String toName, com.fluxyBackend.entity.Order order) {
        if (order == null) return;

        StringBuilder itemsHtml = new StringBuilder();
        if (order.getItems() != null) {
            for (var item : order.getItems()) {
                itemsHtml.append("""
                    <tr>
                      <td style="padding:8px 0; border-bottom:1px solid #f3f4f6; color:#374151; font-size:14px;">%s</td>
                      <td style="padding:8px 0; border-bottom:1px solid #f3f4f6; color:#374151; font-size:14px; text-align:center;">x%d</td>
                      <td style="padding:8px 0; border-bottom:1px solid #f3f4f6; color:#374151; font-size:14px; text-align:right;">S/ %.2f</td>
                    </tr>
                """.formatted(
                        item.getProdcut() != null ? item.getProdcut().getName() : "Producto",
                        item.getQuantity(),
                        item.getSubTotal()
                ));
            }
        }

        String customerInfo = order.getCustomerName() != null ? order.getCustomerName() : "Cliente";
        String customerPhone = order.getCustomerPhone() != null
                ? "<div style=\"margin-bottom:6px;\"><span style=\"color:#6b7280;\">Teléfono:</span> <strong>" + order.getCustomerPhone() + "</strong></div>" : "";
        String customerAddress = order.getCustomerAddress() != null
                ? "<div><span style=\"color:#6b7280;\">Dirección:</span> <strong>" + order.getCustomerAddress() + "</strong></div>" : "";

        String html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0; padding:0; background:#f4f4f8; font-family:'Segoe UI', Arial, sans-serif;">
              <div style="max-width:560px; margin:40px auto; background:white; border-radius:16px; overflow:hidden; box-shadow:0 4px 24px rgba(0,0,0,0.08);">

                <!-- Header -->
                <div style="background:linear-gradient(135deg,#7c83fd,#4f46e5); padding:28px 32px; text-align:center;">
                  <div style="font-size:36px; margin-bottom:8px;">🛒</div>
                  <div style="font-size:24px; font-weight:900; color:white; letter-spacing:3px; margin-bottom:4px;">FLUXY</div>
                  <div style="color:rgba(255,255,255,0.85); font-size:14px;">¡Tienes un nuevo pedido!</div>
                </div>

                <!-- Cuerpo -->
                <div style="padding:32px;">
                  <p style="color:#374151; font-size:16px; margin:0 0 20px;">
                    Hola <strong>%s</strong>, recibiste un nuevo pedido 🎉
                  </p>

                  <!-- Info del cliente -->
                  <div style="background:#f9fafb; border-radius:12px; padding:16px 20px; margin-bottom:20px; border-left:4px solid #7c83fd;">
                    <div style="font-size:12px; color:#6b7280; text-transform:uppercase; letter-spacing:1px; margin-bottom:10px; font-weight:700;">Cliente</div>
                    <div style="margin-bottom:6px; font-size:14px; color:#374151;"><strong>%s</strong></div>
                    %s
                    %s
                  </div>

                  <!-- Productos -->
                  <div style="margin-bottom:20px;">
                    <div style="font-size:12px; color:#6b7280; text-transform:uppercase; letter-spacing:1px; margin-bottom:12px; font-weight:700;">Productos</div>
                    <table style="width:100%%; border-collapse:collapse;">
                      <thead>
                        <tr>
                          <th style="text-align:left; padding:8px 0; border-bottom:2px solid #e5e7eb; color:#374151; font-size:12px; font-weight:700;">Producto</th>
                          <th style="text-align:center; padding:8px 0; border-bottom:2px solid #e5e7eb; color:#374151; font-size:12px; font-weight:700;">Cant.</th>
                          <th style="text-align:right; padding:8px 0; border-bottom:2px solid #e5e7eb; color:#374151; font-size:12px; font-weight:700;">Subtotal</th>
                        </tr>
                      </thead>
                      <tbody>%s</tbody>
                    </table>
                  </div>

                  <!-- Total -->
                  <div style="background:linear-gradient(135deg,rgba(124,131,253,0.08),rgba(79,70,229,0.05)); border:1px solid rgba(124,131,253,0.2); border-radius:12px; padding:16px 20px; display:flex; justify-content:space-between; align-items:center; margin-bottom:24px;">
                    <span style="font-size:15px; font-weight:700; color:#374151;">Total del pedido</span>
                    <span style="font-size:22px; font-weight:900; color:#7c83fd;">S/ %.2f</span>
                  </div>

                  <!-- CTA -->
                  <div style="text-align:center;">
                    <a href="https://fluxyweb.com/dashboard/orders" style="display:inline-block; background:linear-gradient(135deg,#7c83fd,#4f46e5); color:white; padding:13px 28px; border-radius:12px; font-weight:700; font-size:14px; text-decoration:none;">
                      Ver pedido en el panel →
                    </a>
                  </div>
                </div>

                <!-- Footer -->
                <div style="background:#f9fafb; border-top:1px solid #e5e7eb; padding:16px 32px; text-align:center;">
                  <div style="font-size:12px; color:#9ca3af;">
                    Enviado por <strong style="color:#7c83fd;">Fluxy</strong> — Tu plataforma de tiendas online 🇵🇪
                  </div>
                </div>
              </div>
            </body>
            </html>
        """.formatted(
                toName,
                customerInfo,
                customerPhone,
                customerAddress,
                itemsHtml.toString(),
                order.getTotal() != null ? order.getTotal() : 0.0
        );

        send(toEmail, toName, "🛒 Nuevo pedido recibido — Fluxy", html);
    }


    // ─── Email de plan vencido ────────────────────────────────────────────────
    public void sendPlanExpiredEmail(String toEmail, String toName, String planName) {
        String planLabel = planName.equalsIgnoreCase("BUSINESS") ? "Business" : "Pro";
        String html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0; padding:0; background:#f4f4f8; font-family:'Segoe UI', Arial, sans-serif;">
              <div style="max-width:560px; margin:40px auto; background:white; border-radius:16px; overflow:hidden; box-shadow:0 4px 24px rgba(0,0,0,0.08);">
                <div style="background:linear-gradient(135deg,#f87171,#ef4444); padding:32px; text-align:center;">
                  <div style="font-size:40px; margin-bottom:10px;">😔</div>
                  <div style="font-size:24px; font-weight:900; color:white; letter-spacing:3px; margin-bottom:4px;">FLUXY</div>
                  <div style="color:rgba(255,255,255,0.9); font-size:14px;">Tu plan ha vencido</div>
                </div>
                <div style="padding:32px;">
                  <p style="color:#374151; font-size:16px; margin:0 0 16px;">
                    Hola <strong>%s</strong>, tu plan <strong>%s</strong> ha vencido y tu cuenta volvió al plan <strong>Free</strong>.
                  </p>
                  <p style="color:#6b7280; font-size:14px; margin:0 0 24px; line-height:1.7;">
                    No te preocupes — tu tienda y productos siguen activos. Solo perdiste acceso a las funciones premium hasta que renueves.
                  </p>
                  <div style="background:#fff7ed; border:1px solid #fed7aa; border-radius:12px; padding:16px 20px; margin-bottom:24px;">
                    <div style="font-size:13px; color:#9a3412; font-weight:700; margin-bottom:8px;">¿Qué perdiste con el plan Free?</div>
                    <ul style="color:#9a3412; font-size:13px; margin:0; padding-left:20px; line-height:1.8;">
                      <li>WhatsApp automático al recibir pedidos</li>
                      <li>Estadísticas y métricas</li>
                      <li>Más de 10 productos visibles</li>
                    </ul>
                  </div>
                  <div style="text-align:center; margin-bottom:20px;">
                    <a href="https://fluxyweb.com/dashboard/plans" style="display:inline-block; background:linear-gradient(135deg,#7c83fd,#4f46e5); color:white; padding:14px 32px; border-radius:12px; font-weight:700; font-size:15px; text-decoration:none;">
                      Renovar mi plan →
                    </a>
                  </div>
                  <p style="color:#9ca3af; font-size:12px; text-align:center; margin:0;">
                    ¿Tienes alguna duda? Escríbenos a <a href="mailto:notificaciones@fluxyweb.com" style="color:#7c83fd;">notificaciones@fluxyweb.com</a>
                  </p>
                </div>
                <div style="background:#f9fafb; border-top:1px solid #e5e7eb; padding:16px 32px; text-align:center;">
                  <div style="font-size:12px; color:#9ca3af;">© %d <strong style="color:#7c83fd;">Fluxy</strong></div>
                </div>
              </div>
            </body>
            </html>
        """.formatted(toName, planLabel, java.time.LocalDateTime.now().getYear());

        send(toEmail, toName, "Tu plan " + planLabel + " ha vencido — Fluxy", html);
    }


    // ─── Invitación al equipo ─────────────────────────────────────────────────
    /** Devuelve false si el correo no está configurado: el panel ofrece copiar el enlace. */
    public boolean sendTeamInvitationEmail(String toEmail, String companyName, String inviterName,
                                           String roleLabel, String acceptUrl) {
        if (!isConfigured()) {
            log.warn("Brevo no configurado. La invitación a {} se comparte con el enlace.", toEmail);
            return false;
        }
        String html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0; padding:0; background:#f7f9fc; font-family:'Segoe UI', Arial, sans-serif;">
              <div style="max-width:520px; margin:40px auto; background:#ffffff; border:1px solid #e5eaf1; border-radius:14px; overflow:hidden;">
                <div style="padding:28px 32px; border-bottom:1px solid #e5eaf1;">
                  <div style="font-size:18px; font-weight:700; color:#0b172a;">Fluxy</div>
                </div>
                <div style="padding:28px 32px;">
                  <p style="color:#0b172a; font-size:16px; font-weight:600; margin:0 0 12px;">Te invitaron a %s</p>
                  <p style="color:#526078; font-size:14px; line-height:1.6; margin:0 0 22px;">
                    %s te sumó al equipo con el rol <strong style="color:#0b172a;">%s</strong>.
                    Creá tu contraseña para entrar al panel. El enlace vence en 7 días.
                  </p>
                  <a href="%s" style="display:inline-block; background:#1769e0; color:#ffffff; padding:12px 22px; border-radius:10px; font-weight:600; font-size:14px; text-decoration:none;">Aceptar invitación</a>
                  <p style="color:#7d8ba1; font-size:12px; line-height:1.6; margin:22px 0 0;">Si no esperabas esta invitación, ignorá este correo.</p>
                </div>
              </div>
            </body>
            </html>
        """.formatted(escape(companyName), escape(inviterName), escape(roleLabel), acceptUrl);
        return send(toEmail, toEmail, "Te invitaron al equipo de " + companyName + " en Fluxy", html);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ─── Email de trial activado ──────────────────────────────────────────────
    public void sendTrialActivatedEmail(String toEmail, String toName, java.time.LocalDateTime expiresAt) {
        String expiraStr = expiresAt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0; padding:0; background:#f4f4f8; font-family:'Segoe UI', Arial, sans-serif;">
              <div style="max-width:560px; margin:40px auto; background:white; border-radius:16px; overflow:hidden; box-shadow:0 4px 24px rgba(0,0,0,0.08);">
                <div style="background:linear-gradient(135deg,#7c83fd,#4f46e5); padding:36px 32px; text-align:center;">
                  <div style="font-size:48px; margin-bottom:12px;">🎉</div>
                  <div style="font-size:26px; font-weight:900; color:white; letter-spacing:3px; margin-bottom:6px;">FLUXY</div>
                  <div style="color:rgba(255,255,255,0.85); font-size:15px; font-weight:600;">¡Tu prueba gratuita está activa!</div>
                </div>
                <div style="padding:36px 32px;">
                  <p style="color:#374151; font-size:17px; margin:0 0 16px; font-weight:700;">¡Hola, %s! 🚀</p>
                  <p style="color:#6b7280; font-size:14px; margin:0 0 24px; line-height:1.7;">
                    Tu prueba gratuita del <strong style="color:#7c83fd;">Plan Pro</strong> está activa por <strong>1 mes completo</strong>. Disfruta todas las funciones premium sin costo.
                  </p>
                  <div style="background:#f9fafb; border:2px solid #7c83fd; border-radius:12px; padding:20px 24px; margin-bottom:24px;">
                    <div style="font-size:12px; color:#6b7280; text-transform:uppercase; letter-spacing:1px; margin-bottom:12px; font-weight:700;">Lo que tienes disponible</div>
                    <ul style="color:#374151; font-size:14px; line-height:2; padding-left:20px; margin:0;">
                      <li>📦 Hasta 100 productos</li>
                      <li>💬 WhatsApp automático al recibir pedidos</li>
                      <li>📊 Estadísticas completas de ventas</li>
                      <li>🎨 Personalización avanzada de tu tienda</li>
                    </ul>
                  </div>
                  <div style="background:#fff7ed; border:1px solid #fed7aa; border-radius:12px; padding:14px 18px; margin-bottom:24px;">
                    <div style="font-size:13px; color:#9a3412;">
                       Tu prueba vence el <strong>%s</strong>. Después volverás al plan Free si no renuevas.
                    </div>
                  </div>
                  <div style="text-align:center;">
                    <a href="https://fluxyweb.com/dashboard" style="display:inline-block; background:linear-gradient(135deg,#7c83fd,#4f46e5); color:white; padding:14px 32px; border-radius:12px; font-weight:700; font-size:15px; text-decoration:none;">
                      Ir a mi panel →
                    </a>
                  </div>
                </div>
                <div style="background:#f9fafb; border-top:1px solid #e5e7eb; padding:16px 32px; text-align:center;">
                  <div style="font-size:12px; color:#9ca3af;">© %d <strong style="color:#7c83fd;">Fluxy</strong></div>
                </div>
              </div>
            </body>
            </html>
        """.formatted(toName, expiraStr, java.time.LocalDateTime.now().getYear());

        send(toEmail, toName, "🎉 ¡Tu prueba gratuita de Fluxy Pro está activa!", html);
    }

}