package com.fintech.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    public void sendPasswordResetEmail(String to, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Recuperação de senha");
        message.setText("Clique no link para redefinir sua senha (válido por 1 hora):\n" + resetLink);
        mailSender.send(message);
    }
}
