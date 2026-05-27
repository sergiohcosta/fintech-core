package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
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
        Account to = buildAccount(user);
        UUID fromId = from.getId();
        UUID toId = to.getId();

        when(accountRepository.findByIdAndTenant(fromId, user.getTenant())).thenReturn(Optional.of(from));
        when(accountRepository.findByIdAndTenant(toId, user.getTenant())).thenReturn(Optional.of(to));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.createTransfer(fromId, toId, new BigDecimal("500.00"), LocalDate.now(), user);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(2)).save(captor.capture());

        List<Transaction> saved = captor.getAllValues();
        Transaction expense = saved.stream().filter(t -> t.getType() == TransactionType.EXPENSE).findFirst().orElseThrow();
        Transaction income  = saved.stream().filter(t -> t.getType() == TransactionType.INCOME).findFirst().orElseThrow();

        assertThat(expense.getAccount()).isEqualTo(from);
        assertThat(income.getAccount()).isEqualTo(to);
        assertThat(expense.getTransferId()).isNotNull();
        assertThat(expense.getTransferId()).isEqualTo(income.getTransferId());
        assertThat(expense.getAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("findById lança EntityNotFoundException para transação de outro tenant")
    void findByIdThrowsForOtherTenant() {
        User user = buildUser();
        when(repository.findByIdAndTenant(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(UUID.randomUUID(), user))
                .isInstanceOf(EntityNotFoundException.class);
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
