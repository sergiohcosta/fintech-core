package com.fintech.api.controller;

import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.invoice.InvoicePayDTO;
import com.fintech.api.dto.invoice.InvoiceResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.InvoiceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class InvoiceControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private InvoiceService invoiceService;

    @MockitoBean
    private AccountRepository accountRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TokenService tokenService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

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

    @Test
    @DisplayName("POST /api/invoices/{id}/pay com fatura CLOSED e conta válida → 200")
    void payInvoiceReturns200WhenValid() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID sourceAccountId = UUID.randomUUID();
        InvoicePayDTO payDTO = new InvoicePayDTO(sourceAccountId);

        InvoiceResponseDTO responseDTO = new InvoiceResponseDTO(
                invoiceId, UUID.randomUUID(), "Cartão Nubank",
                6, 2026, "Junho/2026",
                LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 15),
                InvoiceStatus.PAID, new BigDecimal("350.00"), 3L);

        when(invoiceService.pay(eq(invoiceId), any(), any(), eq(sourceAccountId)))
                .thenReturn(responseDTO);

        mockMvc.perform(post("/api/invoices/{id}/pay", invoiceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payDTO)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/invoices/{id}/pay quando serviço lança IllegalStateException → 422")
    void payInvoiceReturns422OnIllegalState() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID sourceAccountId = UUID.randomUUID();
        InvoicePayDTO payDTO = new InvoicePayDTO(sourceAccountId);

        when(invoiceService.pay(eq(invoiceId), any(), any(), eq(sourceAccountId)))
                .thenThrow(new IllegalStateException("Só é possível pagar faturas com status CLOSED."));

        mockMvc.perform(post("/api/invoices/{id}/pay", invoiceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payDTO)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /api/invoices/{id}/pay quando serviço lança EntityNotFoundException → 404")
    void payInvoiceReturns404WhenNotFound() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID sourceAccountId = UUID.randomUUID();
        InvoicePayDTO payDTO = new InvoicePayDTO(sourceAccountId);

        when(invoiceService.pay(eq(invoiceId), any(), any(), eq(sourceAccountId)))
                .thenThrow(new EntityNotFoundException("Fatura não encontrada."));

        mockMvc.perform(post("/api/invoices/{id}/pay", invoiceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payDTO)))
                .andExpect(status().isNotFound());
    }
}
