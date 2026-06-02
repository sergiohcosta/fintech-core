package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.CreditCardDetailsRepository;
import com.fintech.api.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository repository;
    private final CreditCardDetailsRepository creditCardDetailsRepository;

    @Transactional
    public Invoice getOrCreate(Account account, int referenceYear, int referenceMonth) {
        return repository.findByAccountAndReferenceYearAndReferenceMonth(
                        account, referenceYear, referenceMonth)
                .orElseGet(() -> {
                    var details = creditCardDetailsRepository.findByAccount(account)
                            .orElseThrow(() -> new EntityNotFoundException(
                                    "Detalhes do cartão não encontrados para a conta."));
                    int closingDay = details.getClosingDay();
                    int dueDay = details.getDueDay();

                    LocalDate closingDate = LocalDate.of(referenceYear, referenceMonth, closingDay);
                    LocalDate dueDate = dueDay >= closingDay
                            ? LocalDate.of(referenceYear, referenceMonth, dueDay)
                            : LocalDate.of(referenceYear, referenceMonth, dueDay).plusMonths(1);

                    return repository.save(Invoice.builder()
                            .account(account)
                            .tenant(account.getTenant())
                            .referenceYear(referenceYear)
                            .referenceMonth(referenceMonth)
                            .closingDate(closingDate)
                            .dueDate(dueDate)
                            .status(InvoiceStatus.OPEN)
                            .build());
                });
    }

    @Transactional(readOnly = true)
    public Invoice findByIdAndTenant(UUID id, Tenant tenant) {
        return repository.findById(id)
                .filter(inv -> inv.getAccount().getTenant().equals(tenant))
                .orElseThrow(() -> new EntityNotFoundException("Fatura não encontrada."));
    }

    @Transactional(readOnly = true)
    public List<Invoice> findByAccount(Account account) {
        return repository.findByAccountOrderByReferenceYearDescReferenceMonthDesc(account);
    }

    @Transactional
    public Invoice close(UUID id, Tenant tenant) {
        Invoice invoice = findByIdAndTenant(id, tenant);
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw new IllegalStateException(
                    "Só é possível fechar faturas com status OPEN. Status atual: " + invoice.getStatus());
        }
        invoice.setStatus(InvoiceStatus.CLOSED);
        return repository.save(invoice);
    }

    @Transactional
    public Invoice pay(UUID id, Tenant tenant) {
        Invoice invoice = findByIdAndTenant(id, tenant);
        if (invoice.getStatus() != InvoiceStatus.CLOSED) {
            throw new IllegalStateException(
                    "Só é possível pagar faturas com status CLOSED. Status atual: " + invoice.getStatus());
        }
        invoice.setStatus(InvoiceStatus.PAID);
        return repository.save(invoice);
    }
}
