package com.fluxyBackend.service;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Value("${sendgrid.api.key:}")
    private String sendgridKey;

    @Value("${mail.from}")
    private String mailFrom;

    // ─── Método base para enviar emails ──────────────────────────────────────
    private void send(String toEmail, String toName, String subject, String html) {
        if (sendgridKey == null || sendgridKey.isBlank()) {
            log.warn("SendGrid no configurado. No se pudo enviar email a: {}", toEmail);
            return;
        }
        try {
            Email from    = new Email(mailFrom, "Fluxy");
            Email to      = new Email(toEmail, toName);
            Content content = new Content("text/html", html);
            Mail mail = new Mail(from, subject, to, content);

            SendGrid sg = new SendGrid(sendgridKey);
            Request req = new Request();
            req.setMethod(Method.POST);
            req.setEndpoint("mail/send");
            req.setBody(mail.build());
            sg.api(req);

            log.info("Email enviado a: {} — Asunto: {}", toEmail, subject);
        } catch (Exception e) {
            log.error("Error al enviar email a {}: {}", toEmail, e.getMessage());
        }
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

}