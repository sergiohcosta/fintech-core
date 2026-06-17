package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetItemRealizeRequest;
import com.fintech.api.dto.budget.RecurringBudgetItemRequest;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.BudgetCycleService;
import com.fintech.api.service.BudgetItemService;
import com.fintech.api.service.BudgetSummaryService;
import com.fintech.api.service.RecurringBudgetItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class BudgetControllerTest {

    private MockMvc mockMvc;

    @Autowired WebApplicationContext context;
    @MockitoBean BudgetCycleService cycleService;
    @MockitoBean BudgetItemService itemService;
    @MockitoBean RecurringBudgetItemService recurringService;
    @MockitoBean BudgetSummaryService summaryService;
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

    // --- GET /api/budget-cycles/{id} ---

    @Test
    @DisplayName("GET /api/budget-cycles/{id} com ID de outro tenant retorna 403")
    void getCycleFromAnotherTenantReturns403() throws Exception {
        UUID otherTenantCycleId = UUID.randomUUID();

        when(cycleService.findByIdAndTenant(any(UUID.class), any()))
            .thenThrow(new AccessDeniedException("Acesso negado."));

        mockMvc.perform(get("/api/budget-cycles/" + otherTenantCycleId)
                        .header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/budget-cycles/{id} com ID inexistente retorna 403")
    void getCycleWithNonExistentIdReturns403() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        when(cycleService.findByIdAndTenant(any(UUID.class), any()))
            .thenThrow(new AccessDeniedException("Acesso negado."));

        mockMvc.perform(get("/api/budget-cycles/" + nonExistentId)
                        .header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    // --- POST /api/budget-cycles ---

    @Test
    @DisplayName("POST /api/budget-cycles sem auth retorna 403")
    void createCycleWithoutAuthReturns403() throws Exception {
        String body = mapper.writeValueAsString(
            new java.util.LinkedHashMap<>() {{
                put("referenceMonth", "2025-06");
                put("startDay", 1);
            }});

        mockMvc.perform(post("/api/budget-cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/budget-cycles com referenceMonth inválido retorna 400")
    void createCycleWithInvalidReferenceMonthReturns400() throws Exception {
        String body = mapper.writeValueAsString(
            new java.util.LinkedHashMap<>() {{
                put("referenceMonth", "invalid-month");
                put("startDay", 1);
            }});

        mockMvc.perform(post("/api/budget-cycles")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // --- POST /api/budget-items/{id}/realize ---

    @Test
    @DisplayName("POST /api/budget-items/{id}/realize com transação de outro tenant retorna 403")
    void realizeItemWithOtherTenantTransactionReturns403() throws Exception {
        UUID itemId = UUID.randomUUID();
        UUID otherTenantTransactionId = UUID.randomUUID();

        when(itemService.findByIdAndTenant(any(UUID.class), any()))
            .thenReturn(null); // item lookup succeeds
        when(itemService.realize(any(), any(UUID.class), any(), any()))
            .thenThrow(new AccessDeniedException("Acesso negado."));

        BudgetItemRealizeRequest req = new BudgetItemRealizeRequest(otherTenantTransactionId);

        mockMvc.perform(post("/api/budget-items/" + itemId + "/realize")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // --- DELETE /api/budget-items/{id} ---

    @Test
    @DisplayName("DELETE /api/budget-items/{id} com item REALIZED retorna 422")
    void deleteRealizedItemReturns422() throws Exception {
        UUID itemId = UUID.randomUUID();

        when(itemService.findByIdAndTenant(any(UUID.class), any()))
            .thenReturn(null); // item lookup succeeds
        doThrow(new IllegalStateException("Itens realizados são imutáveis."))
            .when(itemService).delete(any());

        mockMvc.perform(delete("/api/budget-items/" + itemId)
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity());
    }

    // --- PUT /api/recurring-budget-items/{id} ---

    @Test
    @DisplayName("PUT /api/recurring-budget-items/{id} com item inativo retorna 422")
    void updateInactiveRecurringItemReturns422() throws Exception {
        UUID itemId = UUID.randomUUID();

        when(recurringService.update(any(UUID.class), any(RecurringBudgetItemRequest.class), any()))
            .thenThrow(new IllegalStateException("O item deve ser reativado antes de ser editado."));

        RecurringBudgetItemRequest req = new RecurringBudgetItemRequest(
            "Aluguel", new java.math.BigDecimal("1500.00"),
            com.fintech.api.domain.enums.TransactionType.EXPENSE, 10, null, null);

        mockMvc.perform(put("/api/recurring-budget-items/" + itemId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity());
    }

    // --- DELETE /api/budget-cycles/{id} ---

    @Test
    @DisplayName("DELETE /api/budget-cycles/{id} com role MEMBER retorna 403")
    void deleteCycleAsMemberReturns403() throws Exception {
        // user.role é null por padrão → getAuthorities() retorna apenas ROLE_USER (MEMBER)
        mockMvc.perform(delete("/api/budget-cycles/" + UUID.randomUUID())
                        .header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    // --- POST /api/budget-items/{id}/skip ---

    @Test
    @DisplayName("POST /api/budget-items/{id}/skip em ciclo CLOSED retorna 422")
    void skipItemInClosedCycleReturns422() throws Exception {
        UUID itemId = UUID.randomUUID();

        when(itemService.findByIdAndTenant(any(UUID.class), any()))
            .thenReturn(null); // item lookup succeeds
        when(itemService.skip(any()))
            .thenThrow(new IllegalStateException("O ciclo está fechado para alterações."));

        mockMvc.perform(post("/api/budget-items/" + itemId + "/skip")
                        .header("Authorization", token))
                .andExpect(status().isUnprocessableEntity());
    }
}
