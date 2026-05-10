package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    @PostMapping
    public ResponseEntity<List<TransactionResponseDTO>> create(
            @RequestBody @Valid TransactionRequestDTO dto,
            @AuthenticationPrincipal User user) {
        // O service agora retorna uma Lista, pois uma compra em 12x gera 12 registros
        List<TransactionResponseDTO> newTransactions = service.create(dto, user);

        // Retornamos 201 Created com o corpo contendo todas as parcelas geradas
        return ResponseEntity.status(HttpStatus.CREATED).body(newTransactions);
    }
}