package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.DeleteInstallmentScope;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.InstallmentGroupRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock TransactionRepository repository;
    @Mock CategoryRepository categoryRepository;
    @Mock AccountRepository accountRepository;
    @Mock InstallmentGroupRepository installmentGroupRepository;
    @InjectMocks TransactionService service;

    @Test
    @DisplayName("Cria transação única quando installments=1")
    void createsSingleTransaction() {
        User user = buildUser();
        Account account = buildAccount(user);
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Salário", new BigDecimal("5000.00"), LocalDate.now(),
                TransactionType.INCOME, null, 1, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(1);
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("Cria N parcelas quando totalInstallments=N")
    void createsInstallments() {
        User user = buildUser();
        Account account = buildAccount(user);
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook", new BigDecimal("3000.00"), LocalDate.now(),
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(3);
        verify(repository, times(3)).save(any());
    }

    @Test
    @DisplayName("createTransfer cria duas transações espelhadas com mesmo transferId")
    void createTransferMirrorsTransactions() {
        User user = buildUser();
        Account from = buildAccount(user);
        Account to   = buildAccount(user);

        when(accountRepository.findByIdAndTenant(from.getId(), user.getTenant())).thenReturn(Optional.of(from));
        when(accountRepository.findByIdAndTenant(to.getId(), user.getTenant())).thenReturn(Optional.of(to));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        TransferRequestDTO dto = new TransferRequestDTO(
                from.getId(), to.getId(), new BigDecimal("500.00"), LocalDate.now(), null);

        service.createTransfer(dto, user);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(2)).save(captor.capture());

        List<Transaction> saved = captor.getAllValues();
        Transaction expense = saved.stream().filter(t -> t.getType() == TransactionType.EXPENSE).findFirst().orElseThrow();
        Transaction income  = saved.stream().filter(t -> t.getType() == TransactionType.INCOME).findFirst().orElseThrow();

        assertThat(expense.getAccount()).isEqualTo(from);
        assertThat(income.getAccount()).isEqualTo(to);
        assertThat(expense.getTransferId()).isNotNull().isEqualTo(income.getTransferId());
        assertThat(expense.getAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(expense.getDescription()).isEqualTo("Transferência");
    }

    @Test
    @DisplayName("createTransfer lança IllegalArgumentException quando contas são iguais")
    void createTransferRejectsEqualAccounts() {
        User user = buildUser();
        UUID sameId = UUID.randomUUID();
        TransferRequestDTO dto = new TransferRequestDTO(
                sameId, sameId, new BigDecimal("100.00"), LocalDate.now(), null);

        assertThatThrownBy(() -> service.createTransfer(dto, user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diferentes");
    }

    @Test
    @DisplayName("createTransfer usa descrição customizada quando fornecida")
    void createTransferUsesCustomDescription() {
        User user = buildUser();
        Account from = buildAccount(user);
        Account to   = buildAccount(user);

        when(accountRepository.findByIdAndTenant(from.getId(), user.getTenant())).thenReturn(Optional.of(from));
        when(accountRepository.findByIdAndTenant(to.getId(), user.getTenant())).thenReturn(Optional.of(to));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        TransferRequestDTO dto = new TransferRequestDTO(
                from.getId(), to.getId(), new BigDecimal("200.00"), LocalDate.now(), "Reserva emergência");

        service.createTransfer(dto, user);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(2)).save(captor.capture());
        captor.getAllValues().forEach(t ->
                assertThat(t.getDescription()).isEqualTo("Reserva emergência"));
    }

    @Test
    @DisplayName("deleteTransfer exclui as duas pernas da transferência")
    void deleteTransferRemovesBothLegs() {
        User user = buildUser();
        UUID transferId = UUID.randomUUID();
        Account from = buildAccount(user);
        Account to   = buildAccount(user);

        Transaction leg1 = Transaction.builder().id(UUID.randomUUID())
                .type(TransactionType.EXPENSE).account(from)
                .transferId(transferId).tenant(user.getTenant()).build();
        Transaction leg2 = Transaction.builder().id(UUID.randomUUID())
                .type(TransactionType.INCOME).account(to)
                .transferId(transferId).tenant(user.getTenant()).build();

        when(repository.findByTransferIdAndTenant(transferId, user.getTenant()))
                .thenReturn(List.of(leg1, leg2));

        service.deleteTransfer(transferId, user);

        verify(repository).deleteAll(List.of(leg1, leg2));
    }

    @Test
    @DisplayName("deleteTransfer lança EntityNotFoundException para transferId inexistente")
    void deleteTransferThrowsForUnknownId() {
        User user = buildUser();
        UUID transferId = UUID.randomUUID();

        when(repository.findByTransferIdAndTenant(transferId, user.getTenant()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.deleteTransfer(transferId, user))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("findById lança EntityNotFoundException para transação de outro tenant")
    void findByIdThrowsForOtherTenant() {
        User user = buildUser();
        when(repository.findByIdAndTenant(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(UUID.randomUUID(), user))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("create cria InstallmentGroup quando totalInstallments > 1")
    void createBuildsInstallmentGroup() {
        User user = buildUser();
        Account account = buildAccount(user);
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook", new BigDecimal("3000.00"), LocalDate.now(),
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(installmentGroupRepository.save(any(InstallmentGroup.class)))
                .thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user);

        ArgumentCaptor<InstallmentGroup> captor = ArgumentCaptor.forClass(InstallmentGroup.class);
        verify(installmentGroupRepository, times(1)).save(captor.capture());
        InstallmentGroup group = captor.getValue();
        assertThat(group.getDescription()).isEqualTo("Notebook");
        assertThat(group.getTotalAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(group.getTotalInstallments()).isEqualTo(3);
    }

    @Test
    @DisplayName("create não cria InstallmentGroup para transação única")
    void createDoesNotBuildGroupForSingleTransaction() {
        User user = buildUser();
        Account account = buildAccount(user);
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Salário", new BigDecimal("5000.00"), LocalDate.now(),
                TransactionType.INCOME, null, 1, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user);

        verify(installmentGroupRepository, never()).save(any());
    }

    @Test
    @DisplayName("create associa installmentGroup a cada parcela criada")
    void createAssociatesGroupToEachInstallment() {
        User user = buildUser();
        Account account = buildAccount(user);
        InstallmentGroup savedGroup = InstallmentGroup.builder()
                .id(UUID.randomUUID()).description("Notebook")
                .totalAmount(new BigDecimal("3000.00")).totalInstallments(3)
                .account(account).tenant(user.getTenant()).build();
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook", new BigDecimal("3000.00"), LocalDate.now(),
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(installmentGroupRepository.save(any(InstallmentGroup.class))).thenReturn(savedGroup);
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(3)).save(captor.capture());
        captor.getAllValues().forEach(t ->
                assertThat(t.getInstallmentGroup()).isEqualTo(savedGroup));
    }

    @Test
    @DisplayName("delete com SINGLE remove apenas a transação informada")
    void deleteWithSingleScopeRemovesOnlyOne() {
        User user = buildUser();
        Account account = buildAccount(user);
        UUID txId = UUID.randomUUID();
        Transaction t = Transaction.builder().id(txId)
                .installmentNumber(2).totalInstallments(3)
                .status(TransactionStatus.PENDING)
                .tenant(user.getTenant()).account(account).build();

        when(repository.findByIdAndTenant(txId, user.getTenant())).thenReturn(Optional.of(t));

        DeleteInstallmentResultDTO result = service.delete(txId, DeleteInstallmentScope.SINGLE, user);

        verify(repository).delete(t);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.skippedPaid()).isEqualTo(0);
    }

    @Test
    @DisplayName("delete com ALL pula parcelas PAID e informa quantidade ignorada")
    void deleteWithAllScopeSkipsPaidInstallments() {
        User user = buildUser();
        Account account = buildAccount(user);
        UUID groupId = UUID.randomUUID();
        InstallmentGroup group = InstallmentGroup.builder().id(groupId)
                .tenant(user.getTenant()).account(account).build();
        UUID txId = UUID.randomUUID();
        Transaction t = Transaction.builder().id(txId)
                .installmentNumber(1).totalInstallments(3)
                .installmentGroup(group)
                .status(TransactionStatus.PENDING)
                .tenant(user.getTenant()).account(account).build();
        Transaction paid = Transaction.builder().id(UUID.randomUUID())
                .installmentNumber(2).totalInstallments(3)
                .installmentGroup(group)
                .status(TransactionStatus.PAID)
                .tenant(user.getTenant()).account(account).build();
        Transaction pending = Transaction.builder().id(UUID.randomUUID())
                .installmentNumber(3).totalInstallments(3)
                .installmentGroup(group)
                .status(TransactionStatus.PENDING)
                .tenant(user.getTenant()).account(account).build();

        when(repository.findByIdAndTenant(txId, user.getTenant())).thenReturn(Optional.of(t));
        when(repository.findByInstallmentGroupOrderByInstallmentNumberAsc(group))
                .thenReturn(List.of(t, paid, pending));

        DeleteInstallmentResultDTO result = service.delete(txId, DeleteInstallmentScope.ALL, user);

        verify(repository).deleteAll(List.of(t, pending));
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.skippedPaid()).isEqualTo(1);
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
}
