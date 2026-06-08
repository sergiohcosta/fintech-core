package com.fintech.api.controller;

import com.fintech.api.domain.enums.DeleteInstallmentScope;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController implements TransactionsApi {

    private final TransactionService service;
    private final UserRepository userRepository;

    @Override
    @GetMapping
    public ResponseEntity<List<TransactionResponseDTO>> listTransactions(
            @RequestParam(value = "invoiceId",   required = false) UUID invoiceId,
            @RequestParam(value = "accountIds",  required = false) List<UUID> accountIds,
            @RequestParam(value = "status",      required = false) TransactionStatus status,
            @RequestParam(value = "type",        required = false) TransactionType type,
            @RequestParam(value = "startDate",   required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate",     required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(
                service.findAll(getAuthenticatedUser(), invoiceId, accountIds, status, type, startDate, endDate));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponseDTO> getTransaction(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id, getAuthenticatedUser()));
    }

    @PostMapping
    public ResponseEntity<List<TransactionResponseDTO>> createTransaction(
            @RequestBody @Valid TransactionRequestDTO transactionRequestDTO) {
        // O service agora retorna uma Lista, pois uma compra em 12x gera 12 registros
        List<TransactionResponseDTO> newTransactions = service.create(transactionRequestDTO, getAuthenticatedUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(newTransactions);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionResponseDTO> updateTransaction(
            @PathVariable UUID id,
            @RequestBody @Valid TransactionUpdateDTO transactionUpdateDTO) {
        return ResponseEntity.ok(service.update(id, transactionUpdateDTO, getAuthenticatedUser()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<DeleteInstallmentResultDTO> deleteTransaction(
            @PathVariable UUID id,
            @RequestParam(value = "scope", defaultValue = "SINGLE") DeleteInstallmentScope scope) {
        DeleteInstallmentResultDTO result = service.delete(id, scope, getAuthenticatedUser());
        return ResponseEntity.ok(result);
    }

    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}