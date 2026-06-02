package com.fintech.api.repository;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.invoice.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByAccountAndReferenceYearAndReferenceMonth(
            Account account, int referenceYear, int referenceMonth);

    List<Invoice> findByAccountOrderByReferenceYearDescReferenceMonthDesc(Account account);
}
