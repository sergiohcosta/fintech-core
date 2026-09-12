package com.fintech.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    // MAIL_USERNAME é a credencial SMTP ("resend", literal, no caso do Resend) — não é
    // necessariamente um email válido. Provider rejeita mensagem sem `from` bem formado
    // (550 no Resend). Default = sender de sandbox do Resend (sem domínio verificado, só
    // entrega pro próprio email da conta — ver spec de recuperação de senha).
    @Value("${app.mail.from:onboarding@resend.dev}")
    private String from;

    public void sendPasswordResetEmail(String to, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Recuperação de senha");
        message.setText("Clique no link para redefinir sua senha (válido por 1 hora):\n" + resetLink);
        mailSender.send(message);
    }
}
