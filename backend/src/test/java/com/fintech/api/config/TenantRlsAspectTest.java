package com.fintech.api.config;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.TransactionRepository;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.TransactionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova discriminante do PoC de RLS (#116, ADR-006): a policy da tabela {@code transactions}
 * (V33) bloqueia leitura cross-tenant mesmo quando a query não filtra por {@code tenant_id} —
 * cenário que a aplicação sozinha (só {@code WHERE tenant_id}) nunca conseguiria provar.
 */
@SpringBootTest
@Transactional
class TenantRlsAspectTest {

    @Autowired EntityManager entityManager;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired TransactionService transactionService;

    private Tenant tenantA;
    private Tenant tenantB;
    private User userA;

    @BeforeEach
    void setup() {
        tenantA = tenantRepository.save(newTenant("Tenant RLS A"));
        tenantB = tenantRepository.save(newTenant("Tenant RLS B"));
        userA = userRepository.save(newUser(tenantA));
        User userB = userRepository.save(newUser(tenantB));

        Account accountA = accountRepository.save(newAccount(tenantA));
        Account accountB = accountRepository.save(newAccount(tenantB));

        // FORCE ROW LEVEL SECURITY exige app.tenant_id setado até para o INSERT da fixture —
        // por isso o SET LOCAL explícito aqui, fora do TenantRlsAspect (que só cobre
        // TransactionService, não o repositório usado direto no setup do teste).
        // entityManager.flush() é obrigatório logo após cada save(): sem ele, Hibernate pode
        // adiar o INSERT físico até o próximo flush automático — que aconteceria só depois de
        // trocar app.tenant_id para o tenant seguinte, fazendo a policy rejeitar a linha por
        // tenant errado no momento em que o INSERT de fato sai (achado real deste teste).
        setTenantId(tenantA.getId());
        transactionRepository.save(newTx(accountA, tenantA, userA, "100.00"));
        transactionRepository.save(newTx(accountA, tenantA, userA, "200.00"));
        entityManager.flush();

        setTenantId(tenantB.getId());
        transactionRepository.save(newTx(accountB, tenantB, userB, "999.00"));
        entityManager.flush();
    }

    @Test
    @DisplayName("sem app.tenant_id setado, query nativa sem WHERE retorna 0 linhas (fail-safe deny)")
    void nativeQueryWithoutTenantContextReturnsNothing() {
        entityManager.createNativeQuery("RESET app.tenant_id").executeUpdate();

        long count = ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM transactions")
                .getSingleResult()).longValue();

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("com app.tenant_id = A, query nativa sem WHERE nunca retorna linha do tenant B")
    void nativeQueryWithTenantAContextNeverLeaksTenantB() {
        setTenantId(tenantA.getId());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT tenant_id, amount FROM transactions")
                .getResultList();

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row[0].toString()).isEqualTo(tenantA.getId().toString()));
    }

    @Test
    @DisplayName("TenantRlsAspect: TransactionService.findAll funciona normalmente para o usuário autenticado")
    void aspectSetsContextTransparentlyForAuthenticatedFlow() {
        List<TransactionResponseDTO> result = transactionService.findAll(
                userA, null, null, null, null, null, null);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(tx -> assertThat(tx.amount()).isIn(
                new BigDecimal("100.00"), new BigDecimal("200.00")));
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
        user.setEmail("rls-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("h");
        user.setRole(UserRole.ADMIN);
        user.setTenant(tenant);
        return user;
    }

    private Account newAccount(Tenant tenant) {
        return Account.builder()
                .tenant(tenant).name("Conta").type(AccountType.CHECKING)
                .countInLiquidBalance(true).countInNetWorth(true).active(true).build();
    }

    private Transaction newTx(Account account, Tenant tenant, User user, String amount) {
        return Transaction.builder()
                .description("tx rls").amount(new BigDecimal(amount)).date(LocalDate.of(2026, 6, 1))
                .type(TransactionType.EXPENSE).status(TransactionStatus.PAID)
                .installmentNumber(1).totalInstallments(1)
                .tenant(tenant).user(user).account(account).build();
    }
}
