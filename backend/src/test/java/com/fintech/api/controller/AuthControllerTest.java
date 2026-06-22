package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.api.config.LoginRateLimiter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.AcceptInviteDTO;
import com.fintech.api.dto.LoginDTO;
import com.fintech.api.dto.TenantRegistrationDTO;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.InvitationService;
import com.fintech.api.service.TenantRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AuthControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private TenantRegistrationService registrationService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private InvitationService invitationService;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        // No need for springSecurity() here as endpoints are public/custom
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("Should register new tenant successfully")
    void shouldRegisterTenant() throws Exception {
        // Arrange
        TenantRegistrationDTO dto = new TenantRegistrationDTO(
                "My Tenant", "Admin", "admin@email.com", "Senha123");

        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("My Tenant");

        when(registrationService.register(any(TenantRegistrationDTO.class))).thenReturn(tenant);

        // Act & Assert
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My Tenant"));
    }

    @Test
    @DisplayName("POST /auth/register retorna 400 quando senha não atende a política mínima")
    void shouldFailRegisterWithWeakPassword() throws Exception {
        TenantRegistrationDTO dto = new TenantRegistrationDTO(
                "My Tenant", "Admin", "admin@email.com", "12345678");

        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /auth/register retorna 400 quando senha excede o tamanho máximo")
    void shouldFailRegisterWithTooLongPassword() throws Exception {
        String tooLong = "Senha123" + "a".repeat(70);
        TenantRegistrationDTO dto = new TenantRegistrationDTO(
                "My Tenant", "Admin", "admin@email.com", tooLong);

        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should login successfully")
    void shouldLoginSuccessfully() throws Exception {
        // Arrange
        LoginDTO loginDTO = new LoginDTO("test@email.com", "password");
        User user = new User();
        user.setEmail("test@email.com");
        user.setPasswordHash("encoded_password");

        when(userRepository.findByEmail("test@email.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded_password")).thenReturn(true);
        when(tokenService.generateToken(user)).thenReturn("valid-token");

        // Act & Assert
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("valid-token"));
    }

    @Test
    @DisplayName("Should fail login with invalid credentials")
    void shouldFailLogin() throws Exception {
        // Arrange
        LoginDTO loginDTO = new LoginDTO("test@email.com", "wrong_password");
        User user = new User();
        user.setEmail("test@email.com");
        user.setPasswordHash("encoded_password");

        when(userRepository.findByEmail("test@email.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong_password", "encoded_password")).thenReturn(false);

        // Act & Assert
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginDTO)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return generic 401 when user does not exist (no enumeration)")
    void shouldFailLoginWhenUserNotFound() throws Exception {
        LoginDTO loginDTO = new LoginDTO("naoexiste@test.com", "qualquer");
        when(userRepository.findByEmail("naoexiste@test.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginDTO)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 401 when user is inactive, even with correct password")
    void shouldFailLoginWhenUserInactive() throws Exception {
        LoginDTO loginDTO = new LoginDTO("inativo@test.com", "password");
        User user = new User();
        user.setEmail("inativo@test.com");
        user.setPasswordHash("encoded_password");
        user.setActive(false);

        when(userRepository.findByEmail("inativo@test.com")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginDTO)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 429 with Retry-After header when rate limit exceeded")
    void shouldReturn429WhenRateLimited() throws Exception {
        LoginDTO loginDTO = new LoginDTO("test@email.com", "password");
        // A chave agora é ip:email; MockMvc usa remoteAddr 127.0.0.1 por padrão.
        // any(String.class) evita acoplamento ao formato exato da chave composta.
        when(loginRateLimiter.isBlocked(any(String.class))).thenReturn(true);
        when(loginRateLimiter.secondsUntilUnblock(any(String.class))).thenReturn(42L);

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginDTO)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"));

        verify(userRepository, never()).findByEmail(any());
        verify(loginRateLimiter, never()).registerFailure(any());
        verify(loginRateLimiter, never()).registerSuccess(any());
    }

    @Test
    @DisplayName("POST /auth/accept-invite retorna 200 com token JWT")
    void shouldAcceptInviteSuccessfully() throws Exception {
        AcceptInviteDTO dto = new AcceptInviteDTO("valid-token", "João Silva", "Senha123");
        when(invitationService.accept(any(AcceptInviteDTO.class))).thenReturn("jwt-result");

        mockMvc.perform(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-result"));
    }

    @Test
    @DisplayName("POST /auth/accept-invite retorna 400 quando senha não atende a política mínima")
    void shouldFailAcceptInviteWithWeakPassword() throws Exception {
        AcceptInviteDTO dto = new AcceptInviteDTO("valid-token", "João Silva", "12345678");

        mockMvc.perform(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /auth/accept-invite retorna 400 quando campos obrigatórios ausentes")
    void shouldFailAcceptInviteWhenMissingFields() throws Exception {
        mockMvc.perform(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
