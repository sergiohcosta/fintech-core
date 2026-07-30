package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.InvoiceRepository;
import com.fintech.api.repository.TransactionRepository;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #139 — pagamento duplicado de fatura sob concorrência.
 *
 * NÃO usa {@code @Transactional}: as duas threads precisam commitar de verdade para a corrida
 * existir no isolamento do banco (um método @Transactional envolveria tudo numa transação só).
 * As fixtures são commitadas no Postgres dev e removidas manualmente no {@code @AfterEach}.
 *
 * Reprodução: sem o claim atômico (markAsPaidIfClosed), os dois pay() leem CLOSED e ambos criam
 * o EXPENSE de pagamento → mais de um pagamento por fatura. Com o fix, exatamente um por fatura.
 */
@SpringBootTest
class InvoiceServicePaymentConcurrencyTest {

    private static final int ITERATIONS = 30;

    @Autowired InvoiceService invoiceService;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;
    @Autowired com.fintech.api.repository.TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    private Tenant tenant;
    private User user;
    private Account cardAccount;
    private Account sourceAccount;

    @BeforeEach
    void setup() {
        tenant = new Tenant();
        tenant.setName("Tenant Concorrência #139");
        tenant = tenantRepository.save(tenant);

        user = new User();
        user.setName("Pagador");
        user.setEmail("pagador-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("hash");
        user.setRole(UserRole.ADMIN);
        user.setTenant(tenant);
        user = userRepository.save(user);

        cardAccount = accountRepository.save(Account.builder()
                .tenant(tenant).name("Cartão").type(AccountType.CREDIT_CARD)
                .countInLiquidBalance(false).countInNetWorth(true).active(true).build());
        sourceAccount = accountRepository.save(Account.builder()
                .tenant(tenant).name("Conta Corrente").type(AccountType.CHECKING)
                .countInLiquidBalance(true).countInNetWorth(true).active(true).build());
    }

    @AfterEach
    void cleanup() {
        if (tenant == null || tenant.getId() == null) return;
        UUID t = tenant.getId();
        jdbc.update("DELETE FROM transactions WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM invoices WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM accounts WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM users WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM tenants WHERE id = ?", t);
    }

    @Test
    @DisplayName("pay concorrente: cada fatura gera exatamente um pagamento (sem débito duplicado)")
    void concurrentPayCreatesSinglePayment() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                UUID invoiceId = createClosedInvoiceWithPendingTransaction(i);
                racePay(pool, invoiceId);
            }
        } finally {
            pool.shutdownNow();
        }

        // Só pay() escreve na conta de origem (começa vazia) → nº de EXPENSEs = nº de pagamentos.
        Integer payments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE account_id = ?",
                Integer.class, sourceAccount.getId());
        assertThat(payments)
                .as("um pagamento por fatura ao longo de %d iterações concorrentes", ITERATIONS)
                .isEqualTo(ITERATIONS);
    }

    private void racePay(ExecutorService pool, UUID invoiceId) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Runnable payTask = () -> {
            try {
                barrier.await();
                invoiceService.pay(invoiceId, tenant, user, sourceAccount.getId(), null);
            } catch (Exception expectedForLoser) {
                // O perdedor da corrida recebe IllegalStateException — comportamento esperado.
            }
        };
        Future<?> f1 = pool.submit(payTask);
        Future<?> f2 = pool.submit(payTask);
        f1.get();
        f2.get();
    }

    private UUID createClosedInvoiceWithPendingTransaction(int iteration) {
        // ano único por iteração evita colisão no UNIQUE(account, reference_year, reference_month).
        Invoice invoice = invoiceRepository.save(Invoice.builder()
                .account(cardAccount).tenant(tenant)
                .referenceYear(2000 + iteration).referenceMonth(1)
                .closingDate(LocalDate.of(2026, 1, 28)).dueDate(LocalDate.of(2026, 2, 5))
                .status(InvoiceStatus.CLOSED).build());
        transactionRepository.save(Transaction.builder()
                .description("Compra").amount(new BigDecimal("100.00")).date(LocalDate.of(2026, 1, 10))
                .type(TransactionType.EXPENSE).status(TransactionStatus.PENDING)
                .installmentNumber(1).totalInstallments(1)
                .tenant(tenant).user(user).account(cardAccount).invoice(invoice).build());
        return invoice.getId();
    }
}
