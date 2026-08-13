package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportBatchStatus;
import com.fintech.api.domain.enums.ImportMode;
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
import com.fintech.api.exception.DuplicateImportException;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
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
    private final ExtractionRouter extractionRouter;
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

    // Teto de transações por arquivo — um CSV/OFX gigante não deve travar o commit nem virar um
    // batch impraticável de revisar. Property (não hardcode) pro produto ajustar sem redeploy.
    @Value("${import.file.max-transactions:500}")
    private int maxTransactions;

    // ---------------------------------------------------------------------------------------
    // Fase 1/2 — upload de arquivo (imagem, CSV, OFX...)
    // ---------------------------------------------------------------------------------------

    /**
     * Extrai um arquivo e grava o batch + a staging. O {@link ExtractionRouter} escolhe o extrator
     * por CONTEÚDO — este método não sabe (nem precisa saber) qual formato é. Formato que NENHUM
     * extrator reconhece propaga {@link BusinessException} (400, nenhum batch gravado — não há
     * {@code sourceType} para persistir). Falha de um extrator que RECONHECEU o arquivo mas não
     * conseguiu processá-lo NÃO derruba o usuário: grava um batch {@code FAILED} e o frontend
     * oferece o formulário manual (§6).
     *
     * <p>{@code force}: dedup por arquivo (Onda 4) — o MESMO arquivo (por {@code sha256}) já
     * importado antes por este tenant vira {@link DuplicateImportException} (409), a menos que o
     * chamador passe {@code force=true} (reimportar é caso legítimo: extrato corrigido, etc.).
     */
    @Transactional
    public ImportBatchResponseDTO createFromFile(ExtractionInput input, boolean force, User user) {
        if (input.content() == null || input.content().length == 0) {
            throw new BusinessException("Arquivo vazio.");
        }
        ImportMode effectiveMode = (input.mode() == null) ? ImportMode.NEW_TRANSACTIONS : input.mode();
        ExtractionInput effectiveInput = new ExtractionInput(input.content(), input.filename(), input.mimeType(), effectiveMode);

        // Hash ANTES de extrair — não gasta compute de extração pra descobrir que era repetido.
        String sourceHash = sha256Hex(input.content());
        if (!force) {
            batchRepository.findFirstByTenantAndSourceHashOrderByCreatedAtDesc(user.getTenant(), sourceHash)
                    .ifPresent(existing -> { throw new DuplicateImportException(existing); });
        }

        // route() PODE lançar BusinessException (nenhum extrator reconhece o formato) — propaga
        // direto como 400, sem passar pelo catch abaixo (não há sourceType para gravar um batch).
        TransactionExtractor extractor = extractionRouter.route(effectiveInput);

        try {
            NormalizedBatchDTO normalized = extractor.extract(effectiveInput);
            return createBatch(normalized, sourceHash, input.filename(), user);
        } catch (Exception e) {
            // ExtractionException (guarda-corpo/provider) OU qualquer erro inesperado → FAILED.
            // O batch FAILED é registrado (proveniência) e o fallback manual assume no frontend.
            log.error("Extração de arquivo falhou; gravando batch FAILED. tenant={}, extrator={}, causa={}",
                    user.getTenant().getId(), extractor.getClass().getSimpleName(), e.getMessage());
            ImportBatch failed = batchRepository.save(ImportBatch.builder()
                    .tenant(user.getTenant())
                    .createdBy(user)
                    .importMode(effectiveMode)
                    .sourceType(extractor.sourceType())
                    .extractorUsed(extractor.getClass().getSimpleName())
                    .extractorVersion(extractor.extractorVersion())
                    .sourceHash(sourceHash)
                    .sourceFilename(input.filename())
                    .status(ImportBatchStatus.FAILED)
                    .failureReason(failureReasonFor(e))
                    .build());
            return ImportBatchResponseDTO.fromEntity(failed);
        }
    }

    /** SHA-256 em hex — sempre disponível na JVM (algoritmo padrão do {@code MessageDigest}). */
    private String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM.", e);
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
     * Sem arquivo de origem (mock/Fase 0) — {@code sourceHash}/{@code sourceFilename} nulos.
     */
    @Transactional
    public ImportBatchResponseDTO createBatch(NormalizedBatchDTO batch, User user) {
        return createBatch(batch, null, null, user);
    }

    /**
     * Caminho comum a TODOS os extratores (imagem, OFX, CSV...) — guarda-corpo de sanidade e
     * dedup intra-batch vivem aqui, não em cada extrator, pra não duplicar regra por formato.
     */
    @Transactional
    public ImportBatchResponseDTO createBatch(NormalizedBatchDTO batch, String sourceHash, String sourceFilename, User user) {
        // Sanidade nº1: arquivo gigante não vira um batch impraticável de revisar/commitar.
        if (batch.transactions().size() > maxTransactions) {
            return saveFailedBatch(batch, sourceHash, sourceFilename, user,
                    "Arquivo tem " + batch.transactions().size() + " transações — acima do limite de "
                            + maxTransactions + " suportado. Divida o arquivo em partes menores.");
        }

        // Sanidade nº2 (comum a todos os extratores): data fora de [hoje-10a, hoje+1a] ou valor
        // ausente/zero zera a CONFIANÇA do campo (nunca descarta a linha) — cada extrator já faz
        // sua própria checagem de formato; esta é a rede de segurança central, igual pra todos.
        List<Map<String, StagedFieldValue>> allFields = batch.transactions().stream()
                .map(tx -> applyCentralSanity(toFieldsMap(tx.fields())))
                .toList();

        // Sanidade nº3: zero linhas aproveitáveis (nenhum amount legível) → falha explícita, não
        // um batch vazio ou cheio de lixo pro usuário revisar linha por linha. Reusa o resultado
        // da sanidade acima: confiança > 0 em amount SÓ sobrevive se o valor era parseável e ≠ 0.
        boolean anyUsable = allFields.stream().anyMatch(fields -> {
            StagedFieldValue amount = fields.get("amount");
            return amount != null && amount.confidence() != null && amount.confidence().signum() > 0;
        });
        if (!anyUsable) {
            return saveFailedBatch(batch, sourceHash, sourceFilename, user,
                    "Nenhuma transação aproveitável foi encontrada neste arquivo.");
        }

        ImportBatch entity = batchRepository.save(ImportBatch.builder()
                .tenant(user.getTenant())
                .createdBy(user)
                .importMode(batch.importMode())
                .sourceType(batch.sourceType())
                .extractorUsed(batch.extractorUsed())
                .extractorVersion(batch.extractorVersion())
                .sourceHash(sourceHash)
                .sourceFilename(sourceFilename)
                // Proveniência estruturada (V28): o extrator MEDE (provider/modelo/latência/
                // fallback), o service GRAVA — mesma fronteira de sempre (extrator não toca banco).
                .extractorProvider(batch.extractorProvider())
                .extractorModel(batch.extractorModel())
                .extractionLatencyMs(batch.extractionLatencyMs())
                .fallbackFrom(batch.fallbackFrom())
                .fallbackReason(batch.fallbackReason())
                .targetInvoiceReferenceYear(batch.targetInvoiceReferenceYear())
                .targetInvoiceReferenceMonth(batch.targetInvoiceReferenceMonth())
                // O batch chega "extraído". Aqui vira EXTRACTED; FAILED é o caminho de exceção acima.
                .status(ImportBatchStatus.EXTRACTED)
                .build());

        // Dedup INTRA-batch: FITID repetido (OFX, chave forte) ou trio data+valor+descrição
        // (CSV, sem identificador único) → a segunda ocorrência aponta pra primeira. NENHUMA
        // linha é descartada — quem decide o que fazer com a duplicata é o usuário na revisão.
        Map<String, UUID> seenDedupKeys = new HashMap<>();
        List<NormalizedTransactionDTO> transactions = batch.transactions();
        for (int i = 0; i < transactions.size(); i++) {
            NormalizedTransactionDTO tx = transactions.get(i);
            Map<String, StagedFieldValue> fields = allFields.get(i);
            String dedupKey = dedupKey(fields);
            UUID duplicateOf = dedupKey != null ? seenDedupKeys.get(dedupKey) : null;

            StagedTransaction saved = stagedRepository.save(StagedTransaction.builder()
                    .batch(entity)
                    .tenant(user.getTenant())  // tenant denormalizado — defesa nº1 (spec §3)
                    .fields(fields)
                    .suggestedCategoryCode(tx.suggestedCategoryCode())
                    .suggestedCategoryConfidence(tx.suggestedCategoryConfidence())
                    .overallConfidence(tx.overallConfidence())
                    // #194 — tx.requiresReview() é PISO, nunca teto: extratores existentes
                    // (CSV/OFX/comprovante) mandam null aqui (Boolean.TRUE.equals(null)=false),
                    // então o OR cai inteiro no cálculo por confiança de sempre, comportamento
                    // bit-a-bit idêntico. Só o caminho novo de extrato (VisionExtractor,
                    // mapStatementLine) manda true — staged rollout incondicional, spec §2.d.
                    .requiresReview(Boolean.TRUE.equals(tx.requiresReview())
                            || deriveRequiresReview(fields, tx.overallConfidence()))
                    .duplicateCandidateOf(duplicateOf)
                    .status(StagedTransactionStatus.PENDING)
                    .build());

            if (dedupKey != null) {
                seenDedupKeys.putIfAbsent(dedupKey, saved.getId());
            }
        }

        return ImportBatchResponseDTO.fromEntity(entity);
    }

    private ImportBatchResponseDTO saveFailedBatch(
            NormalizedBatchDTO batch, String sourceHash, String sourceFilename, User user, String reason) {
        ImportBatch failed = batchRepository.save(ImportBatch.builder()
                .tenant(user.getTenant())
                .createdBy(user)
                .importMode(batch.importMode())
                .sourceType(batch.sourceType())
                .extractorUsed(batch.extractorUsed())
                .extractorVersion(batch.extractorVersion())
                .sourceHash(sourceHash)
                .sourceFilename(sourceFilename)
                // A extração em si terminou (o extrator devolveu o batch) — a proveniência que ele
                // mediu continua válida mesmo que a sanidade CENTRAL derrube o batch depois.
                .extractorProvider(batch.extractorProvider())
                .extractorModel(batch.extractorModel())
                .extractionLatencyMs(batch.extractionLatencyMs())
                .fallbackFrom(batch.fallbackFrom())
                .fallbackReason(batch.fallbackReason())
                .status(ImportBatchStatus.FAILED)
                .failureReason(reason)
                .build());
        return ImportBatchResponseDTO.fromEntity(failed);
    }

    /**
     * Guarda-corpo central (comum a todos os extratores): valor ausente/zero ou data fora de uma
     * janela plausível ({@code [hoje-10 anos, hoje+1 ano]}) zera a CONFIANÇA do campo — nunca
     * apaga o valor (o usuário ainda vê o que foi lido e corrige na revisão).
     */
    private Map<String, StagedFieldValue> applyCentralSanity(Map<String, StagedFieldValue> fields) {
        Map<String, StagedFieldValue> adjusted = new LinkedHashMap<>(fields);

        StagedFieldValue amount = adjusted.get("amount");
        if (amount != null) {
            BigDecimal amountValue = toBigDecimal(amount.value());
            if (amountValue == null || amountValue.compareTo(BigDecimal.ZERO) == 0) {
                adjusted.put("amount", new StagedFieldValue(amount.value(), BigDecimal.ZERO));
            }
        }

        StagedFieldValue date = adjusted.get("transaction_date");
        if (date != null) {
            LocalDate parsedDate = toLocalDate(date.value());
            boolean implausible = parsedDate == null
                    || parsedDate.isBefore(LocalDate.now().minusYears(10))
                    || parsedDate.isAfter(LocalDate.now().plusYears(1));
            if (implausible) {
                adjusted.put("transaction_date", new StagedFieldValue(date.value(), BigDecimal.ZERO));
            }
        }

        return adjusted;
    }

    /**
     * Chave de dedup intra-batch: {@code external_id} (FITID do OFX) é AUTORIDADE — único por
     * transação segundo o padrão OFX. Sem ele (CSV), cai no trio (data, valor, descrição); sem
     * NENHUM dado pra formar chave, devolve {@code null} (não arrisca dedup por coincidência vazia).
     */
    private String dedupKey(Map<String, StagedFieldValue> fields) {
        StagedFieldValue externalId = fields.get("external_id");
        if (externalId != null && externalId.value() != null) {
            return "EXT:" + externalId.value();
        }
        Object date = fields.get("transaction_date") != null ? fields.get("transaction_date").value() : null;
        Object amount = fields.get("amount") != null ? fields.get("amount").value() : null;
        Object description = fields.get("description") != null ? fields.get("description").value() : null;
        if (date == null && amount == null && description == null) {
            return null;
        }
        return "TRIO:" + date + "|" + amount + "|" + description;
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

    /**
     * Descarta uma staged PENDING (Fase 2, metade B): a linha não vira {@code Transaction} e deixa
     * de bloquear a revisão do batch (ex.: candidata a duplicata, linha de rodapé do extrato).
     *
     * <p>É uma TRANSIÇÃO DE ESTADO, não uma edição de campos — por isso endpoint próprio, e não
     * um campo {@code status} no {@link StagedPatchDTO} (mesmo padrão de {@code close}/{@code pay}
     * de fatura). Só transiciona a partir de PENDING; qualquer outro estado → 400.
     */
    @Transactional
    public StagedTransactionResponseDTO discardStaged(UUID batchId, UUID stagedId, User user) {
        ImportBatch batch = findBatch(batchId, user);  // valida tenant/existência do batch (404)
        StagedTransaction staged = findStaged(stagedId, batchId, user);
        if (staged.getStatus() != StagedTransactionStatus.PENDING) {
            throw new BusinessException("Só é possível descartar uma staged pendente.");
        }

        staged.setStatus(StagedTransactionStatus.DISCARDED);

        // Descartar a ÚLTIMA pendente resolve o batch tanto quanto lançá-la: sem isso, um batch
        // cujas linhas foram todas descartadas ficaria EXTRACTED para sempre (o gate do commit()
        // nunca roda, porque não há o que commitar). Mesma condição, chamada de dois pontos.
        closeBatchIfNoPendingLeft(batch, user);

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

        // Fatura que O DOCUMENTO IMPORTADO representa (vencimento impresso, spec 2026-08-09) —
        // quando presente, TODA linha do batch ancora nela, em vez de recalculada por
        // resolveInvoiceMonth (que erra em parcela em andamento com data de compra antiga).
        YearMonth targetInvoiceMonth =
                (batch.getTargetInvoiceReferenceYear() != null && batch.getTargetInvoiceReferenceMonth() != null)
                        ? YearMonth.of(batch.getTargetInvoiceReferenceYear(), batch.getTargetInvoiceReferenceMonth())
                        : null;

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

            // Parcela 1 de um parcelamento reconhecido (hoje só o ItauFaturaTemplate preenche
            // esses campos): reusa o MESMO caminho de criação parcelada do lançamento manual —
            // o valor aproximado (parcela × N) é dividido de volta pela mesma regra de resíduo,
            // então a soma bate. Parcela > 1 sem grupo correspondente no sistema seria
            // reconciliação de verdade (fora de escopo — spec import-itau-parcelamento §7); cai
            // no caminho avulso de sempre.
            Integer installmentNumber = fieldValue(staged, "installment_number", this::toInteger);
            Integer installmentTotal = fieldValue(staged, "installment_total", this::toInteger);
            BigDecimal requestAmount = amount;
            Integer totalInstallments = null;
            // Teto de sanidade: nenhuma fatura real parcela em mais de 36x. Sem ele, um falso
            // positivo do marcador de parcela (ex. um código de produto que termina em "01/87")
            // multiplicaria o valor por 87 e criaria 87 transações/faturas silenciosamente.
            if (installmentNumber != null && installmentNumber == 1
                    && installmentTotal != null && installmentTotal > 1 && installmentTotal <= 36) {
                requestAmount = amount.multiply(BigDecimal.valueOf(installmentTotal));
                totalInstallments = installmentTotal;
            }

            // status = null → create() aplica o default (PENDING), mesma semântica de um lançamento manual.
            TransactionRequestDTO dto = new TransactionRequestDTO(
                    description, requestAmount, date, type, (TransactionStatus) null, totalInstallments,
                    item.categoryId(), item.accountId());
            List<TransactionResponseDTO> created = transactionService.create(dto, user, targetInvoiceMonth);
            TransactionResponseDTO tx = created.get(0);

            staged.setPromotedTransactionId(tx.id());
            staged.setStatus(StagedTransactionStatus.CONFIRMED);
        }

        closeBatchIfNoPendingLeft(batch, user);

        return ImportBatchResponseDTO.fromEntity(batch);
    }

    // ---------------------------------------------------------------------------------------
    // Internos
    // ---------------------------------------------------------------------------------------

    /**
     * Gate único de encerramento do batch: "não sobrou nenhuma staged PENDING" ⇒ COMMITTED.
     * Chamado pelo {@code commit()} (todas lançadas) e pelo {@code discardStaged()} (a última
     * pendente foi descartada). Condição em UM lugar só — duplicá-la é o tipo de regra que
     * diverge silenciosamente numa mudança futura.
     */
    private void closeBatchIfNoPendingLeft(ImportBatch batch, User user) {
        boolean anyPending = stagedRepository
                .findAllByBatch_IdAndTenantOrderByCreatedAt(batch.getId(), user.getTenant())
                .stream()
                .anyMatch(s -> s.getStatus() == StagedTransactionStatus.PENDING);
        if (!anyPending) {
            batch.setStatus(ImportBatchStatus.COMMITTED);
        }
    }

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

    private Integer toInteger(Object v) {
        BigDecimal b = toBigDecimal(v);
        return b == null ? null : b.intValue();
    }
}
