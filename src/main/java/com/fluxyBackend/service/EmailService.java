package com.fluxyBackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;
    public void sendOrderNotification(String toEmail, String vendorName, String customerName, double total, Long orderId){
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject("Nuevo Pedido " + orderId + " en Fluxy");
        message.setText("Hola " + vendorName + ",\n\n" +
                "¡Tienes un nuevo pedido!\n\n" +
                "📦 Pedido #" + orderId + "\n" +
                "👤 Cliente: " + customerName + "\n" +
                "💰 Total: S/ " + String.format("%.2f", total) + "\n\n" +
                "Ingresa a tu dashboard para verlo y procesarlo.\n\n" +
                "— El equipo de Fluxy");
        mailSender.send(message);
    }
}
