package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportBatchStatus;
import com.fintech.api.domain.enums.StagedTransactionStatus;
import com.fintech.api.domain.imports.ImportBatch;
import com.fintech.api.domain.imports.StagedFieldValue;
import com.fintech.api.domain.imports.StagedTransaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.imports.ImportBatchResponseDTO;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.dto.imports.StagedTransactionResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.ImportBatchRepository;
import com.fintech.api.repository.StagedTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orquestra o pipeline de importação (Fase 0: staging + consulta; sem extrator real).
 *
 * <p>Todo método recebe o {@link User} autenticado e filtra por {@code user.getTenant()} —
 * o tenant NUNCA vem de parâmetro do cliente (invariante nº1 do projeto).
 */
@Service
@RequiredArgsConstructor
public class ImportService {

    private final ImportBatchRepository batchRepository;
    private final StagedTransactionRepository stagedRepository;

    // Thresholds que definem quando uma transação exige olho humano. Ficam em properties para
    // o PRODUTO controlar a régua sem retreinar/reprompt-ar nada (spec §2.f). O valor de
    // amount é mais crítico → threshold mais rígido.
    @Value("${import.review.overall-threshold:0.90}")
    private BigDecimal overallThreshold;

    @Value("${import.review.amount-threshold:0.95}")
    private BigDecimal amountThreshold;

    /**
     * Grava um batch (já "extraído") + suas transações em staging. Deriva {@code requiresReview}
     * no código por threshold — o campo homônimo do DTO de entrada é ignorado de propósito.
     */
    @Transactional
    public ImportBatchResponseDTO createBatch(NormalizedBatchDTO batch, User user) {
        ImportBatch entity = batchRepository.save(ImportBatch.builder()
                .tenant(user.getTenant())
                .createdBy(user)
                .importMode(batch.importMode())
                .sourceType(batch.sourceType())
                .extractorUsed(batch.extractorUsed())
                .extractorVersion(batch.extractorVersion())
                // Mock: o batch já chega "extraído". Na Fase 1 quem seta EXTRACTED/FAILED é o extrator.
                .status(ImportBatchStatus.EXTRACTED)
                .build());

        for (NormalizedTransactionDTO tx : batch.transactions()) {
            stagedRepository.save(StagedTransaction.builder()
                    .batch(entity)
                    .tenant(user.getTenant())  // tenant denormalizado — defesa nº1 (spec §3)
                    .fields(toFieldsMap(tx.fields()))
                    .suggestedCategoryCode(tx.suggestedCategoryCode())
                    .suggestedCategoryConfidence(tx.suggestedCategoryConfidence())
                    .overallConfidence(tx.overallConfidence())
                    .requiresReview(deriveRequiresReview(tx))
                    .duplicateCandidateOf(tx.duplicateCandidateOf())
                    .status(StagedTransactionStatus.PENDING)
                    .build());
        }

        return ImportBatchResponseDTO.fromEntity(entity);
    }

    @Transactional(readOnly = true)
    public ImportBatchResponseDTO getBatch(UUID id, User user) {
        return ImportBatchResponseDTO.fromEntity(findBatch(id, user));
    }

    @Transactional(readOnly = true)
    public List<StagedTransactionResponseDTO> listStaged(UUID batchId, User user) {
        // Confirma que o batch existe E pertence ao tenant (404 caso contrário) antes de listar —
        // recurso de outro tenant responde 404, não confirma existência (invariante nº1).
        findBatch(batchId, user);
        return stagedRepository
                .findAllByBatch_IdAndTenantOrderByCreatedAt(batchId, user.getTenant())
                .stream()
                .map(StagedTransactionResponseDTO::fromEntity)
                .toList();
    }

    private ImportBatch findBatch(UUID id, User user) {
        return batchRepository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Batch de importação não encontrado."));
    }

    /**
     * Regra de review: exige olho humano se a confiança agregada não bate o threshold geral, OU
     * se a confiança do campo mais crítico (amount) não bate o threshold de valor. Ausência de
     * confiança (null) conta como "duvidoso" → exige review. Nunca confia num {@code requiresReview}
     * vindo do modelo/cliente.
     */
    private boolean deriveRequiresReview(NormalizedTransactionDTO tx) {
        if (isBelow(tx.overallConfidence(), overallThreshold)) {
            return true;
        }
        StagedFieldValueDTO amount = tx.fields() == null ? null : tx.fields().get("amount");
        BigDecimal amountConfidence = amount == null ? null : amount.confidence();
        return isBelow(amountConfidence, amountThreshold);
    }

    /** true se {@code value} é null ou estritamente menor que {@code threshold}. */
    private boolean isBelow(BigDecimal value, BigDecimal threshold) {
        return value == null || value.compareTo(threshold) < 0;
    }

    private Map<String, StagedFieldValue> toFieldsMap(Map<String, StagedFieldValueDTO> dto) {
        if (dto == null) {
            return Map.of();
        }
        return dto.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> new StagedFieldValue(e.getValue().value(), e.getValue().confidence())));
    }
}
