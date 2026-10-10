package com.fintech.api.controller;

import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.InstallmentPreviewDTO;
import com.fintech.api.dto.transaction.InstallmentPreviewRequestDTO;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.exception.BusinessException;
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
                                null, null, null, null, null, null, null, null, null, null,
                                false, null, null, null);
        }

        @Test
        @DisplayName("Should list all transactions for authenticated user")
        void shouldListAllTransactions() throws Exception {
                // Arrange
                when(transactionService.findAll(
                                any(User.class), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false)))
                                .thenReturn(List.of(buildSampleDto("Test")));

                // Act & Assert
                mockMvc.perform(get("/api/transactions")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].description").value("Test"))
                                .andExpect(jsonPath("$[0].amount").value(100.00));
        }

        @Test
        @DisplayName("Deve repassar accountIds ao service quando informado")
        void shouldPassAccountIdFilter() throws Exception {
                UUID accountId = UUID.randomUUID();
                when(transactionService.findAll(
                                any(User.class), isNull(), eq(List.of(accountId)), isNull(), isNull(), isNull(), isNull(), eq(false)))
                                .thenReturn(List.of(buildSampleDto("Nubank")));

                mockMvc.perform(get("/api/transactions")
                                .param("accountIds", accountId.toString())
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].description").value("Nubank"));
        }

        @Test
        @DisplayName("Deve repassar status ao service quando informado")
        void shouldPassStatusFilter() throws Exception {
                when(transactionService.findAll(
                                any(User.class), isNull(), isNull(), eq(TransactionStatus.PENDING), isNull(), isNull(), isNull(), eq(false)))
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
                                any(User.class), isNull(), isNull(), isNull(), isNull(), eq(start), eq(end), eq(false)))
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
                                null, null, null, null, null, null, false, null, null, null, null, null, null, null, null, null, null,
                                false, null, null, null);

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

        @Test
        @DisplayName("POST /installment-preview retorna 200 com a lista de parcelas")
        void shouldReturnInstallmentPreview() throws Exception {
                UUID accountId = UUID.randomUUID();
                InstallmentPreviewRequestDTO requestDTO = new InstallmentPreviewRequestDTO(
                                new BigDecimal("300.00"), LocalDate.of(2026, 7, 8), 2, accountId);

                Invoice julhoFechada = Invoice.builder().id(UUID.randomUUID())
                                .referenceYear(2026).referenceMonth(7)
                                .closingDate(LocalDate.of(2026, 8, 2)).dueDate(LocalDate.of(2026, 8, 10))
                                .status(InvoiceStatus.PAID).build();

                when(transactionService.previewInstallments(any(InstallmentPreviewRequestDTO.class), any(User.class)))
                                .thenReturn(List.of(
                                                new InstallmentPreviewDTO(1, 2, new BigDecimal("150.00"), 2026, 7,
                                                                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 10),
                                                                julhoFechada.getId(), InvoiceStatus.PAID, false),
                                                new InstallmentPreviewDTO(2, 2, new BigDecimal("150.00"), 2026, 8,
                                                                LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 10),
                                                                null, null, true)));

                mockMvc.perform(post("/api/transactions/installment-preview")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestDTO)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].installmentNumber").value(1))
                                .andExpect(jsonPath("$[0].willCreate").value(false))
                                .andExpect(jsonPath("$[0].invoiceStatus").value("PAID"))
                                .andExpect(jsonPath("$[0].referenceMonth").value(7))
                                .andExpect(jsonPath("$[0].dueDate").value("2026-08-10"))
                                .andExpect(jsonPath("$[1].installmentNumber").value(2))
                                .andExpect(jsonPath("$[1].willCreate").value(true));
        }

        @Test
        @DisplayName("POST /installment-preview retorna 400 para conta não-cartão")
        void shouldReturn400ForNonCreditCardPreview() throws Exception {
                InstallmentPreviewRequestDTO requestDTO = new InstallmentPreviewRequestDTO(
                                new BigDecimal("300.00"), LocalDate.of(2026, 7, 8), 2, UUID.randomUUID());

                when(transactionService.previewInstallments(any(InstallmentPreviewRequestDTO.class), any(User.class)))
                                .thenThrow(new BusinessException("O preview de parcelamento só se aplica a contas de cartão de crédito."));

                mockMvc.perform(post("/api/transactions/installment-preview")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestDTO)))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /installment-preview sem body válido → 400")
        void shouldReturn400ForInvalidPreviewRequest() throws Exception {
                mockMvc.perform(post("/api/transactions/installment-preview")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isBadRequest());
        }
}
