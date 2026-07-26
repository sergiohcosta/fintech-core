package com.fintech.api.dto.imports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Corpo do {@code POST /api/imports/{id}/commit}: a lista de staged a promover, cada uma com a
 * conta (e categoria opcional) escolhidas pelo usuário. Só as staged listadas aqui são
 * promovidas; as demais permanecem em staging.
 */
public record ImportCommitRequestDTO(
        @NotEmpty(message = "Informe ao menos uma staged para lançar") @Valid List<StagedCommitItemDTO> items) {}
