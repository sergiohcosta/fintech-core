package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.installment.InstallmentGroupResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.InstallmentGroupRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstallmentGroupServiceTest {

    @Mock InstallmentGroupRepository groupRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock AccountRepository accountRepository;
    @InjectMocks InstallmentGroupService service;

    @Test
    @DisplayName("findById lança EntityNotFoundException quando grupo não pertence ao tenant")
    void findByIdThrowsForOtherTenant() {
        User user = buildUser();
        UUID groupId = UUID.randomUUID();
        when(groupRepository.findByIdAndTenant(groupId, user.getTenant()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(groupId, user))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("deleteGroup remove apenas parcelas PENDING e retorna contagens corretas")
    void deleteGroupSkipsPaidInstallments() {
        User user = buildUser();
        Account account = buildAccount(user);
        UUID groupId = UUID.randomUUID();
        InstallmentGroup group = buildGroup(groupId, user, account);

        Transaction paid = buildTransaction(group, 1, TransactionStatus.PAID);
        Transaction pending1 = buildTransaction(group, 2, TransactionStatus.PENDING);
        Transaction pending2 = buildTransaction(group, 3, TransactionStatus.PENDING);

        when(groupRepository.findByIdAndTenant(groupId, user.getTenant()))
                .thenReturn(Optional.of(group));
        when(transactionRepository.findByInstallmentGroupOrderByInstallmentNumberAsc(group))
                .thenReturn(List.of(paid, pending1, pending2));

        DeleteInstallmentResultDTO result = service.deleteGroup(groupId, user);

        verify(transactionRepository).deleteAll(List.of(pending1, pending2));
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.skippedPaid()).isEqualTo(1);
    }

    @Test
    @DisplayName("findById retorna DTO com contagens corretas de paidInstallments e pendingInstallments")
    void findByIdReturnsDTOWithCorrectCounts() {
        User user = buildUser();
        Account account = buildAccount(user);
        UUID groupId = UUID.randomUUID();
        InstallmentGroup group = buildGroup(groupId, user, account);

        Transaction paid = buildTransaction(group, 1, TransactionStatus.PAID);
        Transaction pending = buildTransaction(group, 2, TransactionStatus.PENDING);

        when(groupRepository.findByIdAndTenant(groupId, user.getTenant()))
                .thenReturn(Optional.of(group));
        when(transactionRepository.findByInstallmentGroupOrderByInstallmentNumberAsc(group))
                .thenReturn(List.of(paid, pending));

        InstallmentGroupResponseDTO dto = service.findById(groupId, user);

        assertThat(dto.paidInstallments()).isEqualTo(1);
        assertThat(dto.pendingInstallments()).isEqualTo(1);
        assertThat(dto.transactions()).hasSize(2);
    }

    private User buildUser() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        User user = new User();
        user.setTenant(tenant);
        return user;
    }

    private Account buildAccount(User user) {
        return Account.builder()
                .id(UUID.randomUUID())
                .type(AccountType.CHECKING)
                .tenant(user.getTenant())
                .build();
    }

    private InstallmentGroup buildGroup(UUID id, User user, Account account) {
        return InstallmentGroup.builder()
                .id(id)
                .description("Notebook")
                .totalAmount(new BigDecimal("3000.00"))
                .totalInstallments(3)
                .account(account)
                .tenant(user.getTenant())
                .build();
    }

    private Transaction buildTransaction(InstallmentGroup group, int number, TransactionStatus status) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .description("Notebook")
                .amount(new BigDecimal("1000.00"))
                .date(LocalDate.now().plusMonths(number - 1))
                .type(TransactionType.EXPENSE)
                .installmentNumber(number)
                .totalInstallments(3)
                .installmentGroup(group)
                .status(status)
                .tenant(group.getTenant())
                .account(group.getAccount())
                .build();
    }
}
