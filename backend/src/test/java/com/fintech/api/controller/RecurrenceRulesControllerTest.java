package com.fintech.api.controller;

import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.recurrence.RecurrenceRuleResponseDTO;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class RecurrenceRulesControllerTest {

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

    private RecurrenceRuleResponseDTO sample() {
        return new RecurrenceRuleResponseDTO(
                UUID.randomUUID(), "Netflix", new BigDecimal("55.90"), TransactionType.EXPENSE,
                null, null, UUID.randomUUID(), "Conta Corrente",
                "FREQ=MONTHLY;BYMONTHDAY=15", LocalDate.of(2026, 1, 15), RecurrenceStatus.ACTIVE);
    }

    private String createBody(String rrule) {
        return """
                { "description": "Netflix", "baseAmount": 55.90, "type": "EXPENSE",
                  "accountId": "%s", "rrule": "%s", "startDate": "2026-01-15" }
                """.formatted(UUID.randomUUID(), rrule);
    }

    @Test
    @DisplayName("Cria regra válida → 201 com status ACTIVE")
    void criaRegraValida() throws Exception {
        when(service.create(any(), any())).thenReturn(sample());

        mockMvc.perform(post("/api/recurrence-rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("FREQ=MONTHLY;BYMONTHDAY=15")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.description").value("Netflix"));
    }

    @Test
    @DisplayName("RRULE fora do subconjunto → 400")
    void rejeitaRruleForaDoSubconjunto() throws Exception {
        mockMvc.perform(post("/api/recurrence-rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("FREQ=WEEKLY;BYDAY=MO")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Lista regras do tenant → 200")
    void listaRegras() throws Exception {
        when(service.findAll(any())).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/recurrence-rules")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value("Netflix"));
    }

    @Test
    @DisplayName("Cancela regra → 204")
    void cancelaRegra() throws Exception {
        mockMvc.perform(delete("/api/recurrence-rules/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Regra inexistente para o tenant → 404")
    void regraDeOutroTenant404() throws Exception {
        when(service.findById(any(), any()))
                .thenThrow(new EntityNotFoundException("Regra de recorrência não encontrada."));

        mockMvc.perform(get("/api/recurrence-rules/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
