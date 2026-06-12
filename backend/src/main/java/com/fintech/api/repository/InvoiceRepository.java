package com.fintech.api.repository;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.invoice.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByAccountAndReferenceYearAndReferenceMonth(
            Account account, int referenceYear, int referenceMonth);

    List<Invoice> findByAccountOrderByReferenceYearDescReferenceMonthDesc(Account account);

    // Retorna (Invoice, totalAmount, transactionCount) em uma única query com GROUP BY,
    // eliminando o N+1 que ocorre ao chamar sumAmountByInvoice + countByInvoice por fatura.
    @Query("""
            SELECT i,
                   COALESCE(SUM(CASE WHEN t.status <> com.fintech.api.domain.enums.TransactionStatus.CANCELLED
                                     THEN t.amount ELSE 0 END), 0),
                   COUNT(t.id)
            FROM Invoice i
            LEFT JOIN Transaction t ON t.invoice = i
            WHERE i.account = :account
            GROUP BY i
            ORDER BY i.referenceYear DESC, i.referenceMonth DESC
            """)
    List<Object[]> findByAccountWithTotals(@Param("account") Account account);
}
