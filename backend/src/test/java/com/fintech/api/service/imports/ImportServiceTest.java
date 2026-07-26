package com.fintech.api.service.imports;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.ImportBatchStatus;
import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.domain.enums.StagedTransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.imports.ImportBatchResponseDTO;
import com.fintech.api.dto.imports.ImportCommitRequestDTO;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedCommitItemDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.dto.imports.StagedPatchDTO;
import com.fintech.api.dto.imports.StagedTransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integração leve contra o Postgres dev (sem Testcontainers — estilo do projeto).
 * {@code @Transactional} garante rollback ao fim: o teste cria suas fixtures (tenant + user)
 * e nada é commitado. Prova que a migration V23 subiu, o JSONB mapeia, {@code requires_review}
 * é DERIVADO por threshold no código, e o isolamento de tenant vale para import.
 */
@SpringBootTest
@Transactional
class ImportServiceTest {

    @Autowired ImportService importService;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionService transactionService;

    private Tenant persistTenant(String name) {
        Tenant t = new Tenant();
        t.setName(name);
        return tenantRepository.save(t);
    }

    private Account persistAccount(Tenant tenant, User user) {
        return accountRepository.save(Account.builder()
                .name("Conta Corrente")
                .type(AccountType.CHECKING)
                .countInLiquidBalance(true)
                .countInNetWorth(true)
                .active(true)
                .tenant(tenant)
                .createdBy(user)
                .build());
    }

    private User persistUser(Tenant tenant, String email) {
        User u = new User();
        u.setTenant(tenant);
        u.setName("Owner " + email);
        u.setEmail(email);
        u.setPasswordHash("irrelevant-hash");
        u.setRole(UserRole.ADMIN);
        u.setActive(true);
        return userRepository.save(u);
    }

    private StagedFieldValueDTO fieldValue(Object value, String confidence) {
        return new StagedFieldValueDTO(value, new BigDecimal(confidence));
    }

    /** Alta confiança em tudo (overall 0.95 ≥ 0.90, amount 0.98 ≥ 0.95) → NÃO exige revisão. */
    private NormalizedTransactionDTO highConfidence() {
        return new NormalizedTransactionDTO(
                null,
                Map.of("amount", fieldValue(127.50, "0.98"),
                        "transaction_date", fieldValue("2026-06-28", "0.95"),
                        "description", fieldValue("PADARIA SAO JOSE", "0.90")),
                "alimentacao", new BigDecimal("0.70"),
                new BigDecimal("0.95"),
                null, null);
    }

    /** overall 0.85 &lt; 0.90 (threshold geral) → exige revisão pela via do agregado. */
    private NormalizedTransactionDTO lowOverall() {
        return new NormalizedTransactionDTO(
                null,
                Map.of("amount", fieldValue(42.00, "0.99")),
                null, null,
                new BigDecimal("0.85"),
                null, null);
    }

    /** overall 0.99 (passa), mas amount 0.80 &lt; 0.95 (threshold de valor) → exige revisão. */
    private NormalizedTransactionDTO lowAmountConfidence() {
        return new NormalizedTransactionDTO(
                null,
                Map.of("amount", fieldValue(1000.00, "0.80")),
                null, null,
                new BigDecimal("0.99"),
                null, null);
    }

    /** Comprovante de ENTRADA (direction=credit), alta confiança → deve promover como INCOME. */
    private NormalizedTransactionDTO creditReceipt() {
        return new NormalizedTransactionDTO(
                null,
                Map.of("amount", fieldValue(500.00, "0.98"),
                        "transaction_date", fieldValue("2026-06-15", "0.95"),
                        "direction", fieldValue("credit", "0.99"),
                        "description", fieldValue("ESTORNO LOJA", "0.90")),
                null, null,
                new BigDecimal("0.95"),
                null, null);
    }

    private NormalizedBatchDTO batchOf(NormalizedTransactionDTO... txs) {
        return new NormalizedBatchDTO(
                ImportMode.NEW_TRANSACTIONS, ImportSourceType.IMAGE,
                "mock_extractor", "2026-07-24", List.of(txs));
    }

    @Test
    void criaBatchELeStagedPontaAPonta() {
        Tenant tenant = persistTenant("Tenant Import A");
        User user = persistUser(tenant, "a@import.test");

        ImportBatchResponseDTO created = importService.createBatch(
                batchOf(highConfidence(), lowOverall()), user);

        assertThat(created.id()).isNotNull();
        assertThat(created.status()).isEqualTo(ImportBatchStatus.EXTRACTED);
        assertThat(created.sourceType()).isEqualTo(ImportSourceType.IMAGE);

        // GET do batch ponta a ponta.
        assertThat(importService.getBatch(created.id(), user).id()).isEqualTo(created.id());

        // Lista as staged: 2 gravadas, JSONB round-trip preservou o campo amount.
        List<StagedTransactionResponseDTO> staged = importService.listStaged(created.id(), user);
        assertThat(staged).hasSize(2);
        assertThat(staged).allSatisfy(s -> assertThat(s.fields()).containsKey("amount"));
    }

    @Test
    void derivaRequiresReviewPorThresholdNoCodigo() {
        Tenant tenant = persistTenant("Tenant Import B");
        User user = persistUser(tenant, "b@import.test");

        ImportBatchResponseDTO created = importService.createBatch(
                batchOf(highConfidence(), lowOverall(), lowAmountConfidence()), user);

        List<StagedTransactionResponseDTO> staged = importService.listStaged(created.id(), user);
        assertThat(staged).hasSize(3);

        // Identifica cada uma pela confiança agregada (única por transação) e prova cada via.
        assertThat(requiresReviewOf(staged, "0.95")).isFalse();  // alta confiança → não revisa
        assertThat(requiresReviewOf(staged, "0.85")).isTrue();   // overall < 0.90 → revisa
        assertThat(requiresReviewOf(staged, "0.99")).isTrue();   // amount conf < 0.95 → revisa
    }

    @Test
    void naoVazaBatchNemStagedEntreTenants() {
        Tenant owner = persistTenant("Tenant Import Owner");
        User ownerUser = persistUser(owner, "owner@import.test");
        Tenant intruder = persistTenant("Tenant Import Intruder");
        User intruderUser = persistUser(intruder, "intruder@import.test");

        ImportBatchResponseDTO batch = importService.createBatch(batchOf(highConfidence()), ownerUser);

        // Invariante nº1: o batch do dono responde 404 para outro tenant (não confirma existência).
        assertThatThrownBy(() -> importService.getBatch(batch.id(), intruderUser))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> importService.listStaged(batch.id(), intruderUser))
                .isInstanceOf(EntityNotFoundException.class);

        // Contraprova: o batch EXISTE (o dono o enxerga) — o 404 acima é isolamento, não ausência.
        assertThat(importService.getBatch(batch.id(), ownerUser).id()).isEqualTo(batch.id());
        assertThat(importService.listStaged(batch.id(), ownerUser)).hasSize(1);
    }

    // ------------------------------------------------------------------------------------
    // Fase 1 — commit (promoção) e patch
    // ------------------------------------------------------------------------------------

    @Test
    void commitPromoveStagedParaTransacaoEFechaOBatch() {
        Tenant tenant = persistTenant("Tenant Commit");
        User user = persistUser(tenant, "commit@import.test");
        Account account = persistAccount(tenant, user);

        ImportBatchResponseDTO batch = importService.createBatch(batchOf(highConfidence()), user);
        UUID stagedId = importService.listStaged(batch.id(), user).get(0).id();

        ImportCommitRequestDTO req = new ImportCommitRequestDTO(
                List.of(new StagedCommitItemDTO(stagedId, account.getId(), null)));
        ImportBatchResponseDTO committed = importService.commit(batch.id(), req, user);

        // Sem staged pendente restante → batch COMMITTED.
        assertThat(committed.status()).isEqualTo(ImportBatchStatus.COMMITTED);

        StagedTransactionResponseDTO afterStaged = importService.listStaged(batch.id(), user).get(0);
        assertThat(afterStaged.status()).isEqualTo(StagedTransactionStatus.CONFIRMED);
        assertThat(afterStaged.promotedTransactionId()).isNotNull();

        // A transação nasceu com os valores da staged (highConfidence: 127.50, sem direction → EXPENSE).
        TransactionResponseDTO tx = transactionService.findById(afterStaged.promotedTransactionId(), user);
        assertThat(tx.amount()).isEqualByComparingTo("127.50");
        assertThat(tx.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(tx.date()).isEqualTo(LocalDate.parse("2026-06-28"));
        assertThat(tx.accountId()).isEqualTo(account.getId());
    }

    @Test
    void commitMapeiaDirectionCreditParaINCOME() {
        Tenant tenant = persistTenant("Tenant Credit");
        User user = persistUser(tenant, "credit@import.test");
        Account account = persistAccount(tenant, user);

        ImportBatchResponseDTO batch = importService.createBatch(batchOf(creditReceipt()), user);
        UUID stagedId = importService.listStaged(batch.id(), user).get(0).id();

        ImportCommitRequestDTO req = new ImportCommitRequestDTO(
                List.of(new StagedCommitItemDTO(stagedId, account.getId(), null)));
        importService.commit(batch.id(), req, user);

        // Contraparte do teste EXPENSE acima: direction=credit deve virar INCOME na promoção.
        StagedTransactionResponseDTO afterStaged = importService.listStaged(batch.id(), user).get(0);
        TransactionResponseDTO tx = transactionService.findById(afterStaged.promotedTransactionId(), user);
        assertThat(tx.type()).isEqualTo(TransactionType.INCOME);
        assertThat(tx.amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void patchEditaCampoComConfiancaMaximaEReDerivaReview() {
        Tenant tenant = persistTenant("Tenant Patch");
        User user = persistUser(tenant, "patch@import.test");

        // lowAmountConfidence: overall 0.99 (passa), amount 0.80 < 0.95 → requiresReview TRUE.
        ImportBatchResponseDTO batch = importService.createBatch(batchOf(lowAmountConfidence()), user);
        StagedTransactionResponseDTO before = importService.listStaged(batch.id(), user).get(0);
        assertThat(before.requiresReview()).isTrue();

        // Humano corrige o valor → confiança do amount vira 1.0 → requiresReview cai para FALSE.
        StagedPatchDTO patch = new StagedPatchDTO(Map.of("amount", 2500.00), null);
        StagedTransactionResponseDTO updated = importService.patchStaged(batch.id(), before.id(), patch, user);

        assertThat(updated.fields().get("amount").value()).isEqualTo(2500.00);
        assertThat(updated.fields().get("amount").confidence()).isEqualByComparingTo("1");
        assertThat(updated.requiresReview()).isFalse();
    }

    @Test
    void naoPermiteCommitNemPatchDeStagedDeOutroTenant() {
        Tenant owner = persistTenant("Owner F1");
        User ownerUser = persistUser(owner, "owner-f1@import.test");
        Tenant intruder = persistTenant("Intruder F1");
        User intruderUser = persistUser(intruder, "intruder-f1@import.test");
        Account intruderAccount = persistAccount(intruder, intruderUser);

        ImportBatchResponseDTO batch = importService.createBatch(batchOf(highConfidence()), ownerUser);
        UUID stagedId = importService.listStaged(batch.id(), ownerUser).get(0).id();

        // Invariante nº1: o intruso não commita nem edita staged do dono → 404 (não confirma existência).
        ImportCommitRequestDTO req = new ImportCommitRequestDTO(
                List.of(new StagedCommitItemDTO(stagedId, intruderAccount.getId(), null)));
        assertThatThrownBy(() -> importService.commit(batch.id(), req, intruderUser))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> importService.patchStaged(
                batch.id(), stagedId, new StagedPatchDTO(Map.of("amount", 1.0), null), intruderUser))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private boolean requiresReviewOf(List<StagedTransactionResponseDTO> staged, String overall) {
        BigDecimal target = new BigDecimal(overall);
        return staged.stream()
                .filter(s -> s.overallConfidence() != null && s.overallConfidence().compareTo(target) == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("staged com overall=" + overall + " não encontrada"))
                .requiresReview();
    }
}
