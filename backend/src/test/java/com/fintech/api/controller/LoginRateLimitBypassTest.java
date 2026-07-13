package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.api.config.LoginRateLimiter;
import com.fintech.api.config.TokenService;
import com.fintech.api.dto.LoginDTO;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.InvitationService;
import com.fintech.api.service.TenantRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #144 — o rate limit de login não pode ser contornável rotacionando X-Forwarded-For.
 *
 * Usa o {@link LoginRateLimiter} REAL (não @MockitoBean) para exercitar a construção da chave e o
 * rastreamento de falhas de ponta a ponta. Com a chave antiga {@code ip:email} lendo o header do
 * cliente, cada request forjava uma chave nova → o teto de 5/email nunca era atingido. Com a chave
 * por email apenas, o bloqueio (429) acontece independentemente do header.
 */
@SpringBootTest
class LoginRateLimitBypassTest {

    @Autowired WebApplicationContext context;

    @MockitoBean UserRepository userRepository;
    @MockitoBean PasswordEncoder passwordEncoder;
    @MockitoBean TokenService tokenService;
    @MockitoBean TenantRegistrationService registrationService;
    @MockitoBean InvitationService invitationService;
    // LoginRateLimiter NÃO é mockado — usa o bean real.
    @Autowired LoginRateLimiter loginRateLimiter;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("rate limit por email não é contornável rotacionando X-Forwarded-For")
    void rotatingXForwardedForStillHitsRateLimit() throws Exception {
        String email = "bypass-" + UUID.randomUUID() + "@test.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty()); // força falha de auth
        String body = objectMapper.writeValueAsString(new LoginDTO(email, "wrong"));

        // 5 falhas, cada uma com um IP forjado diferente (tentativa de bypass).
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/login")
                            .header("X-Forwarded-For", "10.0.0." + i)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        // 6ª tentativa, ainda outro IP forjado → deve ser bloqueada por email (429), não 401.
        mockMvc.perform(post("/auth/login")
                        .header("X-Forwarded-For", "10.0.0.99")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }
}
