package com.fintech.api.config;

import com.fintech.api.domain.enums.ImportBatchStatus;
import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.imports.ImportBatch;
import com.fintech.api.domain.imports.StagedFieldValue;
import com.fintech.api.domain.imports.StagedTransaction;
import com.fintech.api.domain.enums.StagedTransactionStatus;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.imports.StagedTransactionResponseDTO;
import com.fintech.api.repository.ImportBatchRepository;
import com.fintech.api.repository.StagedTransactionRepository;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.imports.ImportService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova discriminante do rollout de RLS (#116, ADR-006) em {@code staged_transactions}.
 */
@SpringBootTest
@Transactional
class StagedTransactionsRlsTest {

    @Autowired EntityManager entityManager;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired ImportBatchRepository importBatchRepository;
    @Autowired StagedTransactionRepository stagedTransactionRepository;
    @Autowired ImportService importService;

    private Tenant tenantA;
    private User userA;
    private UUID batchAId;

    @BeforeEach
    void setup() {
        tenantA = tenantRepository.save(newTenant("Tenant RLS Staged A"));
        Tenant tenantB = tenantRepository.save(newTenant("Tenant RLS Staged B"));
        userA = userRepository.save(newUser(tenantA));
        User userB = userRepository.save(newUser(tenantB));

        setTenantId(tenantA.getId());
        ImportBatch batchA = importBatchRepository.save(newBatch(tenantA, userA));
        stagedTransactionRepository.save(newStaged(batchA, tenantA));
        stagedTransactionRepository.save(newStaged(batchA, tenantA));
        entityManager.flush();
        batchAId = batchA.getId();
        // clear() detacha os entities de A: sem isso, StagedTransaction (tem @Version) pode
        // ser re-flushado (dirty-check) sob app.tenant_id=B mais adiante, e a policy o esconde
        // do próprio UPDATE de versionamento otimista — vira OptimisticLockException, não o
        // comportamento esperado. batchAId já foi capturado acima, antes do clear.
        entityManager.clear();

        setTenantId(tenantB.getId());
        ImportBatch batchB = importBatchRepository.save(newBatch(tenantB, userB));
        stagedTransactionRepository.save(newStaged(batchB, tenantB));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("sem app.tenant_id setado, query nativa sem WHERE retorna 0 linhas")
    void nativeQueryWithoutTenantContextReturnsNothing() {
        entityManager.createNativeQuery("RESET app.tenant_id").executeUpdate();

        long count = ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM staged_transactions")
                .getSingleResult()).longValue();

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("com app.tenant_id = A, query nativa sem WHERE nunca retorna linha do tenant B")
    void nativeQueryWithTenantAContextNeverLeaksTenantB() {
        setTenantId(tenantA.getId());

        @SuppressWarnings("unchecked")
        List<UUID> rows = entityManager
                .createNativeQuery("SELECT tenant_id FROM staged_transactions")
                .getResultList();

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(tenantId -> assertThat(tenantId.toString()).isEqualTo(tenantA.getId().toString()));
    }

    @Test
    @DisplayName("TenantRlsAspect: ImportService.listStaged funciona normalmente pro usuário autenticado")
    void aspectSetsContextTransparentlyForAuthenticatedFlow() {
        List<StagedTransactionResponseDTO> result = importService.listStaged(batchAId, userA);

        assertThat(result).hasSize(2);
    }

    private void setTenantId(UUID tenantId) {
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '" + tenantId + "'").executeUpdate();
    }

    private Tenant newTenant(String name) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        return tenant;
    }

    private User newUser(Tenant tenant) {
        User user = new User();
        user.setName("U");
        user.setEmail("rls-staged-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("h");
        user.setRole(UserRole.ADMIN);
        user.setTenant(tenant);
        return user;
    }

    private ImportBatch newBatch(Tenant tenant, User user) {
        return ImportBatch.builder()
                .tenant(tenant).createdBy(user)
                .importMode(ImportMode.NEW_TRANSACTIONS)
                .sourceType(ImportSourceType.CSV)
                .status(ImportBatchStatus.EXTRACTED)
                .build();
    }

    private StagedTransaction newStaged(ImportBatch batch, Tenant tenant) {
        return StagedTransaction.builder()
                .batch(batch).tenant(tenant)
                .fields(Map.of("amount", new StagedFieldValue(new BigDecimal("10.00"), BigDecimal.ONE)))
                .requiresReview(false)
                .status(StagedTransactionStatus.PENDING)
                .build();
    }
}
