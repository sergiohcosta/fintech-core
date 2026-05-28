package com.fintech.api.service;

import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.invitation.Invitation;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.CreateInvitationDTO;
import com.fintech.api.dto.InvitationResponseDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.repository.InvitationRepository;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    @Mock InvitationRepository invitationRepository;
    @Mock UserRepository userRepository;
    @Mock TokenService tokenService;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks InvitationService service;

    private User admin;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Família Silva");

        admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setEmail("admin@silva.com");
        admin.setRole(UserRole.ADMIN);
        admin.setTenant(tenant);

        // @Value não é processado pelo Mockito — inicializa manualmente via ReflectionTestUtils
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:4200");
    }

    // --- CRIAR CONVITE ---

    @Test
    @DisplayName("Cria convite e retorna token + link quando email não existe")
    void create_happyPath() {
        CreateInvitationDTO dto = new CreateInvitationDTO("novo@silva.com");
        when(userRepository.existsByEmail("novo@silva.com")).thenReturn(false);
        when(invitationRepository.existsByEmailAndTenantIdAndUsedFalseAndExpiresAtAfter(
                eq("novo@silva.com"), eq(tenant.getId()), any())).thenReturn(false);
        when(invitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        InvitationResponseDTO result = service.create(dto, admin);

        assertThat(result.email()).isEqualTo("novo@silva.com");
        assertThat(result.token()).isNotBlank();
        assertThat(result.link()).isEqualTo("http://localhost:4200/accept-invite?token=" + result.token());
        assertThat(result.expiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("Lança BusinessConflictException quando email já tem conta")
    void create_emailAlreadyExists() {
        CreateInvitationDTO dto = new CreateInvitationDTO("existente@silva.com");
        when(userRepository.existsByEmail("existente@silva.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto, admin))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessage("Este email já possui uma conta");
    }

    @Test
    @DisplayName("Lança BusinessConflictException quando já existe convite pendente")
    void create_pendingInviteExists() {
        CreateInvitationDTO dto = new CreateInvitationDTO("pendente@silva.com");
        when(userRepository.existsByEmail("pendente@silva.com")).thenReturn(false);
        when(invitationRepository.existsByEmailAndTenantIdAndUsedFalseAndExpiresAtAfter(
                eq("pendente@silva.com"), eq(tenant.getId()), any())).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto, admin))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessage("Já existe um convite pendente para este email");
    }
}
