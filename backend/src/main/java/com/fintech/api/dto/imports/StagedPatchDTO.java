package com.fintech.api.dto.imports;

import java.util.Map;

/**
 * Corpo do {@code PATCH /api/imports/{id}/staged/{stagedId}}: correções que o usuário faz na
 * revisão ANTES de lançar (o modelo leu errado o valor, a data etc.).
 *
 * <p>{@code fields} é um mapa parcial {@code {nomeDoCampo: novoValor}} (só o valor — sem
 * confiança). Quando o humano edita um campo, o backend grava confiança <b>1.0</b> para ele:
 * o dado deixou de ser probabilístico, foi confirmado por uma pessoa. Isso pode inclusive
 * derrubar o {@code requires_review} (o backend o re-deriva após o patch).
 *
 * <p>Ambos os campos são opcionais — um PATCH pode mexer só em {@code fields}, só na sugestão
 * de categoria, ou em ambos.
 */
public record StagedPatchDTO(
        Map<String, Object> fields,
        String suggestedCategoryCode) {}
