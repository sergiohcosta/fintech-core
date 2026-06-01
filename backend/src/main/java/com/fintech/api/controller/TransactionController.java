package com.fintech.api.controller;

import com.fintech.api.domain.enums.DeleteInstallmentScope;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionUpdateDTO;
import com.fintech.api.openapi.TransactionsApi;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController implements TransactionsApi {

    private final TransactionService service;
    private final UserRepository userRepository;

    /**
     * Lista todas as transações do Tenant do usuário logado.
     * Ordenadas por data (mais recentes primeiro).
     */
    @Override
    @GetMapping
    public ResponseEntity<List<TransactionResponseDTO>> listTransactions() {
        return ResponseEntity.ok(service.findAll(getAuthenticatedUser()));
    }

    /**
     * Busca uma transação pelo ID, escopada ao Tenant do usuário logado.
     */
    @Override
    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponseDTO> getTransaction(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id, getAuthenticatedUser()));
    }

    /**
     * Cria uma ou múltiplas transações (caso seja parcelado).
     * Retorna HTTP 201 e a lista dos itens criados.
     */
    @Override
    @PostMapping
    public ResponseEntity<List<TransactionResponseDTO>> createTransaction(
            @RequestBody @Valid TransactionRequestDTO transactionRequestDTO) {
        // O service agora retorna uma Lista, pois uma compra em 12x gera 12 registros
        List<TransactionResponseDTO> newTransactions = service.create(transactionRequestDTO, getAuthenticatedUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(newTransactions);
    }

    @Override
    @PutMapping("/{id}")
    public ResponseEntity<TransactionResponseDTO> updateTransaction(
            @PathVariable UUID id,
            @RequestBody @Valid TransactionUpdateDTO transactionUpdateDTO) {
        return ResponseEntity.ok(service.update(id, transactionUpdateDTO, getAuthenticatedUser()));
    }

    @Override
    @DeleteMapping("/{id}")
    public ResponseEntity<DeleteInstallmentResultDTO> deleteTransaction(
            @PathVariable UUID id,
            @RequestParam(value = "scope", defaultValue = "SINGLE") DeleteInstallmentScope scope) {
        DeleteInstallmentResultDTO result = service.delete(id, scope, getAuthenticatedUser());
        return ResponseEntity.ok(result);
    }

    // Obtém o usuário autenticado via SecurityContextHolder em vez de @AuthenticationPrincipal,
    // pois a interface OpenAPI não inclui esse parâmetro extra nas assinaturas dos métodos.
    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}