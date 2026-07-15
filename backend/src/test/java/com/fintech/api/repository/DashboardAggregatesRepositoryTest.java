package com.fintech.api.repository;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integração leve contra o Postgres dev (estilo RecurrenceRuleRepositoryTest): {@code @Transactional}
 * garante rollback. Prova, no SQL real, os agregados do dashboard após as correções da Fase 3:
 * #151 (exclui contas arquivadas) e #145 (exclui transferências e pagamentos de fatura).
 */
@SpringBootTest
@Transactional
class DashboardAggregatesRepositoryTest {

    @Autowired TransactionRepository transactionRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired InvoiceRepository invoiceRepository;

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    private Tenant tenant;
    private User user;

    @BeforeEach
    void setup() {
        tenant = new Tenant();
        tenant.setName("Tenant Dashboard Fase3");
        tenant = tenantRepository.save(tenant);
        user = new User();
        user.setName("U");
        user.setEmail("dash-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("h");
        user.setRole(com.fintech.api.domain.enums.UserRole.ADMIN);
        user.setTenant(tenant);
        user = userRepository.save(user);
    }

    @Test
    @DisplayName("#151 sumNetLiquidBalanceByTenant exclui contas arquivadas")
    void netLiquidBalanceExcludesArchivedAccounts() {
        Account active = persistAccount("Ativa", AccountType.CHECKING, true, true);
        Account archived = persistAccount("Arquivada", AccountType.CHECKING, true, false);
        persistTx(active, TransactionType.INCOME, "1000.00", TransactionStatus.PAID, LocalDate.of(2026, 6, 10), null, null);
        persistTx(archived, TransactionType.INCOME, "500.00", TransactionStatus.PAID, LocalDate.of(2026, 6, 10), null, null);

        BigDecimal net = transactionRepository.sumNetLiquidBalanceByTenant(
                tenant, TransactionType.INCOME, TransactionStatus.PAID);

        assertThat(net).isEqualByComparingTo("1000.00"); // só a conta ativa
    }

    @Test
    @DisplayName("#145 transferências não inflam income/expense nem a contagem do dashboard")
    void transfersExcludedFromAggregates() {
        Account a = persistAccount("Corrente A", AccountType.CHECKING, true, true);
        Account b = persistAccount("Corrente B", AccountType.CHECKING, true, true);
        // despesa normal no período
        persistTx(a, TransactionType.EXPENSE, "100.00", TransactionStatus.PAID, LocalDate.of(2026, 6, 15), null, null);
        // par de transferência (mesmo transferId) — não deve contar
        UUID transferId = UUID.randomUUID();
        persistTx(a, TransactionType.EXPENSE, "500.00", TransactionStatus.PAID, LocalDate.of(2026, 6, 16), transferId, null);
        persistTx(b, TransactionType.INCOME, "500.00", TransactionStatus.PAID, LocalDate.of(2026, 6, 16), transferId, null);

        BigDecimal expense = transactionRepository.sumByTenantAndTypeAndPeriod(
                tenant, TransactionType.EXPENSE, TransactionStatus.CANCELLED, START, END);
        BigDecimal income = transactionRepository.sumByTenantAndTypeAndPeriod(
                tenant, TransactionType.INCOME, TransactionStatus.CANCELLED, START, END);
        long count = transactionRepository.countByTenantAndPeriod(
                tenant, TransactionStatus.CANCELLED, START, END);

        assertThat(expense).isEqualByComparingTo("100.00"); // exclui a perna EXPENSE da transferência
        assertThat(income).isEqualByComparingTo("0.00");    // exclui a perna INCOME
        assertThat(count).isEqualTo(1);                     // só a despesa normal
    }

    @Test
    @DisplayName("#145 pagamento de fatura (paidInvoice) não entra nos agregados")
    void invoicePaymentExcludedFromAggregates() {
        Account checking = persistAccount("Corrente", AccountType.CHECKING, true, true);
        Account card = persistAccount("Cartão", AccountType.CREDIT_CARD, false, true);
        Invoice invoice = invoiceRepository.save(Invoice.builder()
                .account(card).tenant(tenant).referenceYear(2026).referenceMonth(5)
                .closingDate(LocalDate.of(2026, 6, 1)).dueDate(LocalDate.of(2026, 6, 10))
                .status(InvoiceStatus.PAID).build());

        persistTx(checking, TransactionType.EXPENSE, "100.00", TransactionStatus.PAID, LocalDate.of(2026, 6, 5), null, null);
        // pagamento da fatura — marcado com paidInvoice, deve ser excluído
        persistTx(checking, TransactionType.EXPENSE, "300.00", TransactionStatus.PAID, LocalDate.of(2026, 6, 8), null, invoice);

        BigDecimal expense = transactionRepository.sumByTenantAndTypeAndPeriod(
                tenant, TransactionType.EXPENSE, TransactionStatus.CANCELLED, START, END);

        assertThat(expense).isEqualByComparingTo("100.00"); // exclui o pagamento de 300
    }

    private Account persistAccount(String name, AccountType type, boolean liquid, boolean active) {
        return accountRepository.save(Account.builder()
                .tenant(tenant).name(name).type(type)
                .countInLiquidBalance(liquid).countInNetWorth(true).active(active).build());
    }

    private void persistTx(Account account, TransactionType type, String amount,
                           TransactionStatus status, LocalDate date, UUID transferId, Invoice paidInvoice) {
        transactionRepository.save(Transaction.builder()
                .description("tx").amount(new BigDecimal(amount)).date(date)
                .type(type).status(status).installmentNumber(1).totalInstallments(1)
                .tenant(tenant).user(user).account(account)
                .transferId(transferId).paidInvoice(paidInvoice).build());
    }
}
