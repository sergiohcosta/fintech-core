package com.fintech.api.dto.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * "Batch normalizado" de ENTRADA do {@code createBatch}: o pacote inteiro que um extrator
 * produziu (proveniência + lista de transações no schema normalizado). Na Fase 0 é o corpo do
 * endpoint {@code POST /api/imports/mock}, que prova o pipeline ponta a ponta sem extrator real.
 *
 * <p>{@code source} do §5.2 (nested {@code {type, extractor_used, extractor_version}}) é achatado
 * aqui para bater 1:1 com as colunas de {@code import_batches}.
 */
public record NormalizedBatchDTO(
        @NotNull ImportMode importMode,
        @NotNull ImportSourceType sourceType,
        String extractorUsed,
        String extractorVersion,
        @NotNull @Valid List<NormalizedTransactionDTO> transactions) {}
