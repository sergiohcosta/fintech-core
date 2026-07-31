package com.fintech.api.controller;

import com.fintech.api.config.SecurityUtils;
import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.imports.ImportBatchResponseDTO;
import com.fintech.api.dto.imports.ImportCommitRequestDTO;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.StagedPatchDTO;
import com.fintech.api.dto.imports.StagedTransactionResponseDTO;
import com.fintech.api.exception.BusinessException;
import com.fintech.api.openapi.ImportsApi;
import com.fintech.api.service.imports.ExtractionInput;
import com.fintech.api.service.imports.ImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Endpoints de importação. Controller FINO: delega imediatamente ao {@link ImportService}.
 * Sob {@code .anyRequest().authenticated()} — import não é operação restrita a ADMIN, nada muda
 * em {@code SecurityConfigurations}.
 */
@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
public class ImportController implements ImportsApi {

    private final ImportService importService;

    // --- Fase 1/2 — extração real (imagem, CSV, OFX...) ---

    @Override
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportBatchResponseDTO> createImport(MultipartFile file, ImportMode importMode, Boolean force) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // Falha ao ler o multipart é erro do cliente (arquivo corrompido/incompleto) → 400.
            throw new BusinessException("Não foi possível ler o arquivo enviado.");
        }
        // Formato não reconhecido é responsabilidade do ExtractionRouter (por conteúdo, não pelo
        // Content-Type do browser — ele mente); o controller só repassa a proveniência.
        ExtractionInput input = new ExtractionInput(bytes, file.getOriginalFilename(), file.getContentType(), importMode);
        boolean effectiveForce = Boolean.TRUE.equals(force);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(importService.createFromFile(input, effectiveForce, getAuthenticatedUser()));
    }

    @Override
    @PatchMapping("/{id}/staged/{stagedId}")
    public ResponseEntity<StagedTransactionResponseDTO> patchImportStaged(
            @PathVariable UUID id, @PathVariable UUID stagedId, @Valid @RequestBody StagedPatchDTO stagedPatchDTO) {
        return ResponseEntity.ok(importService.patchStaged(id, stagedId, stagedPatchDTO, getAuthenticatedUser()));
    }

    @Override
    @PostMapping("/{id}/staged/{stagedId}/discard")
    public ResponseEntity<StagedTransactionResponseDTO> discardImportStaged(
            @PathVariable UUID id, @PathVariable UUID stagedId) {
        return ResponseEntity.ok(importService.discardStaged(id, stagedId, getAuthenticatedUser()));
    }

    @Override
    @PostMapping("/{id}/commit")
    public ResponseEntity<ImportBatchResponseDTO> commitImport(
            @PathVariable UUID id, @Valid @RequestBody ImportCommitRequestDTO importCommitRequestDTO) {
        return ResponseEntity.ok(importService.commit(id, importCommitRequestDTO, getAuthenticatedUser()));
    }

    // --- Fase 0 — mock + consultas ---

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
