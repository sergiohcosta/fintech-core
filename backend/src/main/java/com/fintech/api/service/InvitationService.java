package com.fintech.api.service;

import com.fintech.api.config.TokenService;
import com.fintech.api.domain.invitation.Invitation;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.CreateInvitationDTO;
import com.fintech.api.dto.InvitationResponseDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.repository.InvitationRepository;
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
public class InvitationService {

    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    @Transactional
    public InvitationResponseDTO create(CreateInvitationDTO dto, User admin) {
        if (userRepository.existsByEmail(dto.email())) {
            throw new BusinessConflictException("Este email já possui uma conta");
        }
        if (invitationRepository.existsByEmailAndTenantIdAndUsedFalseAndExpiresAtAfter(
                dto.email(), admin.getTenant().getId(), LocalDateTime.now())) {
            throw new BusinessConflictException("Já existe um convite pendente para este email");
        }

        Invitation invitation = new Invitation();
        invitation.setTenant(admin.getTenant());
        invitation.setEmail(dto.email());
        invitation.setToken(UUID.randomUUID().toString());
        invitation.setExpiresAt(LocalDateTime.now().plusDays(7));
        invitationRepository.save(invitation);

        String link = frontendUrl + "/accept-invite?token=" + invitation.getToken();
        return new InvitationResponseDTO(invitation.getToken(), link, invitation.getEmail(), invitation.getExpiresAt());
    }

    // validate() e accept() serão adicionados na Tarefa 4
}
