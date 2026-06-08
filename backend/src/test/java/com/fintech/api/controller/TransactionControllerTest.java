package com.fintech.api.controller;

import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.service.TransactionService;
import com.fintech.api.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class TransactionControllerTest {

        private MockMvc mockMvc;

        @Autowired
        private WebApplicationContext context;

        @MockitoBean
        private TransactionService transactionService;

        @MockitoBean
        private UserRepository userRepository;

        @MockitoBean
        private TokenService tokenService;

        private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        private User user;
        private String token;

        @BeforeEach
        void setup() {
                mockMvc = MockMvcBuilders.webAppContextSetup(context)
                                .apply(springSecurity())
                                .build();

                user = new User();
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

        private TransactionResponseDTO buildSampleDto(String description) {
                return new TransactionResponseDTO(
                                UUID.randomUUID(), description, new BigDecimal("100.00"), LocalDate.now(),
                                null, null, null, null, null, null, null, false,
                                null, null, null, null, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("Should list all transactions for authenticated user")
        void shouldListAllTransactions() throws Exception {
                // Arrange
                when(transactionService.findAll(
                                any(User.class), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                                .thenReturn(List.of(buildSampleDto("Test")));

                // Act & Assert
                mockMvc.perform(get("/api/transactions")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].description").value("Test"))
                                .andExpect(jsonPath("$[0].amount").value(100.00));
        }

        @Test
        @DisplayName("Deve repassar accountId ao service quando informado")
        void shouldPassAccountIdFilter() throws Exception {
                UUID accountId = UUID.randomUUID();
                when(transactionService.findAll(
                                any(User.class), isNull(), eq(accountId), isNull(), isNull(), isNull(), isNull()))
                                .thenReturn(List.of(buildSampleDto("Nubank")));

                mockMvc.perform(get("/api/transactions")
                                .param("accountId", accountId.toString())
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].description").value("Nubank"));
        }

        @Test
        @DisplayName("Deve repassar status ao service quando informado")
        void shouldPassStatusFilter() throws Exception {
                when(transactionService.findAll(
                                any(User.class), isNull(), isNull(), eq(TransactionStatus.PENDING), isNull(), isNull(), isNull()))
                                .thenReturn(List.of(buildSampleDto("Pendente")));

                mockMvc.perform(get("/api/transactions")
                                .param("status", "PENDING")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].description").value("Pendente"));
        }

        @Test
        @DisplayName("Deve repassar período ao service quando informado")
        void shouldPassDateRangeFilter() throws Exception {
                LocalDate start = LocalDate.of(2026, 6, 1);
                LocalDate end   = LocalDate.of(2026, 6, 30);
                when(transactionService.findAll(
                                any(User.class), isNull(), isNull(), isNull(), isNull(), eq(start), eq(end)))
                                .thenReturn(List.of(buildSampleDto("Junho")));

                mockMvc.perform(get("/api/transactions")
                                .param("startDate", "2026-06-01")
                                .param("endDate",   "2026-06-30")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].description").value("Junho"));
        }

        @Test
        @DisplayName("Should create transaction and return 201")
        void shouldCreateTransaction() throws Exception {
                // Arrange
                TransactionRequestDTO requestDTO = new TransactionRequestDTO(
                                "New Transaction", new BigDecimal("50.00"), LocalDate.now(), TransactionType.EXPENSE,
                                null, 1, null, UUID.randomUUID());

                TransactionResponseDTO responseDTO = new TransactionResponseDTO(
                                UUID.randomUUID(), "New Transaction", new BigDecimal("50.00"), LocalDate.now(), null,
                                null, null, null, null, null, null, false, null, null, null, null, null, null, null, null, null, null);

                when(transactionService.create(any(TransactionRequestDTO.class), any(User.class)))
                                .thenReturn(List.of(responseDTO));

                // Act & Assert
                mockMvc.perform(post("/api/transactions")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestDTO)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$[0].description").value("New Transaction"));
        }
}
