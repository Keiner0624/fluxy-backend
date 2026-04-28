package com.fluxyBackend.service;

import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderItem;
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

    public void sendOrderNotification(String toEmail, String vendorName, Order order) {
        try {
            Email from = new Email(fromEmail, "Fluxy");
            Email to = new Email(toEmail);
            String subject = "🛒 Nuevo pedido de " + order.getCustomerName() + " — Fluxy";

            StringBuilder itemsHtml = new StringBuilder();
            double total = 0;
            for (OrderItem item : order.getItems()) {
                double subtotal = item.getUnitPrice() * item.getQuantity();
                total += subtotal;
                itemsHtml.append("""
                    <tr>
                        <td style="padding: 12px 16px; border-bottom: 1px solid #f0f0f0; color: #333;">
                            %s
                        </td>
                        <td style="padding: 12px 16px; border-bottom: 1px solid #f0f0f0; text-align: center; color: #666;">
                            %d
                        </td>
                        <td style="padding: 12px 16px; border-bottom: 1px solid #f0f0f0; text-align: right; color: #7c83fd; font-weight: bold;">
                            S/ %.2f
                        </td>
                    </tr>
                """.formatted(item.getProdcut().getName(), item.getQuantity(), subtotal));
            }

            String htmlContent = """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="margin:0; padding:0; background:#f4f4f8; font-family: 'Segoe UI', Arial, sans-serif;">
                    <div style="max-width: 600px; margin: 40px auto; background: white; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 24px rgba(0,0,0,0.08);">
                        
                        <!-- Header -->
                        <div style="background: linear-gradient(135deg, #7c83fd, #4f46e5); padding: 32px; text-align: center;">
                            <div style="font-size: 28px; font-weight: 900; color: white; letter-spacing: 3px; margin-bottom: 4px;">FLUXY</div>
                            <div style="color: rgba(255,255,255,0.8); font-size: 13px;">Sistema de gestión de tiendas</div>
                        </div>

                        <!-- Alerta nuevo pedido -->
                        <div style="background: #fef3c7; border-left: 4px solid #f59e0b; padding: 16px 32px; display: flex; align-items: center;">
                            <span style="font-size: 20px; margin-right: 10px;">🔔</span>
                            <div>
                                <div style="font-weight: 700; color: #92400e; font-size: 14px;">¡Nuevo pedido recibido!</div>
                                <div style="color: #b45309; font-size: 12px;">Un cliente acaba de realizar un pedido en tu tienda</div>
                            </div>
                        </div>

                        <!-- Contenido -->
                        <div style="padding: 32px;">
                            <p style="color: #374151; font-size: 16px; margin: 0 0 24px;">
                                Hola <strong style="color: #111;">%s</strong>, tienes un nuevo pedido esperando tu atención 👇
                            </p>

                            <!-- Info cliente -->
                            <div style="background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 12px; padding: 20px; margin-bottom: 24px;">
                                <div style="font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; color: #9ca3af; margin-bottom: 14px;">
                                    Información del cliente
                                </div>
                                <div style="display: flex; gap: 20px; flex-wrap: wrap;">
                                    <div>
                                        <div style="font-size: 12px; color: #9ca3af;">Nombre</div>
                                        <div style="font-size: 15px; font-weight: 600; color: #111;">👤 %s</div>
                                    </div>
                                    <div>
                                        <div style="font-size: 12px; color: #9ca3af;">Pedido</div>
                                        <div style="font-size: 15px; font-weight: 600; color: #7c83fd;">#%d</div>
                                    </div>
                                </div>
                            </div>

                            <!-- Productos -->
                            <div style="margin-bottom: 24px;">
                                <div style="font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; color: #9ca3af; margin-bottom: 12px;">
                                    Productos solicitados
                                </div>
                                <table style="width: 100%%; border-collapse: collapse; border: 1px solid #e5e7eb; border-radius: 10px; overflow: hidden;">
                                    <thead>
                                        <tr style="background: #f3f4f6;">
                                            <th style="padding: 10px 16px; text-align: left; font-size: 12px; color: #6b7280; font-weight: 600;">Producto</th>
                                            <th style="padding: 10px 16px; text-align: center; font-size: 12px; color: #6b7280; font-weight: 600;">Cantidad</th>
                                            <th style="padding: 10px 16px; text-align: right; font-size: 12px; color: #6b7280; font-weight: 600;">Subtotal</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        %s
                                    </tbody>
                                </table>
                            </div>

                            <!-- Total -->
                            <div style="background: linear-gradient(135deg, #ede9fe, #ddd6fe); border-radius: 12px; padding: 20px; display: flex; justify-content: space-between; align-items: center; margin-bottom: 28px;">
                                <span style="font-size: 15px; font-weight: 600; color: #4c1d95;">Total del pedido</span>
                                <span style="font-size: 26px; font-weight: 900; color: #4f46e5;">S/ %.2f</span>
                            </div>

                            <!-- CTA -->
                            <div style="text-align: center; margin-bottom: 24px;">
                                <div style="background: linear-gradient(135deg, #7c83fd, #4f46e5); display: inline-block; padding: 14px 32px; border-radius: 12px; color: white; font-weight: 700; font-size: 15px;">
                                    📋 Revisar pedido en tu dashboard
                                </div>
                            </div>

                            <p style="color: #9ca3af; font-size: 13px; text-align: center; margin: 0;">
                                Responde rápido — los clientes valoran la atención inmediata ⚡
                            </p>
                        </div>

                        <!-- Footer -->
                        <div style="background: #f9fafb; border-top: 1px solid #e5e7eb; padding: 20px 32px; text-align: center;">
                            <div style="font-size: 12px; color: #9ca3af;">
                                Enviado por <strong style="color: #7c83fd;">Fluxy</strong> · La plataforma para tu negocio
                            </div>
                        </div>
                    </div>
                </body>
                </html>
            """.formatted(vendorName, order.getCustomerName(), order.getId(), itemsHtml.toString(), total);

            Content content = new Content("text/html", htmlContent);
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