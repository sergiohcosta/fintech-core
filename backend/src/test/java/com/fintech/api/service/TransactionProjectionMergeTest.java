package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.repository.TransactionRepository;
import com.fintech.api.service.recurrence.ProjectedOccurrence;
import com.fintech.api.service.recurrence.RecurrenceProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
class TransactionProjectionMergeTest {

    @Mock TransactionRepository repository;
    @Mock RecurrenceProjectionService projectionService;
    @InjectMocks TransactionService service;

    private User user;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        user = new User();
        user.setTenant(tenant);
    }

    private Transaction realTx() {
        Account acc = Account.builder().id(accountId).name("Conta").build();
        return Transaction.builder()
                .id(UUID.randomUUID()).description("Mercado").amount(new BigDecimal("80.00"))
                .date(LocalDate.of(2026, 8, 5)).type(TransactionType.EXPENSE)
                .account(acc).tenant(user.getTenant()).user(user).build();
    }

    private ProjectedOccurrence ghost() {
        return new ProjectedOccurrence(UUID.randomUUID(), LocalDate.of(2026, 8, 15),
                "Netflix", new BigDecimal("55.90"), TransactionType.EXPENSE,
                null, null, null, accountId, "Conta");
    }

    @Test
    void mesclaFantasmasOrdenadasQuandoIncludeProjectedTrue() {
        when(repository.findAllByTenantWithFilters(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any())).thenReturn(List.of(realTx()));
        when(projectionService.project(any(), any(), any())).thenReturn(List.of(ghost()));

        List<TransactionResponseDTO> result = service.findAll(user, null, null, null, null,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), true);

        assertThat(result).hasSize(2);
        // Ordenado desc por data efetiva: fantasma (15) antes da real (05).
        assertThat(result.get(0).projected()).isTrue();
        assertThat(result.get(0).id()).isNull();
        assertThat(result.get(0).date()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(result.get(1).projected()).isFalse();
        assertThat(result.get(1).date()).isEqualTo(LocalDate.of(2026, 8, 5));
    }

    @Test
    void naoMesclaQuandoIncludeProjectedFalse() {
        when(repository.findAllByTenantWithFilters(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any())).thenReturn(List.of(realTx()));

        List<TransactionResponseDTO> result = service.findAll(user, null, null, null, null,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), false);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().projected()).isFalse();
    }
}
