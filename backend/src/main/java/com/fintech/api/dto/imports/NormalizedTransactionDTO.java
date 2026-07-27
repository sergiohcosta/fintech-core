package com.fintech.api.dto.imports;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Uma transação no schema NORMALIZADO (roadmap §1.3) — o formato para o qual todo extrator
 * converge (visão, CSV, OFX, PDF), desacoplando extração de regra de negócio. Na Fase 1 será
 * a saída TIPADA que o {@code VisionExtractor} pede ao {@code ChatClient}.
 *
 * <p>{@code fields}: {@code {value, confidence}} por campo, keyed pelo nome (amount, currency,
 * transaction_date, posting_date, description, direction, payment_method) — mapa flexível para
 * não enrijecer o schema a cada campo novo do contrato.
 *
 * <p>{@code suggestedCategory} (§5.2 nested {@code {value, confidence, source}}) é achatada aqui
 * em {@code code + confidence}, que é exatamente o que as colunas {@code suggested_category_code}
 * / {@code suggested_category_confidence} guardam na Fase 0. {@code source} (heurística) é
 * preocupação da Fase 4.
 *
 * <p>{@code requiresReview} aqui é informativo/ignorado no create: o backend DERIVA o valor por
 * threshold (spec §2.f), nunca confia no que veio de fora.
 */
public record NormalizedTransactionDTO(
        UUID transactionId,
        @NotNull Map<String, StagedFieldValueDTO> fields,
        String suggestedCategoryCode,
        BigDecimal suggestedCategoryConfidence,
        BigDecimal overallConfidence,
        Boolean requiresReview,
        UUID duplicateCandidateOf) {}
