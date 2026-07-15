package com.fintech.api.repository;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integração leve contra o Postgres dev (sem Testcontainers — segue o estilo do projeto).
 * {@code @Transactional} garante rollback ao fim: o teste cria suas próprias fixtures
 * (tenant + conta) e nada é commitado no banco de dev. Prova que a migration V19 subiu,
 * a entidade mapeia e os finders escopados por tenant funcionam.
 */
@SpringBootTest
@Transactional
class RecurrenceRuleRepositoryTest {

    @Autowired RecurrenceRuleRepository ruleRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired AccountRepository accountRepository;

    private RecurrenceRule persistRule(Tenant tenant, Account account) {
        return ruleRepository.save(RecurrenceRule.builder()
                .tenant(tenant)
                .account(account)
                .description("Netflix")
                .baseAmount(new BigDecimal("50.00"))
                .type(TransactionType.EXPENSE)
                .rrule("FREQ=MONTHLY;BYMONTHDAY=15")
                .startDate(LocalDate.of(2026, 1, 15))
                .status(RecurrenceStatus.ACTIVE)
                .build());
    }

    private Account persistAccount(Tenant tenant) {
        return accountRepository.save(Account.builder()
                .tenant(tenant)
                .name("Conta Teste Recorrência")
                .type(AccountType.CHECKING)
                .countInLiquidBalance(true)
                .countInNetWorth(true)
                .active(true)
                .build());
    }

    @Test
    void persisteEConsultaRegraAtivaPorTenant() {
        Tenant tenant = new Tenant();
        tenant.setName("Tenant Teste Recorrência");
        tenant = tenantRepository.save(tenant);
        Account account = persistAccount(tenant);

        RecurrenceRule rule = persistRule(tenant, account);

        List<RecurrenceRule> active = ruleRepository.findByTenantAndStatus(tenant, RecurrenceStatus.ACTIVE);
        assertThat(active).extracting(RecurrenceRule::getId).contains(rule.getId());
        assertThat(ruleRepository.findByIdAndTenant(rule.getId(), tenant)).isPresent();
    }

    @Test
    void naoVazaRegraEntreTenants() {
        Tenant owner = new Tenant();
        owner.setName("Tenant Dono");
        owner = tenantRepository.save(owner);
        Tenant other = new Tenant();
        other.setName("Tenant Intruso");
        other = tenantRepository.save(other);

        RecurrenceRule rule = persistRule(owner, persistAccount(owner));

        // Isolamento de tenant: a regra do dono não aparece para outro tenant.
        assertThat(ruleRepository.findByTenantAndStatus(other, RecurrenceStatus.ACTIVE))
                .extracting(RecurrenceRule::getId).doesNotContain(rule.getId());
        assertThat(ruleRepository.findByIdAndTenant(rule.getId(), other)).isEmpty();
    }
}
