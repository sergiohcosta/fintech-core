package com.fintech.api.controller;

import com.fintech.api.config.SecurityUtils;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.imports.ImportBatchResponseDTO;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.StagedTransactionResponseDTO;
import com.fintech.api.openapi.ImportsApi;
import com.fintech.api.service.imports.ImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints de importação (Fase 0). Controller FINO: delega imediatamente ao {@link ImportService}.
 * Sob {@code .anyRequest().authenticated()} — import não é operação restrita a ADMIN, nada muda
 * em {@code SecurityConfigurations}.
 */
@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
public class ImportController implements ImportsApi {

    private final ImportService importService;

    @Override
    @PostMapping("/mock")
    public ResponseEntity<ImportBatchResponseDTO> createMockImport(@Valid @RequestBody NormalizedBatchDTO body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(importService.createBatch(body, getAuthenticatedUser()));
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<ImportBatchResponseDTO> getImport(@PathVariable UUID id) {
        return ResponseEntity.ok(importService.getBatch(id, getAuthenticatedUser()));
    }

    @Override
    @GetMapping("/{id}/staged")
    public ResponseEntity<List<StagedTransactionResponseDTO>> listImportStaged(@PathVariable UUID id) {
        return ResponseEntity.ok(importService.listStaged(id, getAuthenticatedUser()));
    }

    private User getAuthenticatedUser() {
        return SecurityUtils.currentUser();
    }
}
