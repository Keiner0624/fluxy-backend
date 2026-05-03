package com.fluxyBackend.service;

import com.fluxyBackend.entity.PasswordResetToken;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.PasswordResetTokenRepository;
import com.fluxyBackend.repository.UserRepository;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${sendgrid.api.key:}")
    private String sendgridKey;

    @Value("${mail.from}")
    private String mailFrom;

    @Value("${app.frontend_url}")
    private String frontendUrl;

    //Solicitar recuperacion

    @Transactional
    public void requestReset(String email) {
        String normalizedEmail = email.trim().toLowerCase();

        userRepository.findByEmailIgnoreCase(normalizedEmail).ifPresent(user -> {
            //Elimina tokens anteriores del usuario
            tokenRepository.deleteByUser_Email(normalizedEmail);

            //Crear token nuevo
            String token = UUID.randomUUID().toString();
            PasswordResetToken resetToken = PasswordResetToken.builder().token(token).user(user).
                    expiresAt(LocalDateTime.now().plusHours(1)) // expira en una hora
                    .used(false).build();
            tokenRepository.save(resetToken);

            sendResetEmail(user, token);
        });
    }

    //Verificar token
    public boolean validateToken(String token) {
        return tokenRepository.findByToken(token)
                .map(t -> !t.isExpired() && !t.isUsed())
                .orElse(false);
    }

    //Resetear Contraseña
    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token).
                orElseThrow(() -> new RuntimeException("Token invalido o expirado"));
        if (resetToken.isExpired() || resetToken.isUsed()){
            throw new RuntimeException("El link ha expirado. Solicita uno nuevo.");
        }
        if (newPassword == null || newPassword.length() < 6){
            throw new RuntimeException("La contraseña debe tener al menos 6 caracteres.");
        }

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        resetToken.setUsed(true);
        tokenRepository.save(resetToken);

        log.info("Contraseña restablecida para el usuario: " + user.getEmail());
        return true;
    }

    private void sendResetEmail(User user, String token) {
        if (sendgridKey == null || sendgridKey.isBlank()) {
            log.warn("SendGrid no configurado. Token de reset: {}", token);
            return;
        }
        String resetLink = frontendUrl + "/reset-password?token=" + token;

        String html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0; padding:0; background:#f4f4f8; font-family:'Segoe UI', Arial, sans-serif;">
              <div style="max-width:560px; margin:40px auto; background:white; border-radius:16px; overflow:hidden; box-shadow:0 4px 24px rgba(0,0,0,0.08);">
                
                <!-- Header -->
                <div style="background:linear-gradient(135deg,#7c83fd,#4f46e5); padding:32px; text-align:center;">
                  <div style="font-size:28px; font-weight:900; color:white; letter-spacing:3px; margin-bottom:4px;">FLUXY</div>
                  <div style="color:rgba(255,255,255,0.8); font-size:13px;">Recuperación de contraseña</div>
                </div>
 
                <!-- Cuerpo -->
                <div style="padding:32px;">
                  <p style="color:#374151; font-size:16px; margin:0 0 16px;">
                    Hola <strong>%s</strong>, recibimos una solicitud para restablecer tu contraseña.
                  </p>
                  <p style="color:#6b7280; font-size:14px; margin:0 0 28px; line-height:1.6;">
                    Haz clic en el botón para crear una nueva contraseña. Este link es válido por <strong>1 hora</strong>.
                  </p>
 
                  <!-- Botón -->
                  <div style="text-align:center; margin-bottom:28px;">
                    <a href="%s" style="display:inline-block; background:linear-gradient(135deg,#7c83fd,#4f46e5); color:white; padding:14px 32px; border-radius:12px; font-weight:700; font-size:15px; text-decoration:none;">
                      🔐 Restablecer contraseña
                    </a>
                  </div>
 
                  <p style="color:#9ca3af; font-size:12px; text-align:center; margin:0;">
                    Si no solicitaste esto, ignora este correo. Tu contraseña no cambiará.
                  </p>
                </div>
 
                <!-- Footer -->
                <div style="background:#f9fafb; border-top:1px solid #e5e7eb; padding:20px 32px; text-align:center;">
                  <div style="font-size:12px; color:#9ca3af;">
                    Enviado por <strong style="color:#7c83fd;">Fluxy</strong>
                  </div>
                </div>
              </div>
            </body>
            </html>
        """.formatted(user.getFullName(), resetLink);

        try {
            Email from = new Email(mailFrom, "Fluxy");
            Email to = new Email(user.getEmail());
            Content content = new Content("text/html", html);
            Mail mail = new Mail(from, "\uD83D\uDD10 Restablece tu contraseña — Fluxy", to, content);

            SendGrid sg = new SendGrid(sendgridKey);
            Request req = new Request();
            req.setMethod(Method.POST);
            req.setEndpoint("mail/send");
            req.setBody(mail.build());
            sg.api(req);

            log.info("Email de restablecimiento enviado a: " + user.getEmail());
        } catch (Exception e) {
            log.error("Error al enviar email de restablecimiento de contraseña: " + e.getMessage(), e);
        }
    }
}
