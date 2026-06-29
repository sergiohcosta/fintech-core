package com.fintech.api.controller;

import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.RecurrenceRuleService;
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
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class RecurrenceOccurrenceControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private RecurrenceRuleService service;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TokenService tokenService;

    private String token;
    private final UUID ruleId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setName("Test User");
        user.setEmail("test@email.com");
        user.setPasswordHash("hash");
        user.setTenant(new Tenant());
        user.getTenant().setId(UUID.randomUUID());

        token = "valid-token";
        when(tokenService.validateToken(token)).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    private TransactionResponseDTO sampleTx() {
        return new TransactionResponseDTO(
                UUID.randomUUID(), "Netflix", new BigDecimal("55.90"), LocalDate.of(2026, 8, 10),
                TransactionType.EXPENSE, TransactionStatus.PENDING,
                null, null, null, null, null, false, null, null,
                "Conta", UUID.randomUUID(), null, null, null, null, null, null,
                false, null, null);
    }

    @Test
    @DisplayName("Confirmar sem corpo → 201 com a transação materializada")
    void confirmaSemCorpo() throws Exception {
        when(service.confirmOccurrence(any(), any(), any(), any())).thenReturn(sampleTx());

        mockMvc.perform(post("/api/recurrence-rules/{id}/occurrences/{date}/confirm", ruleId, "2026-08-10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Netflix"));
    }

    @Test
    @DisplayName("Confirmar com valor ajustado → 201")
    void confirmaComValor() throws Exception {
        when(service.confirmOccurrence(any(), any(), any(), any())).thenReturn(sampleTx());

        mockMvc.perform(post("/api/recurrence-rules/{id}/occurrences/{date}/confirm", ruleId, "2026-08-10")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"amount\": 150.00 }"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Confirmar ocorrência já materializada → 409")
    void confirmaDuplicada409() throws Exception {
        when(service.confirmOccurrence(any(), any(), any(), any()))
                .thenThrow(new BusinessConflictException("Esta ocorrência já foi confirmada."));

        mockMvc.perform(post("/api/recurrence-rules/{id}/occurrences/{date}/confirm", ruleId, "2026-08-10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Pular ocorrência → 204")
    void pula204() throws Exception {
        mockMvc.perform(post("/api/recurrence-rules/{id}/occurrences/{date}/skip", ruleId, "2026-07-05")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Confirmar em regra de outro tenant → 404")
    void confirmaOutroTenant404() throws Exception {
        when(service.confirmOccurrence(any(), any(), any(), any()))
                .thenThrow(new EntityNotFoundException("Regra de recorrência não encontrada."));

        mockMvc.perform(post("/api/recurrence-rules/{id}/occurrences/{date}/confirm", ruleId, "2026-08-10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
