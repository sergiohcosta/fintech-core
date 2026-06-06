package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository repository;
    private final CreditCardDetailsRepository creditCardDetailsRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

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
        // Comparação por ID evita Lombok canEqual() em proxy detached do SecurityContext
        return repository.findById(id)
                .filter(inv -> inv.getAccount().getTenant().getId().equals(tenant.getId()))
                .orElseThrow(() -> new EntityNotFoundException("Fatura não encontrada."));
    }

    // Retorna DTO com total e contagem — tudo dentro da transação para evitar LazyInitializationException
    @Transactional(readOnly = true)
    public InvoiceResponseDTO getDTO(UUID id, Tenant tenant) {
        return buildDTO(findByIdAndTenant(id, tenant));
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponseDTO> listDTOs(Account account) {
        return repository.findByAccountOrderByReferenceYearDescReferenceMonthDesc(account)
                .stream()
                .map(this::buildDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Invoice> findByAccount(Account account) {
        return repository.findByAccountOrderByReferenceYearDescReferenceMonthDesc(account);
    }

    @Transactional
    public InvoiceResponseDTO close(UUID id, Tenant tenant) {
        Invoice invoice = findByIdAndTenant(id, tenant);
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw new IllegalStateException(
                    "Só é possível fechar faturas com status OPEN. Status atual: " + invoice.getStatus());
        }
        invoice.setStatus(InvoiceStatus.CLOSED);
        log.info("Fatura fechada [invoiceId={} referencia={}/{} tenantId={}]",
                id, invoice.getReferenceMonth(), invoice.getReferenceYear(), tenant.getId());
        return buildDTO(repository.save(invoice));
    }

    @Transactional
    public InvoiceResponseDTO pay(UUID id, Tenant tenant, User user, UUID sourceAccountId) {
        Invoice invoice = findByIdAndTenant(id, tenant);
        if (invoice.getStatus() != InvoiceStatus.CLOSED) {
            throw new IllegalStateException(
                    "Só é possível pagar faturas com status CLOSED. Status atual: " + invoice.getStatus());
        }

        Account sourceAccount = accountRepository.findByIdAndTenant(sourceAccountId, tenant)
                .orElseThrow(() -> new EntityNotFoundException("Conta de origem não encontrada."));

        if (sourceAccount.getType() == AccountType.CREDIT_CARD) {
            throw new IllegalArgumentException(
                    "Não é possível pagar uma fatura com outra conta de cartão de crédito.");
        }

        BigDecimal total = transactionRepository.sumAmountByInvoice(invoice, TransactionStatus.CANCELLED);

        if (total.compareTo(BigDecimal.ZERO) > 0) {
            Transaction payment = Transaction.builder()
                    .type(TransactionType.EXPENSE)
                    .status(TransactionStatus.PAID)
                    .amount(total)
                    .date(LocalDate.now())
                    .description(String.format("Pagamento fatura %s %02d/%d",
                            invoice.getAccount().getName(),
                            invoice.getReferenceMonth(),
                            invoice.getReferenceYear()))
                    .account(sourceAccount)
                    .tenant(invoice.getTenant())
                    .user(user)
                    .build();
            transactionRepository.save(payment);
        }

        transactionRepository.updateStatusByInvoiceAndStatus(
                invoice, TransactionStatus.PENDING, TransactionStatus.PAID);

        invoice.setStatus(InvoiceStatus.PAID);
        log.info("Fatura paga [invoiceId={} referencia={}/{} tenantId={} sourceAccountId={}]",
                id, invoice.getReferenceMonth(), invoice.getReferenceYear(),
                tenant.getId(), sourceAccountId);
        return buildDTO(repository.save(invoice));
    }

    private InvoiceResponseDTO buildDTO(Invoice invoice) {
        var total = transactionRepository.sumAmountByInvoice(invoice, TransactionStatus.CANCELLED);
        var count = transactionRepository.countByInvoice(invoice);
        return InvoiceResponseDTO.fromEntity(invoice, total, count);
    }
}
