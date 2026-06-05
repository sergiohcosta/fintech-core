package com.fintech.api.service;

import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.invitation.Invitation;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.AcceptInviteDTO;
import com.fintech.api.dto.CreateInvitationDTO;
import com.fintech.api.dto.InvitationInfoDTO;
import com.fintech.api.dto.InvitationResponseDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.exception.InviteAlreadyUsedException;
import com.fintech.api.exception.InviteExpiredException;
import org.mockito.ArgumentCaptor;
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

import com.fintech.api.domain.enums.InvitationStatus;
import com.fintech.api.dto.InvitationSummaryDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

    // --- helper compartilhado pelos testes abaixo ---

    private Invitation buildInvitation(boolean used, LocalDateTime expiresAt) {
        Invitation inv = new Invitation();
        inv.setId(UUID.randomUUID());
        inv.setTenant(tenant);
        inv.setEmail("convidado@silva.com");
        inv.setToken("valid-token");
        inv.setUsed(used);
        inv.setExpiresAt(expiresAt);
        return inv;
    }

    // --- VALIDAR TOKEN ---

    @Test
    @DisplayName("validate retorna email e tenantName para token válido")
    void validate_happyPath() {
        Invitation inv = buildInvitation(false, LocalDateTime.now().plusDays(1));
        when(invitationRepository.findByToken("valid-token")).thenReturn(Optional.of(inv));

        InvitationInfoDTO result = service.validate("valid-token");

        assertThat(result.email()).isEqualTo("convidado@silva.com");
        assertThat(result.tenantName()).isEqualTo("Família Silva");
    }

    @Test
    @DisplayName("validate lança EntityNotFoundException para token inexistente")
    void validate_tokenNotFound() {
        when(invitationRepository.findByToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate("nope"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Convite inválido ou inexistente");
    }

    @Test
    @DisplayName("validate lança InviteAlreadyUsedException para token já usado")
    void validate_alreadyUsed() {
        Invitation inv = buildInvitation(true, LocalDateTime.now().plusDays(1));
        when(invitationRepository.findByToken("used-token")).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.validate("used-token"))
                .isInstanceOf(InviteAlreadyUsedException.class);
    }

    @Test
    @DisplayName("validate lança InviteExpiredException para token expirado")
    void validate_expired() {
        Invitation inv = buildInvitation(false, LocalDateTime.now().minusDays(1));
        when(invitationRepository.findByToken("expired-token")).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.validate("expired-token"))
                .isInstanceOf(InviteExpiredException.class);
    }

    // --- ACEITAR CONVITE ---

    @Test
    @DisplayName("accept cria usuário USER, marca convite como usado e retorna JWT")
    void accept_happyPath() {
        Invitation inv = buildInvitation(false, LocalDateTime.now().plusDays(1));
        when(invitationRepository.findByToken("valid-token")).thenReturn(Optional.of(inv));
        when(userRepository.existsByEmail("convidado@silva.com")).thenReturn(false);
        when(passwordEncoder.encode("senha123")).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(tokenService.generateToken(any())).thenReturn("jwt-token");

        AcceptInviteDTO dto = new AcceptInviteDTO("valid-token", "João Silva", "senha123");
        String jwt = service.accept(dto);

        assertThat(jwt).isEqualTo("jwt-token");
        assertThat(inv.isUsed()).isTrue();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.getEmail()).isEqualTo("convidado@silva.com");
        assertThat(saved.getTenant()).isEqualTo(tenant);
    }

    @Test
    @DisplayName("accept lança BusinessConflictException quando email já tem conta")
    void accept_emailAlreadyExists() {
        Invitation inv = buildInvitation(false, LocalDateTime.now().plusDays(1));
        when(invitationRepository.findByToken("valid-token")).thenReturn(Optional.of(inv));
        when(userRepository.existsByEmail("convidado@silva.com")).thenReturn(true);

        AcceptInviteDTO dto = new AcceptInviteDTO("valid-token", "João", "senha");
        assertThatThrownBy(() -> service.accept(dto))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessage("Este email já possui uma conta");
    }

    // --- LISTAR CONVITES ---

    @Test
    @DisplayName("list retorna convites do tenant com status calculado")
    void list_returnsInvitationsWithStatus() {
        Invitation pending = buildInvitation(false, LocalDateTime.now().plusDays(3));
        Invitation accepted = buildInvitation(true, LocalDateTime.now().plusDays(1));
        Invitation expired = buildInvitation(false, LocalDateTime.now().minusDays(1));

        when(invitationRepository.findAllByTenantIdOrderByCreatedAtDesc(tenant.getId()))
                .thenReturn(List.of(pending, accepted, expired));

        List<InvitationSummaryDTO> result = service.list(admin);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(result.get(0).link()).isNotNull();
        assertThat(result.get(1).status()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(result.get(1).link()).isNull();
        assertThat(result.get(2).status()).isEqualTo(InvitationStatus.EXPIRED);
        assertThat(result.get(2).link()).isNull();
    }

    @Test
    @DisplayName("list retorna apenas convites do tenant do admin")
    void list_filtersOnlyAdminTenant() {
        when(invitationRepository.findAllByTenantIdOrderByCreatedAtDesc(tenant.getId()))
                .thenReturn(List.of());

        List<InvitationSummaryDTO> result = service.list(admin);

        assertThat(result).isEmpty();
        verify(invitationRepository).findAllByTenantIdOrderByCreatedAtDesc(tenant.getId());
    }

    // --- REVOGAR CONVITE ---

    @Test
    @DisplayName("revoke exclui convite pendente")
    void revoke_deletesPendingInvitation() {
        Invitation pending = buildInvitation(false, LocalDateTime.now().plusDays(3));
        when(invitationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        service.revoke(pending.getId(), admin);

        verify(invitationRepository).delete(pending);
    }

    @Test
    @DisplayName("revoke lança BusinessConflictException para convite aceito")
    void revoke_throwsConflictForAcceptedInvitation() {
        Invitation accepted = buildInvitation(true, LocalDateTime.now().plusDays(1));
        when(invitationRepository.findById(accepted.getId())).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> service.revoke(accepted.getId(), admin))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessage("Convite já aceito não pode ser revogado");
    }

    @Test
    @DisplayName("revoke lança EntityNotFoundException para convite de outro tenant")
    void revoke_throwsNotFoundForDifferentTenant() {
        Tenant other = new Tenant();
        other.setId(UUID.randomUUID());
        Invitation foreign = buildInvitation(false, LocalDateTime.now().plusDays(3));
        foreign.setTenant(other);
        when(invitationRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.revoke(foreign.getId(), admin))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Convite não encontrado");
    }
}
