package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.invoice.InvoiceResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CreditCardDetailsRepository;
import com.fintech.api.repository.InvoiceRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock InvoiceRepository repository;
    @Mock CreditCardDetailsRepository creditCardDetailsRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock AccountRepository accountRepository;
    @InjectMocks InvoiceService service;

    @BeforeEach
    void setup() {
        // self-injection: em testes Mockito o proxy não existe, então apontamos self para
        // a própria instância. O comportamento transacional (REQUIRES_NEW) não é testado
        // aqui — é verificado em testes de integração. O que testamos é o fluxo de retry.
        ReflectionTestUtils.setField(service, "self", service);
    }

    // ---- getOrCreate ----

    @Test
    @DisplayName("getOrCreate: cria nova fatura quando não existe para o período")
    void createsInvoiceWhenNotFound() {
        Account account = buildAccount();
        CreditCardDetails details = buildDetails(account, 5, 15);

        when(repository.findByAccountAndReferenceYearAndReferenceMonth(account, 2026, 12))
                .thenReturn(Optional.empty());
        when(creditCardDetailsRepository.findByAccount(account))
                .thenReturn(Optional.of(details));
        when(repository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        service.getOrCreate(account, 2026, 12);

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(repository).save(captor.capture());
        Invoice saved = captor.getValue();

        assertThat(saved.getReferenceYear()).isEqualTo(2026);
        assertThat(saved.getReferenceMonth()).isEqualTo(12);
        assertThat(saved.getClosingDate()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(saved.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(saved.getStatus()).isEqualTo(InvoiceStatus.OPEN);
    }

    @Test
    @DisplayName("getOrCreate: retorna fatura existente sem criar nova nem alterar status")
    void returnsExistingInvoice() {
        Account account = buildAccount();
        Invoice existing = Invoice.builder()
                .id(UUID.randomUUID()).account(account)
                .referenceYear(2026).referenceMonth(12)
                .closingDate(LocalDate.of(2026, 12, 5))
                .dueDate(LocalDate.of(2026, 12, 15))
                .status(InvoiceStatus.CLOSED).build();

        when(repository.findByAccountAndReferenceYearAndReferenceMonth(account, 2026, 12))
                .thenReturn(Optional.of(existing));

        Invoice result = service.getOrCreate(account, 2026, 12);

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("getOrCreate: dueDay < closingDay → vencimento no mês seguinte ao fechamento")
    void dueDateNextMonthWhenDueDayLessThanClosingDay() {
        Account account = buildAccount();
        CreditCardDetails details = buildDetails(account, 25, 5); // fecha dia 25, vence dia 5

        when(repository.findByAccountAndReferenceYearAndReferenceMonth(account, 2026, 12))
                .thenReturn(Optional.empty());
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(repository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        service.getOrCreate(account, 2026, 12);

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(repository).save(captor.capture());
        // fecha 25/dez, vence 05/jan
        assertThat(captor.getValue().getClosingDate()).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(captor.getValue().getDueDate()).isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    @DisplayName("getOrCreate: dueDay == closingDay → vencimento no mesmo mês")
    void dueDateSameMonthWhenDueDayEqualsClosingDay() {
        Account account = buildAccount();
        CreditCardDetails details = buildDetails(account, 10, 10);

        when(repository.findByAccountAndReferenceYearAndReferenceMonth(account, 2026, 6))
                .thenReturn(Optional.empty());
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(repository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        service.getOrCreate(account, 2026, 6);

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDueDate()).isEqualTo(LocalDate.of(2026, 6, 10));
    }

    @Test
    @DisplayName("getOrCreate: race condition → retry retorna a fatura salva pela thread vencedora")
    void getOrCreateRetriesOnRaceCondition() {
        Account account = buildAccount();
        Invoice winner = buildInvoice(InvoiceStatus.OPEN);
        CreditCardDetails details = buildDetails(account, 5, 15);

        when(repository.findByAccountAndReferenceYearAndReferenceMonth(account, 2026, 6))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(repository.save(any(Invoice.class))).thenThrow(DataIntegrityViolationException.class);

        Invoice result = service.getOrCreate(account, 2026, 6);

        assertThat(result).isSameAs(winner);
        verify(repository, times(2)).findByAccountAndReferenceYearAndReferenceMonth(account, 2026, 6);
        verify(repository).save(any());
    }

    // ---- listDTOs ----

    @Test
    @DisplayName("listDTOs: usa query agregada — não chama sumAmountByInvoice nem countByInvoice por fatura")
    void listDTOsUsesAggregateQuery() {
        Account account = buildAccount();
        Invoice invoice = buildInvoice(InvoiceStatus.OPEN);

        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[]{invoice, new BigDecimal("250.00"), 4L});
        when(repository.findByAccountWithTotals(account)).thenReturn(rows);

        List<InvoiceResponseDTO> result = service.listDTOs(account);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).totalAmount()).isEqualByComparingTo("250.00");
        assertThat(result.get(0).transactionCount()).isEqualTo(4);
        verify(transactionRepository, never()).sumAmountByInvoice(any(), any());
        verify(transactionRepository, never()).countByInvoice(any());
    }

    @Test
    @DisplayName("listDTOs: totalAmount é zero quando query retorna null (fatura sem transações)")
    void listDTOsHandlesNullTotal() {
        Account account = buildAccount();
        Invoice invoice = buildInvoice(InvoiceStatus.OPEN);

        List<Object[]> emptyRows = new java.util.ArrayList<>();
        emptyRows.add(new Object[]{invoice, null, 0L});
        when(repository.findByAccountWithTotals(account)).thenReturn(emptyRows);

        List<InvoiceResponseDTO> result = service.listDTOs(account);

        assertThat(result.get(0).totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.get(0).transactionCount()).isZero();
    }

    // ---- close ----

    @Test
    @DisplayName("close: transiciona OPEN para CLOSED")
    void closesOpenInvoice() {
        Invoice invoice = buildInvoice(InvoiceStatus.OPEN);
        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.sumAmountByInvoice(any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countByInvoice(any())).thenReturn(0L);

        service.close(invoice.getId(), buildTenant(invoice));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CLOSED);
    }

    @Test
    @DisplayName("close: lança IllegalStateException se status não for OPEN")
    void closeRejectsNonOpen() {
        Invoice invoice = buildInvoice(InvoiceStatus.PAID);
        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.close(invoice.getId(), buildTenant(invoice)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPEN");
    }

    // ---- pay ----

    @Test
    @DisplayName("pay: transiciona CLOSED para PAID")
    void payTransitionsToPaid() {
        Invoice invoice = buildInvoice(InvoiceStatus.CLOSED);
        Account source = buildNonCreditCardAccount(invoice.getTenant());
        User user = buildUser();

        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdAndTenant(source.getId(), invoice.getTenant()))
                .thenReturn(Optional.of(source));
        when(transactionRepository.sumAmountByInvoice(any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countByInvoice(any())).thenReturn(0L);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.pay(invoice.getId(), invoice.getTenant(), user, source.getId());

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("pay: lança IllegalStateException se status não for CLOSED")
    void payRejectsNonClosed() {
        Invoice invoice = buildInvoice(InvoiceStatus.OPEN);
        User user = buildUser();
        UUID sourceId = UUID.randomUUID();

        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.pay(invoice.getId(), invoice.getTenant(), user, sourceId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    @DisplayName("pay: cria EXPENSE na conta de origem quando total > 0")
    void payCreatesExpenseTransactionWhenTotalPositive() {
        Invoice invoice = buildInvoice(InvoiceStatus.CLOSED);
        Account source = buildNonCreditCardAccount(invoice.getTenant());
        User user = buildUser();
        BigDecimal total = new BigDecimal("350.00");

        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdAndTenant(source.getId(), invoice.getTenant()))
                .thenReturn(Optional.of(source));
        when(transactionRepository.sumAmountByInvoice(eq(invoice), eq(TransactionStatus.CANCELLED)))
                .thenReturn(total);
        when(transactionRepository.countByInvoice(any())).thenReturn(3L);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.pay(invoice.getId(), invoice.getTenant(), user, source.getId());

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();

        assertThat(saved.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(saved.getAmount()).isEqualByComparingTo(total);
        assertThat(saved.getAccount()).isSameAs(source);
        assertThat(saved.getTenant()).isSameAs(invoice.getTenant());
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getInvoice()).isNull();
        assertThat(saved.getInstallmentGroup()).isNull();
        assertThat(saved.getDescription()).contains("Pagamento fatura");
    }

    @Test
    @DisplayName("pay: não cria EXPENSE quando total é zero (todas transações canceladas)")
    void paySkipsExpenseWhenTotalIsZero() {
        Invoice invoice = buildInvoice(InvoiceStatus.CLOSED);
        Account source = buildNonCreditCardAccount(invoice.getTenant());
        User user = buildUser();

        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdAndTenant(source.getId(), invoice.getTenant()))
                .thenReturn(Optional.of(source));
        when(transactionRepository.sumAmountByInvoice(any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countByInvoice(any())).thenReturn(0L);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.pay(invoice.getId(), invoice.getTenant(), user, source.getId());

        verify(transactionRepository, never()).save(any(Transaction.class));
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("pay: chama batch update para marcar transações PENDING como PAID")
    void payMarksPendingTransactionsAsPaid() {
        Invoice invoice = buildInvoice(InvoiceStatus.CLOSED);
        Account source = buildNonCreditCardAccount(invoice.getTenant());
        User user = buildUser();

        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdAndTenant(source.getId(), invoice.getTenant()))
                .thenReturn(Optional.of(source));
        when(transactionRepository.sumAmountByInvoice(any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countByInvoice(any())).thenReturn(0L);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.pay(invoice.getId(), invoice.getTenant(), user, source.getId());

        verify(transactionRepository).updateStatusByInvoiceAndStatus(
                invoice, TransactionStatus.PENDING, TransactionStatus.PAID);
    }

    @Test
    @DisplayName("pay: lança IllegalStateException se conta de origem for CREDIT_CARD")
    void payRejectsCreditCardSourceAccount() {
        Invoice invoice = buildInvoice(InvoiceStatus.CLOSED);
        Account creditCard = buildAccount();
        creditCard.setType(AccountType.CREDIT_CARD);
        User user = buildUser();

        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdAndTenant(creditCard.getId(), invoice.getTenant()))
                .thenReturn(Optional.of(creditCard));

        assertThatThrownBy(() -> service.pay(invoice.getId(), invoice.getTenant(), user, creditCard.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cartão de crédito");
    }

    @Test
    @DisplayName("pay: lança EntityNotFoundException se conta de origem não for encontrada")
    void payThrowsWhenSourceAccountNotFound() {
        Invoice invoice = buildInvoice(InvoiceStatus.CLOSED);
        User user = buildUser();
        UUID unknownId = UUID.randomUUID();

        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdAndTenant(unknownId, invoice.getTenant()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(invoice.getId(), invoice.getTenant(), user, unknownId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---- helpers ----

    private Tenant buildTenant(Invoice invoice) {
        return invoice.getAccount().getTenant();
    }

    private Account buildAccount() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        Account acc = new Account();
        acc.setId(UUID.randomUUID());
        acc.setTenant(tenant);
        return acc;
    }

    private CreditCardDetails buildDetails(Account account, int closingDay, int dueDay) {
        CreditCardDetails d = new CreditCardDetails();
        d.setAccount(account);
        d.setClosingDay(closingDay);
        d.setDueDay(dueDay);
        return d;
    }

    private Invoice buildInvoice(InvoiceStatus status) {
        Account account = buildAccount();
        return Invoice.builder()
                .id(UUID.randomUUID())
                .account(account)
                .tenant(account.getTenant())
                .referenceYear(2026).referenceMonth(6)
                .closingDate(LocalDate.of(2026, 6, 5))
                .dueDate(LocalDate.of(2026, 6, 15))
                .status(status)
                .build();
    }

    private User buildUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }

    private Account buildNonCreditCardAccount(Tenant tenant) {
        Account acc = new Account();
        acc.setId(UUID.randomUUID());
        acc.setName("Conta Corrente");
        acc.setType(AccountType.CHECKING);
        acc.setTenant(tenant);
        return acc;
    }
}
