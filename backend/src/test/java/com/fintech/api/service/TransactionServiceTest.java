package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.DeleteInstallmentScope;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.transaction.InstallmentPreviewDTO;
import com.fintech.api.dto.transaction.InstallmentPreviewRequestDTO;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionUpdateDTO;
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.exception.BusinessException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.CreditCardDetailsRepository;
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
import java.time.YearMonth;
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
    @Mock CreditCardDetailsRepository creditCardDetailsRepository;
    @Mock InvoiceService invoiceService;
    @Mock BudgetItemRepository budgetItemRepository;
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

    // #136 — a soma das parcelas deve fechar EXATAMENTE com o total da compra. Com HALF_EVEN
    // uniforme em todas as parcelas a soma divergia: 100/3 → 33,33×3 = 99,99 (falta 1 centavo);
    // 1000/7 → 142,86×7 = 1000,02 (sobram 2). @ParameterizedTest dá mocks frescos por caso.
    // Este teste usa conta CHECKING (não-cartão): sem descarte de fatura fechada, o invariante
    // soma == total vale integralmente. Com descarte (cartão), a soma passa a valer apenas
    // sobre as parcelas efetivamente criadas — ver parcelasDescartadasEmFaturaFechada*.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "100.00, 3", "1000.00, 7", "10.00, 3", "0.10, 3" })
    @DisplayName("Parcelamento sem descarte: soma das parcelas fecha exatamente com o total")
    void installmentSumMatchesTotal(BigDecimal total, int installments) {
        User user = buildUser();
        Account account = buildAccount(user);
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Compra parcelada", total, LocalDate.now(),
                TransactionType.EXPENSE, null, installments, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(installments)).save(captor.capture());
        BigDecimal sum = captor.getAllValues().stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(total);
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
    @DisplayName("createTransfer lança BusinessException quando contas são iguais")
    void createTransferRejectsEqualAccounts() {
        User user = buildUser();
        UUID sameId = UUID.randomUUID();
        TransferRequestDTO dto = new TransferRequestDTO(
                sameId, sameId, new BigDecimal("100.00"), LocalDate.now(), null);

        assertThatThrownBy(() -> service.createTransfer(dto, user))
                .isInstanceOf(BusinessException.class)
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

    // #138 — perna de transferência não pode ser mutada/excluída isoladamente (quebra double-entry).
    @Test
    @DisplayName("#138 update de perna de transferência é rejeitado")
    void updateRejectsTransferLeg() {
        User user = buildUser();
        UUID id = UUID.randomUUID();
        Transaction leg = Transaction.builder().id(id)
                .type(TransactionType.EXPENSE).account(buildAccount(user))
                .transferId(UUID.randomUUID()).tenant(user.getTenant()).build();
        when(repository.findByIdAndTenant(id, user.getTenant())).thenReturn(Optional.of(leg));

        TransactionUpdateDTO dto = new TransactionUpdateDTO(
                "novo", new BigDecimal("10.00"), null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(id, dto, user))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("transferência");
    }

    @Test
    @DisplayName("#138 delete de perna de transferência é rejeitado (não deixa a irmã órfã)")
    void deleteRejectsTransferLeg() {
        User user = buildUser();
        UUID id = UUID.randomUUID();
        Transaction leg = Transaction.builder().id(id)
                .type(TransactionType.EXPENSE).account(buildAccount(user))
                .transferId(UUID.randomUUID()).tenant(user.getTenant()).build();
        when(repository.findByIdAndTenant(id, user.getTenant())).thenReturn(Optional.of(leg));

        assertThatThrownBy(() -> service.delete(id, DeleteInstallmentScope.SINGLE, user))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("transferência");
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("delete rejeita transação vinculada a item de planejamento (evita FK violation em prod)")
    void deleteRejectsBudgetLinkedTransaction() {
        User user = buildUser();
        UUID id = UUID.randomUUID();
        Transaction t = Transaction.builder().id(id)
                .type(TransactionType.EXPENSE).account(buildAccount(user))
                .status(TransactionStatus.PENDING).tenant(user.getTenant()).build();
        BudgetItem item = BudgetItem.builder().id(UUID.randomUUID()).transaction(t).build();
        when(repository.findByIdAndTenant(id, user.getTenant())).thenReturn(Optional.of(t));
        when(budgetItemRepository.findByTransaction(t)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.delete(id, DeleteInstallmentScope.SINGLE, user))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("planejamento");
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteAll(any());
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

    @Test
    @DisplayName("Cria transação em CREDIT_CARD atribuindo fatura corretamente")
    void createsCreditCardTransactionWithInvoice() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        Invoice invoice = Invoice.builder()
                .id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(5)
                .closingDate(LocalDate.of(2026, 6, 5))
                .dueDate(LocalDate.of(2026, 6, 15))
                .status(InvoiceStatus.OPEN).build();

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Mercado", new BigDecimal("100.00"), LocalDate.of(2026, 6, 3),
                TransactionType.EXPENSE, null, 1, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.getOrCreate(account, 2026, 5)).thenReturn(invoice);
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(1);
        verify(invoiceService).getOrCreate(account, 2026, 5);
    }

    @Test
    @DisplayName("Parcelas em CREDIT_CARD têm todas a mesma data de compra mas faturas diferentes")
    void installmentsOnCreditCardHaveSameDateDifferentInvoices() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        LocalDate purchaseDate = LocalDate.of(2026, 6, 3);

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook", new BigDecimal("3000.00"), purchaseDate,
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceService.getOrCreate(any(), anyInt(), anyInt()))
                .thenAnswer(i -> Invoice.builder()
                        .id(UUID.randomUUID()).account(account)
                        .referenceYear(i.getArgument(1))
                        .referenceMonth(i.getArgument(2))
                        .closingDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 5))
                        .dueDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 15))
                        .status(InvoiceStatus.OPEN).build());
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user);

        verify(invoiceService).getOrCreate(account, 2026, 5);
        verify(invoiceService).getOrCreate(account, 2026, 6);
        verify(invoiceService).getOrCreate(account, 2026, 7);
    }

    @Test
    @DisplayName("Compra pós-fechamento em CREDIT_CARD vai para o mês seguinte")
    void purchaseAfterClosingGoesToNextMonth() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        LocalDate purchaseDate = LocalDate.of(2026, 6, 8); // APÓS fechamento dia 5

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Janta", new BigDecimal("80.00"), purchaseDate,
                TransactionType.EXPENSE, null, 1, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.getOrCreate(any(), anyInt(), anyInt()))
                .thenReturn(Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(6)
                        .closingDate(LocalDate.of(2026, 7, 5))
                        .dueDate(LocalDate.of(2026, 7, 15))
                        .status(InvoiceStatus.OPEN).build());
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user);

        // Compra do dia 8 (pós-fechamento dia 5) → fatura de junho (mês 6): dia > fechamento → mês da compra
        verify(invoiceService).getOrCreate(account, 2026, 6);
    }

    @Test
    @DisplayName("Parcelas em conta não-CREDIT_CARD mantêm date + i meses")
    void nonCreditCardInstallmentsKeepDatePlusMonths() {
        User user = buildUser();
        Account account = buildAccount(user); // CHECKING
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Parcela", new BigDecimal("600.00"), LocalDate.of(2026, 6, 1),
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(3)).save(captor.capture());
        List<Transaction> saved = captor.getAllValues();

        assertThat(saved.get(0).getDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(saved.get(1).getDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(saved.get(2).getDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        saved.forEach(t -> assertThat(t.getInvoice()).isNull());
    }

    @Test
    @DisplayName("Âncora explícita de fatura ignora resolveInvoiceMonth (parcela em andamento com data antiga)")
    void anchorInvoiceMonthOverridesResolveInvoiceMonth() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        // Dia 13, closingDay=5 → dia > closingDay → SEM âncora, resolveInvoiceMonth mandaria
        // pra referenceMonth=março (mês da própria compra). Com âncora, deve ignorar isso.
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Parcela em andamento", new BigDecimal("50.00"), LocalDate.of(2026, 3, 13),
                TransactionType.EXPENSE, null, null, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.getOrCreate(any(), anyInt(), anyInt()))
                .thenReturn(Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(7)
                        .closingDate(LocalDate.of(2026, 8, 5))
                        .dueDate(LocalDate.of(2026, 8, 15))
                        .status(InvoiceStatus.OPEN).build());
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user, YearMonth.of(2026, 7));

        verify(invoiceService).getOrCreate(account, 2026, 7);
        verify(invoiceService, never()).getOrCreate(account, 2026, 3);
    }

    @Test
    @DisplayName("Parcelas futuras cascateiam a partir da âncora, não da data de compra")
    void installmentsWithAnchorCascadeFromAnchorMonth() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook parcelado", new BigDecimal("3000.00"), LocalDate.of(2026, 3, 13),
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceService.getOrCreate(any(), anyInt(), anyInt()))
                .thenAnswer(i -> Invoice.builder()
                        .id(UUID.randomUUID()).account(account)
                        .referenceYear(i.getArgument(1))
                        .referenceMonth(i.getArgument(2))
                        .closingDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 5))
                        .dueDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 15))
                        .status(InvoiceStatus.OPEN).build());
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user, YearMonth.of(2026, 7));

        verify(invoiceService).getOrCreate(account, 2026, 7);
        verify(invoiceService).getOrCreate(account, 2026, 8);
        verify(invoiceService).getOrCreate(account, 2026, 9);
    }

    // ---- descarte de parcelas em fatura fechada/paga (manual, skipClosedInvoices=true) ----

    @Test
    @DisplayName("create manual descarta parcela que cairia em fatura PAID (não cria transação nem fatura)")
    void parcelasDescartadasEmFaturaPaga() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        // fecha dia 5 → compra dia 3 cai na fatura de maio; parcela 2 → junho (PAID)
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook", new BigDecimal("3000.00"), LocalDate.of(2026, 6, 3),
                TransactionType.EXPENSE, null, 2, null, account.getId());

        Invoice junhoPaga = Invoice.builder().id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(6)
                .closingDate(LocalDate.of(2026, 7, 5)).dueDate(LocalDate.of(2026, 7, 15))
                .status(InvoiceStatus.PAID).build();

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(account, 2026, 5)).thenReturn(Optional.empty());
        when(invoiceService.findExisting(account, 2026, 6)).thenReturn(Optional.of(junhoPaga));
        when(invoiceService.getOrCreate(account, 2026, 5)).thenReturn(
                Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(5)
                        .closingDate(LocalDate.of(2026, 6, 5)).dueDate(LocalDate.of(2026, 6, 15))
                        .status(InvoiceStatus.OPEN).build());
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(1);
        // Parcela 1 (maio, fatura inexistente → nasce OPEN) é criada com número 1/2;
        // parcela 2 (junho, PAID) é descartada — sem getOrCreate para aquele mês.
        assertThat(result.get(0).installmentNumber()).isEqualTo(1);
        assertThat(result.get(0).totalInstallments()).isEqualTo(2);
        verify(invoiceService).getOrCreate(account, 2026, 5);
        verify(invoiceService, never()).getOrCreate(account, 2026, 6);
    }

    @Test
    @DisplayName("create manual descarta parcela que cairia em fatura CLOSED")
    void parcelasDescartadasEmFaturaFechada() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Mercado", new BigDecimal("200.00"), LocalDate.of(2026, 6, 3),
                TransactionType.EXPENSE, null, 2, null, account.getId());

        Invoice maioFechada = Invoice.builder().id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(5)
                .closingDate(LocalDate.of(2026, 6, 5)).dueDate(LocalDate.of(2026, 6, 15))
                .status(InvoiceStatus.CLOSED).build();

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(account, 2026, 5)).thenReturn(Optional.of(maioFechada));
        when(invoiceService.findExisting(account, 2026, 6)).thenReturn(Optional.empty());
        when(invoiceService.getOrCreate(account, 2026, 6)).thenReturn(
                Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(6)
                        .closingDate(LocalDate.of(2026, 7, 5)).dueDate(LocalDate.of(2026, 7, 15))
                        .status(InvoiceStatus.OPEN).build());
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).installmentNumber()).isEqualTo(2);
        assertThat(result.get(0).totalInstallments()).isEqualTo(2);
        verify(invoiceService, never()).getOrCreate(account, 2026, 5);
    }

    @Test
    @DisplayName("Parcelas descartadas preservam numeração: 3/6..6/6 quando 1 e 2 caem em fatura fechada")
    void numeracaoPreservadaComDescartes() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(2);
        details.setDueDay(10);

        // Compra 08/07 (pós-fechamento dia 2) → fatura jul/2026; 6x → jul..dez
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook", new BigDecimal("6000.00"), LocalDate.of(2026, 7, 8),
                TransactionType.EXPENSE, null, 6, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        // set..dez não existem (stub genérico PRIMEIRO; os específicos de jul/ago abaixo
        // sobrescrevem — em Mockito o último stub que casa vence).
        when(invoiceService.findExisting(eq(account), eq(2026), anyInt())).thenReturn(Optional.empty());
        when(invoiceService.findExisting(account, 2026, 7)).thenReturn(Optional.of(
                Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(7)
                        .closingDate(LocalDate.of(2026, 8, 2)).dueDate(LocalDate.of(2026, 8, 10))
                        .status(InvoiceStatus.PAID).build()));
        when(invoiceService.findExisting(account, 2026, 8)).thenReturn(Optional.of(
                Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(8)
                        .closingDate(LocalDate.of(2026, 9, 2)).dueDate(LocalDate.of(2026, 9, 10))
                        .status(InvoiceStatus.CLOSED).build()));
        when(invoiceService.getOrCreate(any(), anyInt(), anyInt()))
                .thenAnswer(i -> Invoice.builder()
                        .id(UUID.randomUUID()).account(account)
                        .referenceYear(i.<Integer>getArgument(1))
                        .referenceMonth(i.<Integer>getArgument(2))
                        .closingDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 2))
                        .dueDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 10))
                        .status(InvoiceStatus.OPEN).build());
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(TransactionResponseDTO::installmentNumber)
                .containsExactly(3, 4, 5, 6);
        result.forEach(t -> assertThat(t.totalInstallments()).isEqualTo(6));
        verify(invoiceService, never()).getOrCreate(account, 2026, 7);
        verify(invoiceService, never()).getOrCreate(account, 2026, 8);
        verify(invoiceService).getOrCreate(account, 2026, 9);
        verify(invoiceService).getOrCreate(account, 2026, 12);
    }

    @Test
    @DisplayName("Todas as parcelas descartadas → lista vazia e nenhum InstallmentGroup salvo")
    void todasDescartadasNaoCriaNada() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook", new BigDecimal("3000.00"), LocalDate.of(2026, 6, 3),
                TransactionType.EXPENSE, null, 2, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(eq(account), anyInt(), anyInt())).thenReturn(Optional.of(
                Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(5)
                        .closingDate(LocalDate.of(2026, 6, 5)).dueDate(LocalDate.of(2026, 6, 15))
                        .status(InvoiceStatus.PAID).build()));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).isEmpty();
        verify(installmentGroupRepository, never()).save(any());
        verify(repository, never()).save(any());
        verify(invoiceService, never()).getOrCreate(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Conta não-cartão nunca consulta findExisting (comportamento inalterado)")
    void nonCreditCardNeverQueriesInvoiceStatus() {
        User user = buildUser();
        Account account = buildAccount(user); // CHECKING
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Parcela", new BigDecimal("600.00"), LocalDate.of(2026, 6, 1),
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user);

        verify(invoiceService, never()).findExisting(any(), anyInt(), anyInt());
        verify(repository, times(3)).save(any());
    }

    @Test
    @DisplayName("Sobrecarga de 3 args (importação) NÃO descarta parcela em fatura fechada")
    void anchorOverloadDoesNotSkipClosedInvoices() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Fatura Itaú", new BigDecimal("100.00"), LocalDate.of(2026, 3, 13),
                TransactionType.EXPENSE, null, null, null, account.getId());

        Invoice julhoFechada = Invoice.builder().id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(7)
                .closingDate(LocalDate.of(2026, 8, 5)).dueDate(LocalDate.of(2026, 8, 15))
                .status(InvoiceStatus.CLOSED).build();

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.getOrCreate(account, 2026, 7)).thenReturn(julhoFechada);
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user, YearMonth.of(2026, 7));

        assertThat(result).hasSize(1);
        verify(invoiceService).getOrCreate(account, 2026, 7);
        // A âncora explícita do documento manda: a importação nunca consulta status.
        verify(invoiceService, never()).findExisting(any(), anyInt(), anyInt());
    }

    // ---- regressão do escopo: avulsa no cartão NÃO descarta (só N>1) ----

    @Test
    @DisplayName("Transação avulsa no cartão (N=1) em fatura PAID é criada normalmente")
    void singleCreditCardTransactionInPaidInvoiceIsCreated() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        // closingDay=5, compra dia 3 → fatura maio/2026 (PAID): avulsa anexa, como antes.
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Mercado", new BigDecimal("100.00"), LocalDate.of(2026, 6, 3),
                TransactionType.EXPENSE, null, null, null, account.getId());

        Invoice maioPaga = Invoice.builder().id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(5)
                .closingDate(LocalDate.of(2026, 6, 5)).dueDate(LocalDate.of(2026, 6, 15))
                .status(InvoiceStatus.PAID).build();

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.getOrCreate(account, 2026, 5)).thenReturn(maioPaga);
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(1);
        verify(invoiceService).getOrCreate(account, 2026, 5);
        // Avulsa (N=1) não consulta status: o descarte é exclusivo de compra parcelada.
        verify(invoiceService, never()).findExisting(any(), anyInt(), anyInt());
    }

    // ---- bordas de fatura existente OPEN e descarte intermediário ----

    @Test
    @DisplayName("create manual mantém parcela que cai em fatura existente OPEN")
    void createKeepsInstallmentInOpenInvoice() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook", new BigDecimal("3000.00"), LocalDate.of(2026, 6, 3),
                TransactionType.EXPENSE, null, 2, null, account.getId());

        Invoice maioAberta = Invoice.builder().id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(5)
                .closingDate(LocalDate.of(2026, 6, 5)).dueDate(LocalDate.of(2026, 6, 15))
                .status(InvoiceStatus.OPEN).build();

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(account, 2026, 5)).thenReturn(Optional.of(maioAberta));
        when(invoiceService.findExisting(account, 2026, 6)).thenReturn(Optional.empty());
        when(invoiceService.getOrCreate(account, 2026, 5)).thenReturn(maioAberta);
        when(invoiceService.getOrCreate(account, 2026, 6)).thenReturn(
                Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(6)
                        .closingDate(LocalDate.of(2026, 7, 5)).dueDate(LocalDate.of(2026, 7, 15))
                        .status(InvoiceStatus.OPEN).build());
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TransactionResponseDTO::installmentNumber)
                .containsExactly(1, 2);
        verify(invoiceService).getOrCreate(account, 2026, 5);
        verify(invoiceService).getOrCreate(account, 2026, 6);
    }

    @Test
    @DisplayName("Fatura fechada num mês INTERMEDIÁRIO descarta só a parcela do meio (1 e 3 criadas)")
    void intermediateClosedMonthDiscardsOnlyMiddleInstallment() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(2);
        details.setDueDay(10);

        // Compra 08/07 (pós-fechamento dia 2) → fatura jul/2026; 3x → jul, ago, set
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook", new BigDecimal("300.00"), LocalDate.of(2026, 7, 8),
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(account, 2026, 7)).thenReturn(Optional.empty());
        when(invoiceService.findExisting(account, 2026, 8)).thenReturn(Optional.of(
                Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(8)
                        .closingDate(LocalDate.of(2026, 9, 2)).dueDate(LocalDate.of(2026, 9, 10))
                        .status(InvoiceStatus.CLOSED).build()));
        when(invoiceService.findExisting(account, 2026, 9)).thenReturn(Optional.empty());
        when(invoiceService.getOrCreate(any(), anyInt(), anyInt()))
                .thenAnswer(i -> Invoice.builder()
                        .id(UUID.randomUUID()).account(account)
                        .referenceYear(i.<Integer>getArgument(1))
                        .referenceMonth(i.<Integer>getArgument(2))
                        .closingDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 2))
                        .dueDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 10))
                        .status(InvoiceStatus.OPEN).build());
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TransactionResponseDTO::installmentNumber)
                .containsExactly(1, 3);
        verify(invoiceService).getOrCreate(account, 2026, 7);
        verify(invoiceService, never()).getOrCreate(account, 2026, 8);
        verify(invoiceService).getOrCreate(account, 2026, 9);
    }

    @Test
    @DisplayName("Descarte da ÚLTIMA parcela: resíduo de centavos some com ela, anteriores mantêm DOWN")
    void discardingLastInstallmentDropsCentsResidue() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        // 100.00 em 3x → 33.33 / 33.33 / 33.34 (última absorve resíduo).
        // 3ª descartada → criadas somam 66.66; a última CRIADA (2ª) mantém 33.33,
        // não absorve o resíduo — intencional: parcelas derivam do total pedido.
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Mercado", new BigDecimal("100.00"), LocalDate.of(2026, 6, 3),
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(eq(account), eq(2026), anyInt())).thenReturn(Optional.empty());
        when(invoiceService.findExisting(account, 2026, 7)).thenReturn(Optional.of(
                Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(7)
                        .closingDate(LocalDate.of(2026, 8, 5)).dueDate(LocalDate.of(2026, 8, 15))
                        .status(InvoiceStatus.PAID).build()));
        when(invoiceService.getOrCreate(any(), anyInt(), anyInt()))
                .thenAnswer(i -> Invoice.builder()
                        .id(UUID.randomUUID()).account(account)
                        .referenceYear(i.<Integer>getArgument(1))
                        .referenceMonth(i.<Integer>getArgument(2))
                        .closingDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 5))
                        .dueDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 15))
                        .status(InvoiceStatus.OPEN).build());
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        // fecha dia 5, compra dia 3 → parcelas caem em maio(5), junho(6), julho(7)
        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TransactionResponseDTO::amount)
                .extracting(BigDecimal::doubleValue)
                .containsExactly(33.33, 33.33);
        verify(invoiceService, never()).getOrCreate(account, 2026, 7);
    }

    @Test
    @DisplayName("Importação com âncora NULA e fatura fechada NÃO descarta (regressão)")
    void nullAnchorImportDoesNotSkipClosedInvoice() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        // Importação sem fatura-alvo no documento passa anchor=null; a regra de descarte
        // é chaveada pela FLAG (skipClosedInvoices=false), não por anchor==null (D1).
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Lançamento importado", new BigDecimal("50.00"), LocalDate.of(2026, 6, 8),
                TransactionType.EXPENSE, null, 2, null, account.getId());

        Invoice junhoFechada = Invoice.builder().id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(6)
                .closingDate(LocalDate.of(2026, 7, 5)).dueDate(LocalDate.of(2026, 7, 15))
                .status(InvoiceStatus.CLOSED).build();

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        // getOrCreate devolve a fatura existente SEM olhar status (comportamento real do
        // repository): junho já existe fechada e a parcela-âncora é anexada a ela mesmo assim.
        when(invoiceService.getOrCreate(account, 2026, 6)).thenReturn(junhoFechada);
        when(invoiceService.getOrCreate(account, 2026, 7)).thenReturn(
                Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(7)
                        .closingDate(LocalDate.of(2026, 8, 5)).dueDate(LocalDate.of(2026, 8, 15))
                        .status(InvoiceStatus.OPEN).build());
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        // dia 8 > closingDay 5 → fatura junho/2026 (fechada); âncora null → cascata junho+julho
        List<TransactionResponseDTO> result = service.create(dto, user, null);

        assertThat(result).hasSize(2);
        verify(invoiceService).getOrCreate(account, 2026, 6);
        verify(invoiceService).getOrCreate(account, 2026, 7);
        verify(invoiceService, never()).findExisting(any(), anyInt(), anyInt());
        // sanity: a fatura de junho existia fechada e mesmo assim recebeu a parcela
        assertThat(junhoFechada.getStatus()).isEqualTo(InvoiceStatus.CLOSED);
    }

    // ---- materializeFromRule: recusa em fatura fechada/paga (D6) ----

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = InvoiceStatus.class, names = { "CLOSED", "PAID" })
    @DisplayName("materializeFromRule lança BusinessConflictException quando a fatura está fechada/paga")
    void materializeFromRuleRejectsClosedInvoice(InvoiceStatus closedStatus) {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        RecurrenceRule rule = RecurrenceRule.builder()
                .id(UUID.randomUUID()).tenant(user.getTenant())
                .description("Assinatura")
                .baseAmount(new BigDecimal("50.00"))
                .type(TransactionType.EXPENSE)
                .account(account)
                .rrule("FREQ=MONTHLY")
                .startDate(LocalDate.of(2026, 1, 1))
                .build();

        // closingDay=5, ocorrência dia 8 (pós-fechamento) → fatura de maio/2026
        Invoice fechada = Invoice.builder().id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(5)
                .closingDate(LocalDate.of(2026, 6, 5)).dueDate(LocalDate.of(2026, 6, 15))
                .status(closedStatus).build();

        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(account, 2026, 5)).thenReturn(Optional.of(fechada));

        assertThatThrownBy(() -> service.materializeFromRule(
                rule, LocalDate.of(2026, 5, 8), null, null, user))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("2026-05");

        verify(repository, never()).save(any());
        verify(invoiceService, never()).getOrCreate(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("materializeFromRule materializa normalmente quando a fatura não existe ou está OPEN")
    void materializeFromRuleCreatesWhenInvoiceOpenOrMissing() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        RecurrenceRule rule = RecurrenceRule.builder()
                .id(UUID.randomUUID()).tenant(user.getTenant())
                .description("Assinatura")
                .baseAmount(new BigDecimal("50.00"))
                .type(TransactionType.EXPENSE)
                .account(account)
                .rrule("FREQ=MONTHLY")
                .startDate(LocalDate.of(2026, 1, 1))
                .build();

        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(account, 2026, 5)).thenReturn(Optional.empty());
        when(invoiceService.getOrCreate(account, 2026, 5)).thenReturn(
                Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(5)
                        .closingDate(LocalDate.of(2026, 6, 5)).dueDate(LocalDate.of(2026, 6, 15))
                        .status(InvoiceStatus.OPEN).build());
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        TransactionResponseDTO result = service.materializeFromRule(
                rule, LocalDate.of(2026, 5, 8), null, null, user);

        assertThat(result.invoiceId()).isNotNull();
        verify(repository).save(any(Transaction.class));
    }

    @Test
    @DisplayName("materializeFromRule materializa quando a fatura EXISTE OPEN (sem 409)")
    void materializeFromRuleAcceptsExistingOpenInvoice() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        RecurrenceRule rule = RecurrenceRule.builder()
                .id(UUID.randomUUID()).tenant(user.getTenant())
                .description("Assinatura")
                .baseAmount(new BigDecimal("50.00"))
                .type(TransactionType.EXPENSE)
                .account(account)
                .rrule("FREQ=MONTHLY")
                .startDate(LocalDate.of(2026, 1, 1))
                .build();

        Invoice maioAberta = Invoice.builder().id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(5)
                .closingDate(LocalDate.of(2026, 6, 5)).dueDate(LocalDate.of(2026, 6, 15))
                .status(InvoiceStatus.OPEN).build();

        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(account, 2026, 5)).thenReturn(Optional.of(maioAberta));
        when(invoiceService.getOrCreate(account, 2026, 5)).thenReturn(maioAberta);
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        TransactionResponseDTO result = service.materializeFromRule(
                rule, LocalDate.of(2026, 5, 8), null, null, user);

        assertThat(result.invoiceId()).isEqualTo(maioAberta.getId());
        verify(repository).save(any(Transaction.class));
    }

    // ---- previewInstallments (D5) ----

    @Test
    @DisplayName("previewInstallments: parcela em fatura PAID → willCreate=false; sem efeito colateral")
    void previewInstallmentsMarksClosedInvoices() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(2);
        details.setDueDay(10);

        // Compra 08/07 (pós-fechamento dia 2) → fatura jul/2026; 3x → jul, ago, set
        InstallmentPreviewRequestDTO dto = new InstallmentPreviewRequestDTO(
                new BigDecimal("300.00"), LocalDate.of(2026, 7, 8), 3, account.getId());

        Invoice julhoPaga = Invoice.builder().id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(7)
                // Datas PERSISTIDAS divergentes do schedule recalculado (fechamento dia 2,
                // vencimento dia 10): closingDay/dueDay do cartão mudaram depois — o preview
                // deve mostrar a fatura real, não o recálculo.
                .closingDate(LocalDate.of(2026, 8, 5)).dueDate(LocalDate.of(2026, 8, 20))
                .status(InvoiceStatus.PAID).build();

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(account, 2026, 7)).thenReturn(Optional.of(julhoPaga));
        when(invoiceService.findExisting(account, 2026, 8)).thenReturn(Optional.empty());
        when(invoiceService.findExisting(account, 2026, 9)).thenReturn(Optional.empty());

        List<InstallmentPreviewDTO> result = service.previewInstallments(dto, user);

        assertThat(result).hasSize(3);
        InstallmentPreviewDTO primeira = result.get(0);
        assertThat(primeira.installmentNumber()).isEqualTo(1);
        assertThat(primeira.totalInstallments()).isEqualTo(3);
        assertThat(primeira.willCreate()).isFalse();
        assertThat(primeira.invoiceId()).isEqualTo(julhoPaga.getId());
        assertThat(primeira.invoiceStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(primeira.referenceYear()).isEqualTo(2026);
        assertThat(primeira.referenceMonth()).isEqualTo(7);
        // datas da fatura PERSISTIDA (não o schedule recalculado com closingDay=2/dueDay=10)
        assertThat(primeira.closingDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(primeira.dueDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(result.get(1).willCreate()).isTrue();
        assertThat(result.get(1).invoiceId()).isNull();
        assertThat(result.get(1).invoiceStatus()).isNull();
        // parcela sem fatura usa o schedule recalculado
        assertThat(result.get(1).closingDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(result.get(1).dueDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(result.get(2).willCreate()).isTrue();
        // Preview é read-only: nenhuma fatura materializada, nenhuma transação salva.
        verify(invoiceService, never()).getOrCreate(any(), anyInt(), anyInt());
        verify(repository, never()).save(any());
        verify(installmentGroupRepository, never()).save(any());
    }

    @Test
    @DisplayName("previewInstallments: fatura EXISTE OPEN → willCreate=true com id/status e datas persistidas")
    void previewInstallmentsMarksExistingOpenInvoiceAsWillCreate() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(2);
        details.setDueDay(10);

        InstallmentPreviewRequestDTO dto = new InstallmentPreviewRequestDTO(
                new BigDecimal("100.00"), LocalDate.of(2026, 7, 8), 1, account.getId());

        Invoice julhoAberta = Invoice.builder().id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(7)
                .closingDate(LocalDate.of(2026, 8, 5)).dueDate(LocalDate.of(2026, 8, 20))
                .status(InvoiceStatus.OPEN).build();

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(account, 2026, 7)).thenReturn(Optional.of(julhoAberta));

        List<InstallmentPreviewDTO> result = service.previewInstallments(dto, user);

        assertThat(result).hasSize(1);
        InstallmentPreviewDTO linha = result.get(0);
        assertThat(linha.willCreate()).isTrue();
        assertThat(linha.invoiceId()).isEqualTo(julhoAberta.getId());
        assertThat(linha.invoiceStatus()).isEqualTo(InvoiceStatus.OPEN);
        // fatura existente: datas persistidas, não o schedule recalculado (dia 2/10)
        assertThat(linha.closingDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(linha.dueDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        verify(invoiceService, never()).getOrCreate(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("previewInstallments: conta não-cartão → BusinessException")
    void previewInstallmentsRejectsNonCreditCardAccount() {
        User user = buildUser();
        Account account = buildAccount(user); // CHECKING
        InstallmentPreviewRequestDTO dto = new InstallmentPreviewRequestDTO(
                new BigDecimal("300.00"), LocalDate.of(2026, 7, 8), 3, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.previewInstallments(dto, user))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cartão");
    }

    @Test
    @DisplayName("previewInstallments: totalInstallments ausente → 1 parcela")
    void previewInstallmentsDefaultsToSingleInstallment() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(2);
        details.setDueDay(10);

        InstallmentPreviewRequestDTO dto = new InstallmentPreviewRequestDTO(
                new BigDecimal("300.00"), LocalDate.of(2026, 7, 8), null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(account, 2026, 7)).thenReturn(Optional.empty());

        List<InstallmentPreviewDTO> result = service.previewInstallments(dto, user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).amount()).isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    @DisplayName("previewInstallments: divide centavos igual ao create (DOWN + última absorve resíduo)")
    void previewInstallmentsSplitsCentsLikeCreate() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(2);
        details.setDueDay(10);

        InstallmentPreviewRequestDTO dto = new InstallmentPreviewRequestDTO(
                new BigDecimal("100.00"), LocalDate.of(2026, 7, 8), 3, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.findExisting(eq(account), eq(2026), anyInt())).thenReturn(Optional.empty());

        List<InstallmentPreviewDTO> result = service.previewInstallments(dto, user);

        assertThat(result).extracting(InstallmentPreviewDTO::amount)
                .extracting(BigDecimal::doubleValue)
                .containsExactly(33.33, 33.33, 33.34);
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

    private Account buildCreditCardAccount(User user) {
        return Account.builder()
                .id(UUID.randomUUID())
                .type(AccountType.CREDIT_CARD)
                .tenant(user.getTenant())
                .build();
    }
}
