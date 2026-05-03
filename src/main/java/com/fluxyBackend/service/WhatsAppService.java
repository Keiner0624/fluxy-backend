package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);

    // ─── Genera el mensaje de WhatsApp para el vendedor ──────────────────────
    public String buildOrderMessage(Order order, Company company) {
        StringBuilder sb = new StringBuilder();
        sb.append("🛒 *Nuevo pedido en ").append(company.getName()).append("*\n\n");
        sb.append("📋 *Pedido #").append(order.getId()).append("*\n");
        sb.append("👤 Cliente: ").append(order.getCustomerName()).append("\n");

        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank()) {
            sb.append("📞 Teléfono: ").append(order.getCustomerPhone()).append("\n");
        }
        if (order.getCustomerAddress() != null && !order.getCustomerAddress().isBlank()) {
            sb.append("📍 Dirección: ").append(order.getCustomerAddress()).append("\n");
        }

        sb.append("\n*Productos:*\n");
        double total = 0;
        for (OrderItem item : order.getItems()) {
            double subtotal = item.getUnitPrice() * item.getQuantity();
            total += subtotal;
            sb.append("• ").append(item.getProdcut().getName())
                    .append(" x").append(item.getQuantity())
                    .append(" = S/ ").append(String.format("%.2f", subtotal)).append("\n");
        }

        sb.append("\n💰 *Total: S/ ").append(String.format("%.2f", total)).append("*\n");
        sb.append("\n_Enviado desde Fluxy_ 🚀");

        return sb.toString();
    }

    // ─── Genera el link de WhatsApp (wa.me) ──────────────────────────────────
    public String buildWhatsAppUrl(String phone, String message) {
        // Limpiar el número: solo dígitos
        String cleanPhone = phone.replaceAll("[^0-9]", "");

        // Si es número peruano de 9 dígitos, agregar código de país
        if (cleanPhone.length() == 9) {
            cleanPhone = "51" + cleanPhone;
        }

        try {
            String encoded = java.net.URLEncoder.encode(message, "UTF-8");
            return "https://wa.me/" + cleanPhone + "?text=" + encoded;
        } catch (Exception e) {
            return "https://wa.me/" + cleanPhone;
        }
    }

    // ─── Enviar notificación via CallMeBot API (WhatsApp gratuito) ───────────
    // Alternativa: simplemente loguear el link para que el vendedor lo use
    public void sendWhatsAppNotification(String phone, Order order, Company company) {
        if (phone == null || phone.isBlank()) {
            log.warn("No hay número de WhatsApp para la empresa {}", company.getId());
            return;
        }

        try {
            String message = buildOrderMessage(order, company);
            String whatsappUrl = buildWhatsAppUrl(phone, message);
            log.info("📱 WhatsApp link generado para pedido #{}: {}", order.getId(), whatsappUrl);

            // Guardar el link en el pedido o enviarlo por otra vía si se configura una API
            // Por ahora logueamos el link — en producción se puede integrar Twilio o CallMeBot
        } catch (Exception e) {
            log.error("Error generando WhatsApp para pedido #{}: {}", order.getId(), e.getMessage());
        }
    }
}