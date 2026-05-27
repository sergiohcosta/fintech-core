package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.account.AccountCreateDTO;
import com.fintech.api.dto.account.AccountResponseDTO;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.AccountService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class AccountControllerTest {

    private MockMvc mockMvc;

    @Autowired WebApplicationContext context;
    @MockitoBean AccountService accountService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TokenService tokenService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private User user;
    private String token;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        user = new User();
        user.setEmail("test@test.com");
        user.setTenant(tenant);
        token = "Bearer mock-token";

        when(tokenService.validateToken(anyString())).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("GET /api/accounts retorna 200 com lista de contas")
    void listAccountsReturns200() throws Exception {
        AccountResponseDTO dto = new AccountResponseDTO(
                UUID.randomUUID(), "Bradesco", AccountType.CHECKING,
                "#FF0000", null, true, true, true, BigDecimal.ZERO, null);

        when(accountService.findAll(any())).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/accounts").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Bradesco"))
                .andExpect(jsonPath("$[0].type").value("CHECKING"))
                .andExpect(jsonPath("$[0].balance").value(0));
    }

    @Test
    @DisplayName("POST /api/accounts retorna 201 com conta criada")
    void createAccountReturns201() throws Exception {
        AccountCreateDTO req = new AccountCreateDTO(
                "Nubank", AccountType.CREDIT_CARD, "#8A05BE", null, null, null, null);
        AccountResponseDTO res = new AccountResponseDTO(
                UUID.randomUUID(), "Nubank", AccountType.CREDIT_CARD,
                "#8A05BE", null, false, true, true, BigDecimal.ZERO, null);

        when(accountService.create(any(), any())).thenReturn(res);

        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CREDIT_CARD"))
                .andExpect(jsonPath("$.countInLiquidBalance").value(false));
    }

    @Test
    @DisplayName("GET /api/accounts sem token retorna 403")
    void listAccountsWithoutTokenReturns403() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/accounts/{id} retorna 204")
    void deleteAccountReturns204() throws Exception {
        mockMvc.perform(delete("/api/accounts/" + UUID.randomUUID())
                        .header("Authorization", token))
                .andExpect(status().isNoContent());
    }
}
