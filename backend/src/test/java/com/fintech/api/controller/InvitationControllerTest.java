package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.InvitationStatus;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.CreateInvitationDTO;
import com.fintech.api.dto.InvitationInfoDTO;
import com.fintech.api.dto.InvitationResponseDTO;
import com.fintech.api.dto.InvitationSummaryDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.exception.InviteAlreadyUsedException;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.InvitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class InvitationControllerTest {

    private MockMvc mockMvc;

    @Autowired WebApplicationContext context;
    @MockitoBean InvitationService invitationService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TokenService tokenService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private User adminUser;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        adminUser = new User();
        adminUser.setEmail("admin@test.com");
        adminUser.setRole(UserRole.ADMIN);
        adminUser.setTenant(tenant);

        when(tokenService.validateToken(anyString())).thenReturn(adminUser.getEmail());
        when(userRepository.findByEmail(adminUser.getEmail())).thenReturn(Optional.of(adminUser));
    }

    @Test
    @DisplayName("POST /invites retorna 201 com token e link para ADMIN autenticado")
    void createInvite_returnsCreated() throws Exception {
        CreateInvitationDTO dto = new CreateInvitationDTO("convidado@test.com");
        InvitationResponseDTO response = new InvitationResponseDTO(
                "abc-token", "http://localhost:4200/accept-invite?token=abc-token",
                "convidado@test.com", LocalDateTime.now().plusDays(7));

        when(invitationService.create(any(), any())).thenReturn(response);

        mockMvc.perform(post("/invites")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("abc-token"))
                .andExpect(jsonPath("$.email").value("convidado@test.com"));
    }

    @Test
    @DisplayName("POST /invites retorna 403 sem autenticação")
    void createInvite_withoutAuth_returns403() throws Exception {
        CreateInvitationDTO dto = new CreateInvitationDTO("x@test.com");

        mockMvc.perform(post("/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /invites retorna 403 para usuário com role USER")
    void createInvite_withUserRole_returns403() throws Exception {
        adminUser.setRole(UserRole.USER);
        CreateInvitationDTO dto = new CreateInvitationDTO("x@test.com");

        mockMvc.perform(post("/invites")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /invites/{token} retorna 200 com email e tenantName")
    void validateToken_returnsOk() throws Exception {
        InvitationInfoDTO info = new InvitationInfoDTO("convidado@test.com", "Família Silva");
        when(invitationService.validate("valid-token")).thenReturn(info);

        mockMvc.perform(get("/invites/valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("convidado@test.com"))
                .andExpect(jsonPath("$.tenantName").value("Família Silva"));
    }

    @Test
    @DisplayName("GET /invites/{token} retorna 404 para token inexistente")
    void validateToken_notFound_returns404() throws Exception {
        when(invitationService.validate("bad-token"))
                .thenThrow(new EntityNotFoundException("Convite inválido ou inexistente"));

        mockMvc.perform(get("/invites/bad-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Convite inválido ou inexistente"));
    }

    @Test
    @DisplayName("GET /invites/{token} retorna 410 para token já usado")
    void validateToken_alreadyUsed_returns410() throws Exception {
        when(invitationService.validate("used-token"))
                .thenThrow(new InviteAlreadyUsedException());

        mockMvc.perform(get("/invites/used-token"))
                .andExpect(status().isGone());
    }

    // --- LISTAR CONVITES ---

    @Test
    @DisplayName("GET /invites retorna 200 com lista para ADMIN autenticado")
    void listInvites_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        InvitationSummaryDTO summary = new InvitationSummaryDTO(
                id, "x@test.com", InvitationStatus.PENDING,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(6),
                "http://localhost:4200/accept-invite?token=tok");

        when(invitationService.list(any())).thenReturn(List.of(summary));

        mockMvc.perform(get("/invites")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("x@test.com"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /invites retorna 403 sem autenticação")
    void listInvites_withoutAuth_returns403() throws Exception {
        mockMvc.perform(get("/invites"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /invites retorna 403 para role USER")
    void listInvites_withUserRole_returns403() throws Exception {
        adminUser.setRole(UserRole.USER);

        mockMvc.perform(get("/invites")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());
    }

    // --- REVOGAR CONVITE ---

    @Test
    @DisplayName("DELETE /invites/{id} retorna 204 para ADMIN")
    void revokeInvite_returnsNoContent() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(invitationService).revoke(eq(id), any());

        mockMvc.perform(delete("/invites/" + id)
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /invites/{id} retorna 403 para role USER")
    void revokeInvite_withUserRole_returns403() throws Exception {
        adminUser.setRole(UserRole.USER);

        mockMvc.perform(delete("/invites/" + UUID.randomUUID())
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());
    }
}
