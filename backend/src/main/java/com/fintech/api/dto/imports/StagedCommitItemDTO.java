package com.fintech.api.dto.imports;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Instrução de commit de UMA staged: em qual conta lançar e (opcional) qual categoria.
 *
 * <p>{@code accountId} é obrigatório porque o schema normalizado NÃO carrega conta — a extração
 * não sabe (nem deveria adivinhar) em qual conta do tenant o lançamento cai (spec §6.3). A conta
 * é escolha do usuário na tela de revisão. {@code categoryId} é opcional (o usuário pode deixar
 * a transação sem categoria e ajustar depois).
 */
public record StagedCommitItemDTO(
        @NotNull(message = "O id da staged é obrigatório") UUID stagedId,
        @NotNull(message = "A conta é obrigatória") UUID accountId,
        UUID categoryId) {}
