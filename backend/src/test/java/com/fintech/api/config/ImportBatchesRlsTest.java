package com.fintech.api.config;

import com.fintech.api.domain.enums.ImportBatchStatus;
import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.imports.ImportBatch;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.imports.ImportBatchResponseDTO;
import com.fintech.api.repository.ImportBatchRepository;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova discriminante do rollout de RLS (#116, ADR-006) em {@code import_batches} — mesmo
 * molde do {@code TenantRlsAspectTest} do PoC em {@code transactions}.
 */
@SpringBootTest
@Transactional
class ImportBatchesRlsTest {

    @Autowired EntityManager entityManager;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired ImportBatchRepository importBatchRepository;
    @Autowired ImportService importService;

    private Tenant tenantA;
    private User userA;

    @BeforeEach
    void setup() {
        tenantA = tenantRepository.save(newTenant("Tenant RLS Batches A"));
        Tenant tenantB = tenantRepository.save(newTenant("Tenant RLS Batches B"));
        userA = userRepository.save(newUser(tenantA));
        User userB = userRepository.save(newUser(tenantB));

        setTenantId(tenantA.getId());
        importBatchRepository.save(newBatch(tenantA, userA));
        importBatchRepository.save(newBatch(tenantA, userA));
        entityManager.flush();

        setTenantId(tenantB.getId());
        importBatchRepository.save(newBatch(tenantB, userB));
        entityManager.flush();
    }

    @Test
    @DisplayName("sem app.tenant_id setado, query nativa sem WHERE retorna 0 linhas")
    void nativeQueryWithoutTenantContextReturnsNothing() {
        entityManager.createNativeQuery("RESET app.tenant_id").executeUpdate();

        long count = ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM import_batches")
                .getSingleResult()).longValue();

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("com app.tenant_id = A, query nativa sem WHERE nunca retorna linha do tenant B")
    void nativeQueryWithTenantAContextNeverLeaksTenantB() {
        setTenantId(tenantA.getId());

        @SuppressWarnings("unchecked")
        List<UUID> rows = entityManager
                .createNativeQuery("SELECT tenant_id FROM import_batches")
                .getResultList();

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(tenantId -> assertThat(tenantId.toString()).isEqualTo(tenantA.getId().toString()));
    }

    @Test
    @DisplayName("TenantRlsAspect: ImportService.listBatches funciona normalmente pro usuário autenticado")
    void aspectSetsContextTransparentlyForAuthenticatedFlow() {
        List<ImportBatchResponseDTO> result = importService.listBatches(userA);

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
        user.setEmail("rls-batches-" + UUID.randomUUID() + "@test.com");
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
}
