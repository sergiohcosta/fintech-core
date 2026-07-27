package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportBatchStatus;
import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.domain.enums.StagedTransactionStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.imports.ImportBatch;
import com.fintech.api.domain.imports.StagedFieldValue;
import com.fintech.api.domain.imports.StagedTransaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.imports.ImportBatchResponseDTO;
import com.fintech.api.dto.imports.ImportCommitRequestDTO;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedCommitItemDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.dto.imports.StagedPatchDTO;
import com.fintech.api.dto.imports.StagedTransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.exception.BusinessException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.ImportBatchRepository;
import com.fintech.api.repository.StagedTransactionRepository;
import com.fintech.api.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orquestra o pipeline de importação. Fase 0: staging + consulta (mock). Fase 1: extração real
 * de imagem, edição de staged e promoção (commit) para {@code Transaction}.
 *
 * <p>Todo método recebe o {@link User} autenticado e filtra por {@code user.getTenant()} —
 * o tenant NUNCA vem de parâmetro do cliente (invariante nº1 do projeto).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportService {

    private final ImportBatchRepository batchRepository;
    private final StagedTransactionRepository stagedRepository;
    private final TransactionExtractor extractor;
    // Reusa o CAMINHO de criação de transação existente (fatura de cartão, parcelas, tenant) —
    // o commit não reimplementa regra de lançamento nenhuma (spec §6.5).
    private final TransactionService transactionService;

    // Thresholds que definem quando uma transação exige olho humano. Ficam em properties para
    // o PRODUTO controlar a régua sem retreinar/reprompt-ar nada (spec §2.f). O valor de
    // amount é mais crítico → threshold mais rígido.
    @Value("${import.review.overall-threshold:0.90}")
    private BigDecimal overallThreshold;

    @Value("${import.review.amount-threshold:0.95}")
    private BigDecimal amountThreshold;

    @Value("${import.vision.extractor-version:unknown}")
    private String extractorVersion;

    // ---------------------------------------------------------------------------------------
    // Fase 1 — upload de imagem
    // ---------------------------------------------------------------------------------------

    /**
     * Extrai um comprovante (imagem) e grava o batch + a staging. Falha de extração NÃO derruba
     * o usuário: grava um batch {@code FAILED} e o frontend oferece o formulário manual (§6).
     */
    @Transactional
    public ImportBatchResponseDTO createFromImage(byte[] imageBytes, String mimeType, ImportMode mode, User user) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException("Arquivo de imagem vazio.");
        }
        if (mimeType == null || !mimeType.toLowerCase().startsWith("image/")) {
            throw new BusinessException("Tipo de arquivo não suportado: envie uma imagem (image/*).");
        }
        ImportMode effectiveMode = (mode == null) ? ImportMode.NEW_TRANSACTIONS : mode;

        try {
            NormalizedBatchDTO normalized = extractor.extract(imageBytes, mimeType, effectiveMode);
            return createBatch(normalized, user);
        } catch (Exception e) {
            // ExtractionException (guarda-corpo/provider) OU qualquer erro inesperado → FAILED.
            // O batch FAILED é registrado (proveniência) e o fallback manual assume no frontend.
            log.error("Extração de imagem falhou; gravando batch FAILED. tenant={}, causa={}",
                    user.getTenant().getId(), e.getMessage());
            ImportBatch failed = batchRepository.save(ImportBatch.builder()
                    .tenant(user.getTenant())
                    .createdBy(user)
                    .importMode(effectiveMode)
                    .sourceType(ImportSourceType.IMAGE)
                    .extractorUsed("vision_ollama")
                    .extractorVersion(extractorVersion)
                    .status(ImportBatchStatus.FAILED)
                    .failureReason(failureReasonFor(e))
                    .build());
            return ImportBatchResponseDTO.fromEntity(failed);
        }
    }

    /**
     * Mensagem de falha exibida ao usuário. Só a {@link ExtractionException} é repassada — ela é
     * redigida por nós, em PT-BR, para o usuário final. Qualquer outra exceção (timeout do
     * provider, NPE, erro de parse) carrega detalhe interno na mensagem e vira texto genérico:
     * a borda da API não é lugar para mensagem de infra vazar (#193).
     */
    private String failureReasonFor(Exception e) {
        return (e instanceof ExtractionException && e.getMessage() != null)
                ? e.getMessage()
                : "Não foi possível ler esta imagem. Ela pode estar ilegível ou o serviço de "
                        + "extração pode estar indisponível.";
    }

    // ---------------------------------------------------------------------------------------
    // Fase 0 — criação a partir de batch normalizado (mock e caminho comum da extração)
    // ---------------------------------------------------------------------------------------

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
                // O batch chega "extraído". Aqui vira EXTRACTED; FAILED é o caminho de exceção acima.
                .status(ImportBatchStatus.EXTRACTED)
                .build());

        for (NormalizedTransactionDTO tx : batch.transactions()) {
            Map<String, StagedFieldValue> fields = toFieldsMap(tx.fields());
            stagedRepository.save(StagedTransaction.builder()
                    .batch(entity)
                    .tenant(user.getTenant())  // tenant denormalizado — defesa nº1 (spec §3)
                    .fields(fields)
                    .suggestedCategoryCode(tx.suggestedCategoryCode())
                    .suggestedCategoryConfidence(tx.suggestedCategoryConfidence())
                    .overallConfidence(tx.overallConfidence())
                    .requiresReview(deriveRequiresReview(fields, tx.overallConfidence()))
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

    // ---------------------------------------------------------------------------------------
    // Fase 1 — edição de staged antes de lançar
    // ---------------------------------------------------------------------------------------

    /**
     * Aplica correções do usuário a uma staged PENDING antes do commit. Cada campo editado passa
     * a ter confiança 1.0 (dado confirmado por humano) e o {@code requiresReview} é re-derivado.
     */
    @Transactional
    public StagedTransactionResponseDTO patchStaged(UUID batchId, UUID stagedId, StagedPatchDTO patch, User user) {
        findBatch(batchId, user);  // valida tenant/existência do batch (404)
        StagedTransaction staged = findStaged(stagedId, batchId, user);
        if (staged.getStatus() != StagedTransactionStatus.PENDING) {
            throw new BusinessException("Só é possível editar uma staged pendente.");
        }

        if (patch.fields() != null && !patch.fields().isEmpty()) {
            Map<String, StagedFieldValue> fields = staged.getFields() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(staged.getFields());
            // Valor vindo do humano = confiança 1.0 (deixou de ser probabilístico).
            patch.fields().forEach((key, value) -> fields.put(key, new StagedFieldValue(value, BigDecimal.ONE)));
            staged.setFields(fields);
            // Editou → re-deriva o requires_review com a nova confiança (amount editado pode zerá-lo).
            staged.setRequiresReview(deriveRequiresReview(fields, staged.getOverallConfidence()));
        }
        if (patch.suggestedCategoryCode() != null) {
            staged.setSuggestedCategoryCode(patch.suggestedCategoryCode());
        }

        return StagedTransactionResponseDTO.fromEntity(staged);
    }

    // ---------------------------------------------------------------------------------------
    // Fase 1 — commit (promoção staged → Transaction)
    // ---------------------------------------------------------------------------------------

    /**
     * Promove as staged listadas a {@code Transaction}, reusando o caminho de criação existente.
     * Quando não sobra nenhuma staged PENDING no batch, marca o batch como {@code COMMITTED}.
     */
    @Transactional
    public ImportBatchResponseDTO commit(UUID batchId, ImportCommitRequestDTO request, User user) {
        ImportBatch batch = findBatch(batchId, user);

        for (StagedCommitItemDTO item : request.items()) {
            StagedTransaction staged = findStaged(item.stagedId(), batchId, user);
            if (staged.getStatus() != StagedTransactionStatus.PENDING) {
                throw new BusinessException("Staged " + staged.getId() + " não está pendente para lançamento.");
            }

            // Guarda-corpo de sanidade (§6.1): valida os valores ATUAIS (originais ou já editados).
            BigDecimal amount = fieldValue(staged, "amount", this::toBigDecimal);
            if (amount == null || amount.compareTo(new BigDecimal("0.01")) < 0) {
                throw new BusinessException("Valor inválido na staged " + staged.getId() + " — corrija antes de lançar.");
            }
            LocalDate date = fieldValue(staged, "transaction_date", this::toLocalDate);
            if (date == null) {
                throw new BusinessException("Data ausente/inválida na staged " + staged.getId() + " — corrija antes de lançar.");
            }
            String direction = fieldValue(staged, "direction", this::toStr);
            TransactionType type = "credit".equalsIgnoreCase(direction)
                    ? TransactionType.INCOME : TransactionType.EXPENSE;
            String description = fieldValue(staged, "description", this::toStr);
            if (description == null || description.isBlank()) {
                description = "Importado de comprovante";
            }

            // status = null → create() aplica o default (PENDING), mesma semântica de um lançamento manual.
            TransactionRequestDTO dto = new TransactionRequestDTO(
                    description, amount, date, type, (TransactionStatus) null, null, item.categoryId(), item.accountId());
            List<TransactionResponseDTO> created = transactionService.create(dto, user);
            TransactionResponseDTO tx = created.get(0);

            staged.setPromotedTransactionId(tx.id());
            staged.setStatus(StagedTransactionStatus.CONFIRMED);
        }

        // Batch vira COMMITTED quando nenhuma staged está mais PENDING (todas resolvidas).
        boolean anyPending = stagedRepository
                .findAllByBatch_IdAndTenantOrderByCreatedAt(batchId, user.getTenant())
                .stream()
                .anyMatch(s -> s.getStatus() == StagedTransactionStatus.PENDING);
        if (!anyPending) {
            batch.setStatus(ImportBatchStatus.COMMITTED);
        }

        return ImportBatchResponseDTO.fromEntity(batch);
    }

    // ---------------------------------------------------------------------------------------
    // Internos
    // ---------------------------------------------------------------------------------------

    private ImportBatch findBatch(UUID id, User user) {
        return batchRepository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Batch de importação não encontrado."));
    }

    private StagedTransaction findStaged(UUID stagedId, UUID batchId, User user) {
        StagedTransaction staged = stagedRepository.findByIdAndTenant(stagedId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Transação em staging não encontrada."));
        // Consistência: a staged tem que pertencer AO batch da rota (evita cruzar staged entre batches).
        if (!staged.getBatch().getId().equals(batchId)) {
            throw new EntityNotFoundException("Transação em staging não encontrada.");
        }
        return staged;
    }

    /**
     * Regra de review: exige olho humano se a confiança agregada não bate o threshold geral, OU
     * se a confiança do campo mais crítico (amount) não bate o threshold de valor. Ausência de
     * confiança (null) conta como "duvidoso" → exige review. Nunca confia num {@code requiresReview}
     * vindo do modelo/cliente.
     */
    private boolean deriveRequiresReview(Map<String, StagedFieldValue> fields, BigDecimal overallConfidence) {
        if (isBelow(overallConfidence, overallThreshold)) {
            return true;
        }
        StagedFieldValue amount = fields == null ? null : fields.get("amount");
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

    // Coerção robusta do valor do JSONB (round-trip Hibernate/Jackson ou edição do usuário):
    // um número pode voltar como Double, BigDecimal, Integer ou String — normalizamos todos.
    private <T> T fieldValue(StagedTransaction staged, String key, Function<Object, T> coercer) {
        Map<String, StagedFieldValue> fields = staged.getFields();
        if (fields == null) {
            return null;
        }
        StagedFieldValue v = fields.get(key);
        return v == null ? null : coercer.apply(v.value());
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private LocalDate toLocalDate(Object v) {
        if (v instanceof String s && !s.isBlank()) {
            try {
                return LocalDate.parse(s.trim());
            } catch (java.time.format.DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private String toStr(Object v) {
        return v == null ? null : v.toString();
    }
}
