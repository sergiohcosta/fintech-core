package com.fintech.api.service;

import com.fintech.api.domain.passwordreset.PasswordResetToken;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.ForgotPasswordDTO;
import com.fintech.api.dto.ResetPasswordDTO;
import com.fintech.api.exception.BusinessException;
import com.fintech.api.repository.PasswordResetTokenRepository;
import com.fintech.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    // Sem `else`/exceção para email inexistente: a resposta ao cliente é sempre 200,
    // não há nada além de "não fazer nada" quando o usuário não existe ou está inativo
    // (anti-enumeração — mesma postura do /auth/login).
    @Transactional
    public void requestReset(ForgotPasswordDTO dto) {
        userRepository.findByEmail(dto.email())
                .filter(User::isEnabled)
                .ifPresent(this::createTokenAndSendEmail);
    }

    private void createTokenAndSendEmail(User user) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(LocalDateTime.now().plusHours(1));
        tokenRepository.save(token);

        String link = frontendUrl + "/reset-password?token=" + token.getToken();
        emailService.sendPasswordResetEmail(user.getEmail(), link);
    }

    @Transactional
    public void reset(ResetPasswordDTO dto) {
        PasswordResetToken token = tokenRepository.findByToken(dto.token())
                .orElseThrow(() -> new BusinessException("Token inválido"));
        if (token.isUsed() || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("Token inválido");
        }

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(dto.newPassword()));
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);

        token.setUsed(true);
        tokenRepository.save(token);
    }
}
