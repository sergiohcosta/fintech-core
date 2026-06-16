package com.fintech.api.service;

import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.dto.budget.RecurringBudgetItemRequest;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.RecurringBudgetItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurringBudgetItemServiceTest {

    @Mock RecurringBudgetItemRepository repository;
    @Mock CategoryRepository categoryRepository;
    @Mock AccountRepository accountRepository;

    @InjectMocks RecurringBudgetItemService service;

    @Test
    @DisplayName("update() em item inativo lança IllegalStateException")
    void update_inactiveItem_throwsIllegalState() {
        Tenant tenant = tenantWithId();
        UUID itemId = UUID.randomUUID();

        RecurringBudgetItem item = RecurringBudgetItem.builder()
            .id(itemId)
            .tenant(tenant)
            .description("Aluguel")
            .amount(new BigDecimal("2000.00"))
            .type(TransactionType.EXPENSE)
            .dayOfMonth(10)
            .active(false)
            .build();

        when(repository.findByIdAndTenant(itemId, tenant)).thenReturn(Optional.of(item));

        RecurringBudgetItemRequest request = new RecurringBudgetItemRequest(
            "Aluguel atualizado", new BigDecimal("2500.00"), TransactionType.EXPENSE, 15, null, null);

        assertThatThrownBy(() -> service.update(itemId, request, tenant))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("reativado antes de ser editado");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("reactivate() restaura active=true")
    void reactivate_setsActiveTrue() {
        Tenant tenant = tenantWithId();
        UUID itemId = UUID.randomUUID();

        RecurringBudgetItem item = RecurringBudgetItem.builder()
            .id(itemId)
            .tenant(tenant)
            .description("Internet")
            .amount(new BigDecimal("120.00"))
            .type(TransactionType.EXPENSE)
            .dayOfMonth(5)
            .active(false)
            .build();

        when(repository.findByIdAndTenant(itemId, tenant)).thenReturn(Optional.of(item));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecurringBudgetItem result = service.reactivate(itemId, tenant);

        assertThat(result.isActive()).isTrue();
        verify(repository).save(item);
    }

    @Test
    @DisplayName("create() com categoria de outro tenant lança EntityNotFoundException")
    void create_withInvalidCategory_throwsException() {
        Tenant tenant = tenantWithId();
        UUID categoryIdDeOutroTenant = UUID.randomUUID();

        when(categoryRepository.findByIdAndTenantIdAndDeletedAtIsNull(categoryIdDeOutroTenant, tenant.getId()))
            .thenReturn(Optional.empty());

        RecurringBudgetItemRequest request = new RecurringBudgetItemRequest(
            "Streaming", new BigDecimal("55.90"), TransactionType.EXPENSE, 20, categoryIdDeOutroTenant, null);

        assertThatThrownBy(() -> service.create(request, tenant, null))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining("Categoria não encontrada");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("update() com conta de outro tenant lança EntityNotFoundException")
    void update_withInvalidAccount_throwsException() {
        Tenant tenant = tenantWithId();
        UUID itemId = UUID.randomUUID();
        UUID accountIdDeOutroTenant = UUID.randomUUID();

        RecurringBudgetItem item = RecurringBudgetItem.builder()
            .id(itemId)
            .tenant(tenant)
            .description("Salário")
            .amount(new BigDecimal("8000.00"))
            .type(TransactionType.INCOME)
            .dayOfMonth(1)
            .active(true)
            .build();

        when(repository.findByIdAndTenant(itemId, tenant)).thenReturn(Optional.of(item));
        when(accountRepository.findByIdAndTenant(accountIdDeOutroTenant, tenant))
            .thenReturn(Optional.empty());

        RecurringBudgetItemRequest request = new RecurringBudgetItemRequest(
            "Salário", new BigDecimal("8000.00"), TransactionType.INCOME, 1, null, accountIdDeOutroTenant);

        assertThatThrownBy(() -> service.update(itemId, request, tenant))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining("Conta não encontrada");

        verify(repository, never()).save(any());
    }

    private Tenant tenantWithId() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        return tenant;
    }
}
