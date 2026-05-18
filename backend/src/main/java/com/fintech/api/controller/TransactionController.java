package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionUpdateDTO;
import com.fintech.api.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService service;

    /**
     * Lista todas as transações do Tenant do usuário logado.
     * Ordenadas por data (mais recentes primeiro).
     */
    @GetMapping
    public ResponseEntity<List<TransactionResponseDTO>> listAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.findAll(user));
    }

    /**
     * Cria uma ou múltiplas transações (caso seja parcelado).
     * Retorna HTTP 201 e a lista dos itens criados.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponseDTO> findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.findById(id, user));
    }

    @PostMapping
    public ResponseEntity<List<TransactionResponseDTO>> create(
            @RequestBody @Valid TransactionRequestDTO dto,
            @AuthenticationPrincipal User user) {
        // O service agora retorna uma Lista, pois uma compra em 12x gera 12 registros
        List<TransactionResponseDTO> newTransactions = service.create(dto, user);

        return ResponseEntity.status(HttpStatus.CREATED).body(newTransactions);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid TransactionUpdateDTO dto,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.update(id, dto, user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        service.delete(id, user);
        // 204 No Content: sucesso sem corpo de resposta — padrão REST para DELETE
        return ResponseEntity.noContent().build();
    }
}