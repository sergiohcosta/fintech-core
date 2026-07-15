package com.fintech.api.service.recurrence;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.recurrence.RecurrenceException;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.repository.RecurrenceExceptionRepository;
import com.fintech.api.repository.RecurrenceRuleRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurrenceProjectionServiceTest {

    @Mock RecurrenceRuleRepository ruleRepository;
    @Mock RecurrenceExceptionRepository exceptionRepository;
    @Mock TransactionRepository transactionRepository;

    private RecurrenceProjectionService service;

    @BeforeEach
    void setup() {
        // RecurrenceExpander é lógica pura (sem mocks) — injetado de verdade.
        service = new RecurrenceProjectionService(
                ruleRepository, exceptionRepository, transactionRepository, new RecurrenceExpander());
    }

    private RecurrenceRule netflix(Tenant tenant, Account acc) {
        return RecurrenceRule.builder()
                .id(UUID.randomUUID()).tenant(tenant).account(acc)
                .description("Netflix").baseAmount(new BigDecimal("100.00"))
                .type(TransactionType.EXPENSE)
                .rrule("FREQ=MONTHLY;BYMONTHDAY=10")
                .startDate(LocalDate.of(2026, 1, 10))
                .status(RecurrenceStatus.ACTIVE)
                .build();
    }

    // Teste B (inflação): confirmar agosto a 150 (materializado) não some a fantasma de
    // setembro, que volta ao base 100.
    @Test
    void subtraiOcorrenciaMaterializadaMasMantemAsDemais() {
        Tenant tenant = new Tenant();
        Account acc = new Account(); acc.setName("Cartão");
        RecurrenceRule rule = netflix(tenant, acc);

        when(ruleRepository.findByTenantAndStatus(tenant, RecurrenceStatus.ACTIVE)).thenReturn(List.of(rule));
        Transaction ago = Transaction.builder().recurrenceRule(rule)
                .recurrenceOccurrence(LocalDate.of(2026, 8, 10)).build();
        when(transactionRepository.findByRecurrenceRuleIdInAndRecurrenceOccurrenceBetween(any(), any(), any()))
                .thenReturn(List.of(ago));
        when(exceptionRepository.findByRuleIdInAndOccurrenceDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        List<ProjectedOccurrence> ghosts = service.project(tenant,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30));

        assertThat(ghosts).extracting(ProjectedOccurrence::occurrenceDate)
                .containsExactly(LocalDate.of(2026, 9, 10)); // agosto sumiu; setembro fantasma
        assertThat(ghosts.getFirst().amount()).isEqualByComparingTo("100.00"); // volta ao base
    }

    // Teste C (pular): EXDATE no mês vigente remove só aquela ocorrência.
    @Test
    void subtraiExdate() {
        Tenant tenant = new Tenant();
        Account acc = new Account(); acc.setName("Cartão");
        RecurrenceRule rule = netflix(tenant, acc);

        when(ruleRepository.findByTenantAndStatus(tenant, RecurrenceStatus.ACTIVE)).thenReturn(List.of(rule));
        when(transactionRepository.findByRecurrenceRuleIdInAndRecurrenceOccurrenceBetween(any(), any(), any()))
                .thenReturn(List.of());
        RecurrenceException skip = RecurrenceException.builder()
                .rule(rule).occurrenceDate(LocalDate.of(2026, 8, 10)).build();
        when(exceptionRepository.findByRuleIdInAndOccurrenceDateBetween(any(), any(), any()))
                .thenReturn(List.of(skip));

        List<ProjectedOccurrence> ghosts = service.project(tenant,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30));

        assertThat(ghosts).extracting(ProjectedOccurrence::occurrenceDate)
                .containsExactly(LocalDate.of(2026, 9, 10)); // agosto pulado
    }
}
