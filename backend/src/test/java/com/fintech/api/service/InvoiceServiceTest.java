package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.repository.CreditCardDetailsRepository;
import com.fintech.api.repository.InvoiceRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock InvoiceRepository repository;
    @Mock CreditCardDetailsRepository creditCardDetailsRepository;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks InvoiceService service;

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
    void paysPaidInvoice() {
        Invoice invoice = buildInvoice(InvoiceStatus.CLOSED);
        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.sumAmountByInvoice(any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countByInvoice(any())).thenReturn(0L);

        service.pay(invoice.getId(), buildTenant(invoice));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("pay: lança IllegalStateException se status não for CLOSED")
    void payRejectsNonClosed() {
        Invoice invoice = buildInvoice(InvoiceStatus.OPEN);
        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.pay(invoice.getId(), buildTenant(invoice)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
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
}
