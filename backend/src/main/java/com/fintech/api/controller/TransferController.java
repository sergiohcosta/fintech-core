package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.dto.transfer.TransferResponseDTO;
import com.fintech.api.openapi.TransfersApi;
import com.fintech.api.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController implements TransfersApi {

    private final TransactionService service;

    @Override
    @PostMapping
    public ResponseEntity<TransferResponseDTO> createTransfer(
            @RequestBody @Valid TransferRequestDTO transferRequestDTO) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createTransfer(transferRequestDTO, getAuthenticatedUser()));
    }

    @Override
    @DeleteMapping("/{transferId}")
    public ResponseEntity<Void> deleteTransfer(@PathVariable UUID transferId) {
        service.deleteTransfer(transferId, getAuthenticatedUser());
        return ResponseEntity.noContent().build();
    }

    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
