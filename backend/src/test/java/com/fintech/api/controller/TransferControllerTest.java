package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.dto.transfer.TransferResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.TransactionService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class TransferControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private TransactionService transactionService;

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
        user.setEmail("test@email.com");
        user.setPasswordHash("hash");
        user.setTenant(new Tenant());
        user.getTenant().setId(UUID.randomUUID());

        token = "valid-token";
        when(tokenService.validateToken(token)).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("POST /api/transfers com payload válido retorna 201 com transferId")
    void createTransferReturns201() throws Exception {
        UUID transferId = UUID.randomUUID();

        TransferRequestDTO request = new TransferRequestDTO(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("500.00"), LocalDate.now(), "Reserva");

        TransferResponseDTO response = new TransferResponseDTO(
                transferId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("500.00"), LocalDate.now(), "Reserva",
                "Nubank", "Inter");

        when(transactionService.createTransfer(any(TransferRequestDTO.class), any(User.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.fromAccount").value("Nubank"))
                .andExpect(jsonPath("$.toAccount").value("Inter"));
    }

    @Test
    @DisplayName("POST /api/transfers com contas iguais retorna 400")
    void createTransferWithEqualAccountsReturns400() throws Exception {
        UUID sameId = UUID.randomUUID();
        TransferRequestDTO request = new TransferRequestDTO(
                sameId, sameId, new BigDecimal("100.00"), LocalDate.now(), null);

        when(transactionService.createTransfer(any(TransferRequestDTO.class), any(User.class)))
                .thenThrow(new IllegalArgumentException("As contas de origem e destino devem ser diferentes."));

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/transfers sem autenticação retorna 403")
    void createTransferWithoutAuthReturns403() throws Exception {
        TransferRequestDTO request = new TransferRequestDTO(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), LocalDate.now(), null);

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/transfers/{transferId} válido retorna 204")
    void deleteTransferReturns204() throws Exception {
        mockMvc.perform(delete("/api/transfers/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/transfers/{transferId} inexistente retorna 404")
    void deleteTransferNotFoundReturns404() throws Exception {
        doThrow(new EntityNotFoundException("Transferência não encontrada."))
                .when(transactionService).deleteTransfer(any(UUID.class), any(User.class));

        mockMvc.perform(delete("/api/transfers/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
