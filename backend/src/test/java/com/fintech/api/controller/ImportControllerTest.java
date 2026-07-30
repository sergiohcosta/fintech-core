package com.fintech.api.controller;

import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.StagedTransactionStatus;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.dto.imports.StagedTransactionResponseDTO;
import com.fintech.api.exception.BusinessException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.imports.ImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Borda HTTP do descarte de staged (Fase 2, metade B). Prova o MAPEAMENTO de exceção → status
 * pelo {@code GlobalExceptionHandler}: BusinessException → 400, EntityNotFoundException → 404
 * (staged de outro tenant não pode virar 403 nem 200 vazio — 404 não confirma existência).
 */
@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class ImportControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private ImportService importService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TokenService tokenService;

    private String token;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setName("Test User");
        user.setEmail("import@email.com");
        user.setPasswordHash("hash");
        user.setTenant(new Tenant());
        user.getTenant().setId(UUID.randomUUID());

        token = "valid-token";
        when(tokenService.validateToken(token)).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    private StagedTransactionResponseDTO discarded(UUID batchId, UUID stagedId) {
        return new StagedTransactionResponseDTO(
                stagedId, batchId,
                Map.of("amount", new StagedFieldValueDTO(new BigDecimal("42.00"), BigDecimal.ONE)),
                null, null, new BigDecimal("0.95"), false, null, null,
                StagedTransactionStatus.DISCARDED, LocalDateTime.now());
    }

    @Test
    @DisplayName("POST /api/imports/{id}/staged/{stagedId}/discard → 200 com a staged DISCARDED")
    void discardReturns200() throws Exception {
        UUID batchId = UUID.randomUUID();
        UUID stagedId = UUID.randomUUID();

        when(importService.discardStaged(eq(batchId), eq(stagedId), any()))
                .thenReturn(discarded(batchId, stagedId));

        mockMvc.perform(post("/api/imports/{id}/staged/{stagedId}/discard", batchId, stagedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(stagedId.toString()))
                .andExpect(jsonPath("$.status").value("DISCARDED"));
    }

    @Test
    @DisplayName("POST .../discard com staged não-PENDING → 400")
    void discardReturns400WhenNotPending() throws Exception {
        UUID batchId = UUID.randomUUID();
        UUID stagedId = UUID.randomUUID();

        when(importService.discardStaged(eq(batchId), eq(stagedId), any()))
                .thenThrow(new BusinessException("Só é possível descartar uma staged pendente."));

        mockMvc.perform(post("/api/imports/{id}/staged/{stagedId}/discard", batchId, stagedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST .../discard com staged inexistente ou de outro tenant → 404")
    void discardReturns404WhenNotFound() throws Exception {
        UUID batchId = UUID.randomUUID();
        UUID stagedId = UUID.randomUUID();

        when(importService.discardStaged(eq(batchId), eq(stagedId), any()))
                .thenThrow(new EntityNotFoundException("Transação em staging não encontrada."));

        mockMvc.perform(post("/api/imports/{id}/staged/{stagedId}/discard", batchId, stagedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
