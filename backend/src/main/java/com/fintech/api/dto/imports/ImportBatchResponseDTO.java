package com.fintech.api.dto.imports;

import com.fintech.api.domain.enums.ImportBatchStatus;
import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.domain.imports.ImportBatch;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Resposta de um batch de importação. Não expõe {@code tenantId} (o recurso já é escopado por
 * tenant — expor seria ruído/vazamento), seguindo o padrão de {@code AccountResponseDTO}.
 */
public record ImportBatchResponseDTO(
        UUID id,
        ImportMode importMode,
        ImportSourceType sourceType,
        String extractorUsed,
        String extractorVersion,
        ImportBatchStatus status,
        String failureReason,
        String sourceFilename,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ImportBatchResponseDTO fromEntity(ImportBatch b) {
        return new ImportBatchResponseDTO(
                b.getId(), b.getImportMode(), b.getSourceType(),
                b.getExtractorUsed(), b.getExtractorVersion(), b.getStatus(),
                b.getFailureReason(), b.getSourceFilename(), b.getCreatedAt(), b.getUpdatedAt());
    }
}
