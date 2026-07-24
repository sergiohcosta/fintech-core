package com.fintech.api.dto.imports;

import com.fintech.api.domain.enums.StagedTransactionStatus;
import com.fintech.api.domain.imports.StagedFieldValue;
import com.fintech.api.domain.imports.StagedTransaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resposta de uma transação em staging. {@code requiresReview}/confidence vêm calculados do
 * backend — a UI só exibe, nunca recalcula o threshold.
 */
public record StagedTransactionResponseDTO(
        UUID id,
        UUID batchId,
        Map<String, StagedFieldValueDTO> fields,
        String suggestedCategoryCode,
        BigDecimal suggestedCategoryConfidence,
        BigDecimal overallConfidence,
        boolean requiresReview,
        UUID duplicateCandidateOf,
        UUID promotedTransactionId,
        StagedTransactionStatus status,
        LocalDateTime createdAt) {

    public static StagedTransactionResponseDTO fromEntity(StagedTransaction s) {
        Map<String, StagedFieldValueDTO> fields = s.getFields() == null ? Map.of()
                : s.getFields().entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> toFieldDto(e.getValue())));
        return new StagedTransactionResponseDTO(
                s.getId(), s.getBatch().getId(), fields,
                s.getSuggestedCategoryCode(), s.getSuggestedCategoryConfidence(),
                s.getOverallConfidence(), s.isRequiresReview(),
                s.getDuplicateCandidateOf(), s.getPromotedTransactionId(),
                s.getStatus(), s.getCreatedAt());
    }

    private static StagedFieldValueDTO toFieldDto(StagedFieldValue v) {
        return v == null ? null : new StagedFieldValueDTO(v.value(), v.confidence());
    }
}
