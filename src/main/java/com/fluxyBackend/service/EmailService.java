package com.fluxyBackend.service;

import com.sendgrid.*;
import com.sendgrid.helpers.mail.*;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Value("${sendgrid.api.key}")
    private String apiKey;

    @Value("${mail.from}")
    private String fromEmail;

    public void sendOrderNotification(String toEmail, String vendorName,
                                      String customerName, double total, Long orderId) {
        try {
            Email from = new Email(fromEmail, "Fluxy");
            Email to = new Email(toEmail);
            String subject = "🛒 Nuevo pedido #" + orderId + " en Fluxy";

            Content content = new Content("text/plain",
                    "Hola " + vendorName + ",\n\n" +
                            "¡Tienes un nuevo pedido!\n\n" +
                            "📦 Pedido #" + orderId + "\n" +
                            "👤 Cliente: " + customerName + "\n" +
                            "💰 Total: S/ " + String.format("%.2f", total) + "\n\n" +
                            "Ingresa a tu dashboard para verlo.\n\n" +
                            "— El equipo de Fluxy"
            );

            Mail mail = new Mail(from, subject, to, content);
            SendGrid sg = new SendGrid(apiKey);
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            sg.api(request);

            System.out.println("Email enviado a: " + toEmail);
        } catch (Exception e) {
            System.out.println("Error enviando email: " + e.getMessage());
        }
    }
}