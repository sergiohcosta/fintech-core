package com.fintech.api.service;

import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.InvitationStatus;
import com.fintech.api.domain.invitation.Invitation;
import com.fintech.api.domain.user.User;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.dto.AcceptInviteDTO;
import com.fintech.api.dto.CreateInvitationDTO;
import com.fintech.api.dto.InvitationInfoDTO;
import com.fintech.api.dto.InvitationResponseDTO;
import com.fintech.api.dto.InvitationSummaryDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.exception.InviteAlreadyUsedException;
import com.fintech.api.exception.InviteExpiredException;
import com.fintech.api.repository.InvitationRepository;
import com.fintech.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
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

    @Transactional(readOnly = true)
    public InvitationInfoDTO validate(String token) {
        Invitation invitation = findValidInvitation(token);
        return new InvitationInfoDTO(invitation.getEmail(), invitation.getTenant().getName());
    }

    @Transactional
    public String accept(AcceptInviteDTO dto) {
        Invitation invitation = findValidInvitation(dto.token());

        if (userRepository.existsByEmail(invitation.getEmail())) {
            throw new BusinessConflictException("Este email já possui uma conta");
        }

        User user = new User();
        user.setName(dto.name());
        user.setEmail(invitation.getEmail());
        user.setPasswordHash(passwordEncoder.encode(dto.password()));
        user.setRole(UserRole.USER);
        user.setTenant(invitation.getTenant());
        userRepository.save(user);

        invitation.setUsed(true);
        invitationRepository.save(invitation);

        return tokenService.generateToken(user);
    }

    @Transactional(readOnly = true)
    public List<InvitationSummaryDTO> list(User admin) {
        return invitationRepository
                .findAllByTenantIdOrderByCreatedAtDesc(admin.getTenant().getId())
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public void revoke(UUID id, User admin) {
        Invitation invitation = invitationRepository.findById(id)
                // Filtro dentro da transação ativa — getTenant() é lazy, só pode ser acessado com session aberta
                .filter(inv -> inv.getTenant().getId().equals(admin.getTenant().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Convite não encontrado"));
        if (invitation.isUsed()) {
            throw new BusinessConflictException("Convite já aceito não pode ser revogado");
        }
        invitationRepository.delete(invitation);
    }

    private InvitationSummaryDTO toSummary(Invitation inv) {
        InvitationStatus status;
        if (inv.isUsed()) {
            status = InvitationStatus.ACCEPTED;
        } else if (inv.getExpiresAt().isBefore(LocalDateTime.now())) {
            status = InvitationStatus.EXPIRED;
        } else {
            status = InvitationStatus.PENDING;
        }
        String link = status == InvitationStatus.PENDING
                ? frontendUrl + "/accept-invite?token=" + inv.getToken()
                : null;
        return new InvitationSummaryDTO(
                inv.getId(), inv.getEmail(), status,
                inv.getCreatedAt(), inv.getExpiresAt(), link);
    }

    private Invitation findValidInvitation(String token) {
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new EntityNotFoundException("Convite inválido ou inexistente"));
        if (invitation.isUsed()) throw new InviteAlreadyUsedException();
        if (invitation.getExpiresAt().isBefore(LocalDateTime.now())) throw new InviteExpiredException();
        return invitation;
    }
}
