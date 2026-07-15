package com.fintech.api.repository;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.invoice.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
    // totalAmount é líquido: EXPENSE soma, INCOME (estorno/reembolso) abate — mesma regra de sumAmountByInvoice.
    @Query("""
            SELECT i,
                   COALESCE(SUM(
                       CASE WHEN t.status = com.fintech.api.domain.enums.TransactionStatus.CANCELLED THEN 0
                            WHEN t.type = com.fintech.api.domain.enums.TransactionType.INCOME THEN -t.amount
                            ELSE t.amount END
                   ), 0),
                   COUNT(t.id)
            FROM Invoice i
            LEFT JOIN Transaction t ON t.invoice = i
            WHERE i.account = :account
            GROUP BY i
            ORDER BY i.referenceYear DESC, i.referenceMonth DESC
            """)
    List<Object[]> findByAccountWithTotals(@Param("account") Account account);

    // #139: claim atômico do pagamento. O UPDATE ... WHERE status = CLOSED é atômico no banco
    // (READ_COMMITTED): dois pay() concorrentes disputam a mesma linha e apenas UM afeta 1 linha;
    // o perdedor recebe 0 e aborta ANTES de criar o EXPENSE de pagamento (evita débito duplicado).
    @Modifying
    @Query("UPDATE Invoice i SET i.status = :paid WHERE i.id = :id AND i.status = :closed")
    int markAsPaidIfClosed(@Param("id") UUID id,
                           @Param("closed") InvoiceStatus closed,
                           @Param("paid") InvoiceStatus paid);
}
