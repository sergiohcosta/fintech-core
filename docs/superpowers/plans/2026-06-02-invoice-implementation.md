# Invoice (Fatura) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar o modelo completo de Fatura (Invoice) para contas CREDIT_CARD — entidade Invoice com ciclo de vida OPEN→CLOSED→PAID, atribuição automática de transações via closingDay, e preview de fatura no formulário frontend.

**Architecture:** Lazy creation — Invoice nasce quando a primeira transação do período é criada. `Transaction.invoice` é um FK nullable; apenas contas CREDIT_CARD recebem vínculo. Para parcelamentos, `Transaction.date` permanece como data de compra (igual para todas as parcelas); o mês financeiro é determinado por `invoice.dueDate`. Para contas não-CREDIT_CARD, a lógica de `date + i meses` permanece inalterada.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, Flyway, JUnit 5 + Mockito, Angular 21 Zoneless, Vitest, OpenAPI + Orval

---

## Mapa de arquivos

### Backend — novos
- `backend/src/main/java/com/fintech/api/domain/enums/InvoiceStatus.java`
- `backend/src/main/java/com/fintech/api/domain/invoice/Invoice.java`
- `backend/src/main/java/com/fintech/api/repository/InvoiceRepository.java`
- `backend/src/main/java/com/fintech/api/service/InvoiceService.java`
- `backend/src/main/java/com/fintech/api/dto/invoice/InvoiceResponseDTO.java`
- `backend/src/main/java/com/fintech/api/controller/InvoiceController.java`
- `backend/src/main/resources/db/migration/V9__invoices.sql`
- `backend/src/test/java/com/fintech/api/service/InvoiceServiceTest.java`

### Backend — alterados
- `backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java` — adiciona campo `invoice`
- `backend/src/main/java/com/fintech/api/dto/transaction/TransactionResponseDTO.java` — adiciona `invoiceId`, `invoiceDueDate`, `invoiceStatus`
- `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java` — novo query com fetch de invoice + query por invoice + queries de dashboard
- `backend/src/main/java/com/fintech/api/service/TransactionService.java` — CREDIT_CARD detection + invoice assignment + invoiceId filter
- `backend/src/main/java/com/fintech/api/service/DashboardService.java` — usa invoice.dueDate para cartão
- `backend/src/main/resources/static/openapi.yaml` — Invoice schemas/paths + updates em TransactionResponseDTO + invoiceId param
- `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java` — novos mocks + casos CREDIT_CARD
- `backend/src/test/java/com/fintech/api/service/DashboardServiceTest.java` — ajuste para nova query

### Frontend — alterados
- `frontend/src/app/features/transaction/transaction-form/installment-preview.ts`
- `frontend/src/app/features/transaction/transaction-form/transaction-form.spec.ts`
- `frontend/src/app/features/transaction/transaction-list/transaction-list.html`
- `frontend/src/app/core/api/` — regenerado via Orval

---

## Task 1: Migration V9 — tabela invoices + FK em transactions

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__invoices.sql`

- [ ] **Step 1: Criar arquivo de migration**

```sql
-- V9__invoices.sql

CREATE TABLE invoices (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID         NOT NULL REFERENCES accounts(id),
    tenant_id        UUID         NOT NULL REFERENCES tenants(id),
    reference_year   INT          NOT NULL,
    reference_month  INT          NOT NULL,
    closing_date     DATE         NOT NULL,
    due_date         DATE         NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (account_id, reference_year, reference_month)
);

CREATE INDEX idx_invoices_tenant      ON invoices(tenant_id);
CREATE INDEX idx_invoices_account     ON invoices(account_id);

ALTER TABLE transactions
    ADD COLUMN invoice_id UUID REFERENCES invoices(id);

CREATE INDEX idx_transactions_invoice ON transactions(invoice_id);
```

- [ ] **Step 2: Verificar que o backend sobe sem erros de migration**

```bash
cd backend && ./mvnw spring-boot:run -q 2>&1 | grep -E "(Flyway|ERROR|Started)" | head -20
```

Expected: linha `Successfully applied 1 migration to schema "public"` ou `Current version of schema "public": 9`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V9__invoices.sql
git commit -m "feat(db): migration V9 — tabela invoices e FK invoice_id em transactions"
```

---

## Task 2: Domain layer — InvoiceStatus, Invoice, InvoiceRepository, Transaction.invoice

**Files:**
- Create: `backend/src/main/java/com/fintech/api/domain/enums/InvoiceStatus.java`
- Create: `backend/src/main/java/com/fintech/api/domain/invoice/Invoice.java`
- Create: `backend/src/main/java/com/fintech/api/repository/InvoiceRepository.java`
- Modify: `backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java`

- [ ] **Step 1: Criar InvoiceStatus**

```java
// backend/src/main/java/com/fintech/api/domain/enums/InvoiceStatus.java
package com.fintech.api.domain.enums;

public enum InvoiceStatus {
    OPEN,
    CLOSED,
    PAID
}
```

- [ ] **Step 2: Criar entidade Invoice**

```java
// backend/src/main/java/com/fintech/api/domain/invoice/Invoice.java
package com.fintech.api.domain.invoice;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.tenant.Tenant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "invoices")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @ToString.Exclude
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude
    private Tenant tenant;

    @Column(nullable = false)
    private Integer referenceYear;

    @Column(nullable = false)
    private Integer referenceMonth;

    @Column(nullable = false)
    private LocalDate closingDate;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.OPEN;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: Criar InvoiceRepository**

```java
// backend/src/main/java/com/fintech/api/repository/InvoiceRepository.java
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
```

- [ ] **Step 4: Adicionar campo `invoice` à entidade Transaction**

No arquivo `backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java`, adicionar após o campo `installmentGroup` (linha ~87):

```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    @ToString.Exclude
    private Invoice invoice;
```

Adicionar o import no topo do arquivo:
```java
import com.fintech.api.domain.invoice.Invoice;
```

- [ ] **Step 5: Verificar compilação**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS sem erros.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/domain/enums/InvoiceStatus.java \
        backend/src/main/java/com/fintech/api/domain/invoice/Invoice.java \
        backend/src/main/java/com/fintech/api/repository/InvoiceRepository.java \
        backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java
git commit -m "feat(domain): entidade Invoice com status OPEN/CLOSED/PAID e FK em Transaction"
```

---

## Task 3: InvoiceService — getOrCreate, findByIdAndTenant, close, pay

**Files:**
- Create: `backend/src/main/java/com/fintech/api/service/InvoiceService.java`
- Create: `backend/src/test/java/com/fintech/api/service/InvoiceServiceTest.java`

- [ ] **Step 1: Escrever o teste antes de implementar**

```java
// backend/src/test/java/com/fintech/api/service/InvoiceServiceTest.java
package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.CreditCardDetailsRepository;
import com.fintech.api.repository.InvoiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        Invoice result = service.getOrCreate(account, 2026, 12);

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
```

- [ ] **Step 2: Executar os testes e confirmar que falham**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvoiceServiceTest -q 2>&1 | tail -10
```

Expected: FAIL com `InvoiceService cannot be resolved` ou similar.

- [ ] **Step 3: Implementar InvoiceService**

```java
// backend/src/main/java/com/fintech/api/service/InvoiceService.java
package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
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
                    CreditCardDetails details = creditCardDetailsRepository.findByAccount(account)
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
```

- [ ] **Step 4: Executar os testes e confirmar que passam**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvoiceServiceTest -q 2>&1 | tail -10
```

Expected: `Tests run: 8, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/InvoiceService.java \
        backend/src/test/java/com/fintech/api/service/InvoiceServiceTest.java
git commit -m "feat(service): InvoiceService com getOrCreate, close e pay"
```

---

## Task 4: Atualizar TransactionResponseDTO com campos de invoice

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/dto/transaction/TransactionResponseDTO.java`
- Modify: `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`

- [ ] **Step 1: Atualizar TransactionResponseDTO**

Substituir o record completo em `TransactionResponseDTO.java`:

```java
package com.fintech.api.dto.transaction;

import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponseDTO(
        UUID id,
        String description,
        BigDecimal amount,
        LocalDate date,
        TransactionType type,
        TransactionStatus status,
        String installmentLabel,
        Integer installmentNumber,
        Integer totalInstallments,
        String categoryName,
        UUID categoryId,
        boolean categoryArchived,
        String accountName,
        UUID accountId,
        UUID transferId,
        UUID installmentGroupId,
        String installmentGroupDescription,
        UUID invoiceId,
        LocalDate invoiceDueDate,
        InvoiceStatus invoiceStatus
) {
    public static TransactionResponseDTO fromEntity(Transaction t) {
        String installLabel = null;
        if (t.getTotalInstallments() != null && t.getTotalInstallments() > 1) {
            installLabel = t.getInstallmentNumber() + "/" + t.getTotalInstallments();
        }
        var group = t.getInstallmentGroup();
        var invoice = t.getInvoice();
        return new TransactionResponseDTO(
                t.getId(),
                t.getDescription(),
                t.getAmount(),
                t.getDate(),
                t.getType(),
                t.getStatus(),
                installLabel,
                t.getInstallmentNumber(),
                t.getTotalInstallments(),
                t.getCategory() != null ? t.getCategory().getName() : null,
                t.getCategory() != null ? t.getCategory().getId() : null,
                t.getCategory() != null && t.getCategory().getDeletedAt() != null,
                t.getAccount() != null ? t.getAccount().getName() : null,
                t.getAccount() != null ? t.getAccount().getId() : null,
                t.getTransferId(),
                group != null ? group.getId() : null,
                group != null ? group.getDescription() : null,
                invoice != null ? invoice.getId() : null,
                invoice != null ? invoice.getDueDate() : null,
                invoice != null ? invoice.getStatus() : null
        );
    }
}
```

- [ ] **Step 2: Atualizar a query findAllByTenantWithDetails para incluir invoice no fetch**

No arquivo `TransactionRepository.java`, atualizar a query `findAllByTenantWithDetails`:

```java
    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.installmentGroup
            LEFT JOIN FETCH t.category
            LEFT JOIN FETCH t.account
            LEFT JOIN FETCH t.invoice
            WHERE t.tenant = :tenant
            ORDER BY t.date DESC
            """)
    List<Transaction> findAllByTenantWithDetails(@Param("tenant") Tenant tenant);
```

Adicionar também query para filtrar por fatura:

```java
    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.installmentGroup
            LEFT JOIN FETCH t.category
            LEFT JOIN FETCH t.account
            WHERE t.tenant = :tenant AND t.invoice = :invoice
            ORDER BY t.date DESC
            """)
    List<Transaction> findAllByTenantAndInvoiceWithDetails(
            @Param("tenant") Tenant tenant,
            @Param("invoice") Invoice invoice);
```

Adicionar o import necessário no topo:
```java
import com.fintech.api.domain.invoice.Invoice;
```

- [ ] **Step 3: Verificar compilação**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/dto/transaction/TransactionResponseDTO.java \
        backend/src/main/java/com/fintech/api/repository/TransactionRepository.java
git commit -m "feat(dto): TransactionResponseDTO com campos invoiceId, invoiceDueDate, invoiceStatus"
```

---

## Task 5: Atualizar TransactionService — CREDIT_CARD detection e invoice assignment

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java`

- [ ] **Step 1: Escrever novos casos de teste em TransactionServiceTest**

Adicionar ao arquivo `TransactionServiceTest.java` (após os testes existentes):

```java
    // Adicionar no topo da classe:
    @Mock InvoiceService invoiceService;
    @Mock CreditCardDetailsRepository creditCardDetailsRepository;

    // Adicionar testes:

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
                .referenceYear(2026).referenceMonth(6)
                .closingDate(LocalDate.of(2026, 6, 5))
                .dueDate(LocalDate.of(2026, 6, 15))
                .status(InvoiceStatus.OPEN).build();

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Mercado", new BigDecimal("100.00"), LocalDate.of(2026, 6, 3),
                TransactionType.EXPENSE, null, 1, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.getOrCreate(account, 2026, 6)).thenReturn(invoice);
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(1);
        verify(invoiceService).getOrCreate(account, 2026, 6);
    }

    @Test
    @DisplayName("Parcelas em CREDIT_CARD têm todas a mesma data de compra mas faturas diferentes")
    void installmentsOnCreditCardHaveSameDateDifferentInvoices() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        LocalDate purchaseDate = LocalDate.of(2026, 6, 3); // antes do fechamento (dia 5)

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
                        .closingDate(LocalDate.of(i.getArgument(1), i.getArgument(2), 5))
                        .dueDate(LocalDate.of(i.getArgument(1), i.getArgument(2), 15))
                        .status(InvoiceStatus.OPEN).build());
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user);

        // Verifica que getOrCreate foi chamado 3 vezes, para meses 6, 7 e 8
        verify(invoiceService).getOrCreate(account, 2026, 6);
        verify(invoiceService).getOrCreate(account, 2026, 7);
        verify(invoiceService).getOrCreate(account, 2026, 8);
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
                        .referenceYear(2026).referenceMonth(7)
                        .closingDate(LocalDate.of(2026, 7, 5))
                        .dueDate(LocalDate.of(2026, 7, 15))
                        .status(InvoiceStatus.OPEN).build());
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user);

        // Compra do dia 8 (pós-fechamento dia 5) → fatura de julho (mês 7)
        verify(invoiceService).getOrCreate(account, 2026, 7);
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
```

Adicionar também os builders de apoio que faltam na classe de teste:

```java
    private Account buildCreditCardAccount(User user) {
        Account acc = new Account();
        acc.setId(UUID.randomUUID());
        acc.setTenant(user.getTenant());
        acc.setType(AccountType.CREDIT_CARD);
        return acc;
    }
```

Verificar que o builder `buildAccount` existente define `type = AccountType.CHECKING` (ou outro não-CREDIT_CARD). Se não tiver `setType`, adicionar.

- [ ] **Step 2: Executar os novos testes e confirmar falha**

```bash
cd backend && ./mvnw test -pl . -Dtest=TransactionServiceTest -q 2>&1 | tail -15
```

Expected: FAIL — os novos mocks não existem no service ainda.

- [ ] **Step 3: Atualizar TransactionService**

Substituir o conteúdo completo de `TransactionService.java`:

```java
package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.DeleteInstallmentScope;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionUpdateDTO;
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.dto.transfer.TransferResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.CreditCardDetailsRepository;
import com.fintech.api.repository.InstallmentGroupRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository repository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final InstallmentGroupRepository installmentGroupRepository;
    private final CreditCardDetailsRepository creditCardDetailsRepository;
    private final InvoiceService invoiceService;

    @Transactional(readOnly = true)
    public List<TransactionResponseDTO> findAll(User user, UUID invoiceId) {
        if (invoiceId != null) {
            Invoice invoice = invoiceService.findByIdAndTenant(invoiceId, user.getTenant());
            return repository.findAllByTenantAndInvoiceWithDetails(user.getTenant(), invoice)
                    .stream().map(TransactionResponseDTO::fromEntity).toList();
        }
        return repository.findAllByTenantWithDetails(user.getTenant())
                .stream().map(TransactionResponseDTO::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public TransactionResponseDTO findById(UUID id, User user) {
        return TransactionResponseDTO.fromEntity(
                repository.findByIdAndTenant(id, user.getTenant())
                        .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada.")));
    }

    @Transactional
    public List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user) {
        Category category = resolveCategory(dto.categoryId(), user);
        Account account = resolveAccount(dto.accountId(), user);

        int installments = (dto.totalInstallments() != null && dto.totalInstallments() > 1)
                ? dto.totalInstallments() : 1;
        BigDecimal installmentAmount = dto.amount()
                .divide(BigDecimal.valueOf(installments), 2, RoundingMode.HALF_EVEN);

        boolean isCreditCard = AccountType.CREDIT_CARD.equals(account.getType());
        CreditCardDetails creditCardDetails = null;
        if (isCreditCard) {
            creditCardDetails = creditCardDetailsRepository.findByAccount(account)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Detalhes do cartão não encontrados para a conta."));
        }

        InstallmentGroup group = null;
        if (installments > 1) {
            group = installmentGroupRepository.save(InstallmentGroup.builder()
                    .description(dto.description())
                    .totalAmount(dto.amount())
                    .totalInstallments(installments)
                    .account(account)
                    .category(category)
                    .tenant(user.getTenant())
                    .build());
        }

        final InstallmentGroup finalGroup = group;
        final CreditCardDetails finalDetails = creditCardDetails;
        List<Transaction> created = new ArrayList<>();
        for (int i = 0; i < installments; i++) {
            Invoice invoice = null;
            LocalDate transactionDate;

            if (isCreditCard) {
                YearMonth invoiceMonth = resolveInvoiceMonth(dto.date(), finalDetails.getClosingDay())
                        .plusMonths(i);
                invoice = invoiceService.getOrCreate(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue());
                transactionDate = dto.date(); // data de compra — igual em todas as parcelas
            } else {
                transactionDate = dto.date().plusMonths(i); // comportamento original para contas não-cartão
            }

            created.add(repository.save(Transaction.builder()
                    .description(dto.description())
                    .amount(installmentAmount)
                    .date(transactionDate)
                    .type(dto.type())
                    .status(dto.status() != null ? dto.status() : TransactionStatus.PENDING)
                    .installmentNumber(i + 1)
                    .totalInstallments(installments)
                    .installmentGroup(finalGroup)
                    .invoice(invoice)
                    .tenant(user.getTenant())
                    .user(user)
                    .category(category)
                    .account(account)
                    .build()));
        }
        return created.stream().map(TransactionResponseDTO::fromEntity).toList();
    }

    @Transactional
    public TransferResponseDTO createTransfer(TransferRequestDTO dto, User user) {
        if (dto.fromAccountId().equals(dto.toAccountId())) {
            throw new IllegalArgumentException("As contas de origem e destino devem ser diferentes.");
        }
        Account from = resolveAccount(dto.fromAccountId(), user);
        Account to   = resolveAccount(dto.toAccountId(), user);
        UUID transferId = UUID.randomUUID();
        String description = (dto.description() != null && !dto.description().isBlank())
                ? dto.description() : "Transferência";

        Transaction expense = repository.save(Transaction.builder()
                .description(description)
                .amount(dto.amount()).date(dto.date())
                .type(TransactionType.EXPENSE)
                .status(TransactionStatus.PAID)
                .installmentNumber(1).totalInstallments(1)
                .tenant(user.getTenant()).user(user)
                .account(from).transferId(transferId)
                .build());

        Transaction income = repository.save(Transaction.builder()
                .description(description)
                .amount(dto.amount()).date(dto.date())
                .type(TransactionType.INCOME)
                .status(TransactionStatus.PAID)
                .installmentNumber(1).totalInstallments(1)
                .tenant(user.getTenant()).user(user)
                .account(to).transferId(transferId)
                .build());

        return new TransferResponseDTO(
                transferId, expense.getId(), income.getId(),
                dto.amount(), dto.date(), description,
                from.getName(), to.getName());
    }

    @Transactional
    public void deleteTransfer(UUID transferId, User user) {
        List<Transaction> legs = repository.findByTransferIdAndTenant(transferId, user.getTenant());
        if (legs.isEmpty()) {
            throw new EntityNotFoundException("Transferência não encontrada.");
        }
        repository.deleteAll(legs);
    }

    @Transactional
    public TransactionResponseDTO update(UUID id, TransactionUpdateDTO dto, User user) {
        Transaction t = repository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada."));

        if (dto.description() != null) t.setDescription(dto.description());
        if (dto.amount() != null)      t.setAmount(dto.amount());
        if (dto.date() != null)        t.setDate(dto.date());
        if (dto.type() != null)        t.setType(dto.type());
        if (dto.status() != null)      t.setStatus(dto.status());
        if (dto.categoryId() != null)  t.setCategory(resolveCategory(dto.categoryId(), user));
        if (dto.accountId() != null)   t.setAccount(resolveAccount(dto.accountId(), user));

        List<String> propagate = dto.propagate();
        if (propagate != null && !propagate.isEmpty() && t.getInstallmentGroup() != null) {
            List<Transaction> futures = repository.findFuturePendingInGroup(
                    t.getInstallmentGroup(), t.getInstallmentNumber(), TransactionStatus.PENDING);
            for (Transaction future : futures) {
                if (propagate.contains("description") && dto.description() != null)
                    future.setDescription(dto.description());
                if (propagate.contains("amount") && dto.amount() != null)
                    future.setAmount(dto.amount());
                if (propagate.contains("categoryId") && dto.categoryId() != null)
                    future.setCategory(resolveCategory(dto.categoryId(), user));
                if (propagate.contains("accountId") && dto.accountId() != null)
                    future.setAccount(resolveAccount(dto.accountId(), user));
                if (propagate.contains("status") && dto.status() != null)
                    future.setStatus(dto.status());
            }
        }

        return TransactionResponseDTO.fromEntity(t);
    }

    @Transactional
    public DeleteInstallmentResultDTO delete(UUID id, DeleteInstallmentScope scope, User user) {
        Transaction t = repository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada."));

        if (scope == DeleteInstallmentScope.SINGLE || t.getInstallmentGroup() == null) {
            repository.delete(t);
            return new DeleteInstallmentResultDTO(1, 0);
        }

        InstallmentGroup group = t.getInstallmentGroup();
        List<Transaction> candidates = switch (scope) {
            case THIS_AND_NEXT -> repository
                    .findByInstallmentGroupAndInstallmentNumberGreaterThanEqualOrderByInstallmentNumberAsc(
                            group, t.getInstallmentNumber());
            case ALL -> repository.findByInstallmentGroupOrderByInstallmentNumberAsc(group);
            default -> List.of(t);
        };

        List<Transaction> toDelete = candidates.stream()
                .filter(tx -> tx.getStatus() != TransactionStatus.PAID)
                .toList();
        int skipped = candidates.size() - toDelete.size();

        repository.deleteAll(toDelete);
        return new DeleteInstallmentResultDTO(toDelete.size(), skipped);
    }

    private YearMonth resolveInvoiceMonth(LocalDate purchaseDate, int closingDay) {
        return purchaseDate.getDayOfMonth() <= closingDay
                ? YearMonth.from(purchaseDate)
                : YearMonth.from(purchaseDate).plusMonths(1);
    }

    private Category resolveCategory(UUID categoryId, User user) {
        if (categoryId == null) return null;
        return categoryRepository.findByIdAndTenantIdAndDeletedAtIsNull(categoryId, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
    }

    private Account resolveAccount(UUID accountId, User user) {
        return accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
    }
}
```

- [ ] **Step 4: Atualizar TransactionController para receber invoiceId e chamar findAll com 2 argumentos**

No arquivo `TransactionController.java`, atualizar o método `listTransactions`:

```java
    @Override
    @GetMapping
    public ResponseEntity<List<TransactionResponseDTO>> listTransactions(
            @RequestParam(value = "invoiceId", required = false) UUID invoiceId) {
        return ResponseEntity.ok(service.findAll(getAuthenticatedUser(), invoiceId));
    }
```

- [ ] **Step 5: Executar os testes e confirmar que passam**

```bash
cd backend && ./mvnw test -pl . -Dtest=TransactionServiceTest -q 2>&1 | tail -15
```

Expected: `Tests run: X, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/TransactionService.java \
        backend/src/main/java/com/fintech/api/controller/TransactionController.java \
        backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java
git commit -m "feat(service): TransactionService detecta CREDIT_CARD e atribui Invoice por parcela"
```

---

## Task 6: InvoiceResponseDTO + InvoiceController

**Files:**
- Create: `backend/src/main/java/com/fintech/api/dto/invoice/InvoiceResponseDTO.java`
- Create: `backend/src/main/java/com/fintech/api/controller/InvoiceController.java`

- [ ] **Step 1: Criar InvoiceResponseDTO**

```java
// backend/src/main/java/com/fintech/api/dto/invoice/InvoiceResponseDTO.java
package com.fintech.api.dto.invoice;

import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.invoice.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceResponseDTO(
        UUID id,
        UUID accountId,
        String accountName,
        int referenceMonth,
        int referenceYear,
        String label,
        LocalDate closingDate,
        LocalDate dueDate,
        InvoiceStatus status,
        BigDecimal totalAmount,
        long transactionCount
) {
    private static final String[] MONTH_NAMES = {
        "", "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
        "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"
    };

    public static InvoiceResponseDTO fromEntity(Invoice invoice, BigDecimal totalAmount, long transactionCount) {
        return new InvoiceResponseDTO(
                invoice.getId(),
                invoice.getAccount().getId(),
                invoice.getAccount().getName(),
                invoice.getReferenceMonth(),
                invoice.getReferenceYear(),
                MONTH_NAMES[invoice.getReferenceMonth()] + "/" + invoice.getReferenceYear(),
                invoice.getClosingDate(),
                invoice.getDueDate(),
                invoice.getStatus(),
                totalAmount,
                transactionCount
        );
    }
}
```

- [ ] **Step 2: Adicionar queries de agregação ao TransactionRepository**

No arquivo `TransactionRepository.java`, adicionar ao final da interface:

```java
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.invoice = :invoice AND t.status <> :excluded
            """)
    BigDecimal sumAmountByInvoice(
            @Param("invoice") Invoice invoice,
            @Param("excluded") TransactionStatus excluded);

    long countByInvoice(Invoice invoice);
```

- [ ] **Step 3: Criar InvoiceController**

```java
// backend/src/main/java/com/fintech/api/controller/InvoiceController.java
package com.fintech.api.controller;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.invoice.InvoiceResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.TransactionRepository;
import com.fintech.api.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @GetMapping
    public ResponseEntity<List<InvoiceResponseDTO>> listInvoices(@RequestParam UUID accountId) {
        User user = getAuthenticatedUser();
        var account = accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
        List<InvoiceResponseDTO> result = invoiceService.findByAccount(account).stream()
                .map(inv -> toDTO(inv))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceResponseDTO> getInvoice(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        Invoice invoice = invoiceService.findByIdAndTenant(id, user.getTenant());
        return ResponseEntity.ok(toDTO(invoice));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<InvoiceResponseDTO> closeInvoice(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        Invoice invoice = invoiceService.close(id, user.getTenant());
        return ResponseEntity.ok(toDTO(invoice));
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<InvoiceResponseDTO> payInvoice(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        Invoice invoice = invoiceService.pay(id, user.getTenant());
        return ResponseEntity.ok(toDTO(invoice));
    }

    private InvoiceResponseDTO toDTO(Invoice invoice) {
        BigDecimal total = transactionRepository.sumAmountByInvoice(invoice, TransactionStatus.CANCELLED);
        long count = transactionRepository.countByInvoice(invoice);
        return InvoiceResponseDTO.fromEntity(invoice, total, count);
    }

    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 4: Verificar compilação**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/dto/invoice/InvoiceResponseDTO.java \
        backend/src/main/java/com/fintech/api/controller/InvoiceController.java \
        backend/src/main/java/com/fintech/api/repository/TransactionRepository.java
git commit -m "feat(api): InvoiceController com listagem, detalhe e transições de status"
```

---

## Task 7: Atualizar DashboardService para usar invoice.dueDate

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`
- Modify: `backend/src/main/java/com/fintech/api/service/DashboardService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/DashboardServiceTest.java`

- [ ] **Step 1: Verificar o teste existente de DashboardService**

```bash
cd backend && ./mvnw test -pl . -Dtest=DashboardServiceTest -q 2>&1 | tail -10
```

Anotar se passa antes da mudança.

- [ ] **Step 2: Atualizar as queries de sum e count no TransactionRepository**

Substituir `sumByTenantAndTypeAndPeriod` e `countByTenantAndPeriod` no `TransactionRepository.java`:

```java
    // Para transações de cartão de crédito, usa invoice.dueDate como referência de mês.
    // Para demais contas (sem fatura), continua usando transaction.date.
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.tenant = :tenant
              AND t.type = :type
              AND t.status <> :excluded
              AND (
                (t.invoice IS NOT NULL AND t.invoice.dueDate BETWEEN :start AND :end)
                OR
                (t.invoice IS NULL AND t.date BETWEEN :start AND :end)
              )
            """)
    BigDecimal sumByTenantAndTypeAndPeriod(
            @Param("tenant") Tenant tenant,
            @Param("type") TransactionType type,
            @Param("excluded") TransactionStatus excluded,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end
    );

    @Query("""
            SELECT COUNT(t)
            FROM Transaction t
            WHERE t.tenant = :tenant
              AND t.status <> :excluded
              AND (
                (t.invoice IS NOT NULL AND t.invoice.dueDate BETWEEN :start AND :end)
                OR
                (t.invoice IS NULL AND t.date BETWEEN :start AND :end)
              )
            """)
    long countByTenantAndPeriod(
            @Param("tenant") Tenant tenant,
            @Param("excluded") TransactionStatus excluded,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end
    );
```

Nota: `DashboardService` não precisa de alteração — ele já chama `repository.sumByTenantAndTypeAndPeriod` e `countByTenantAndPeriod` com os mesmos parâmetros.

- [ ] **Step 3: Executar todos os testes do backend**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -20
```

Expected: todos os testes passando. Se `DashboardServiceTest` falhar por mudança de assinatura, ajustar os mocks: o método mantém a mesma assinatura (`Tenant, TransactionType, TransactionStatus, LocalDate, LocalDate`), apenas a JPQL interna mudou — mocks não são afetados.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/repository/TransactionRepository.java
git commit -m "fix(dashboard): queries de soma e contagem usam invoice.dueDate para transações de cartão"
```

---

## Task 8: Atualizar OpenAPI spec + regenerar interfaces Java

**Files:**
- Modify: `backend/src/main/resources/static/openapi.yaml`

- [ ] **Step 1: Adicionar schemas de Invoice no openapi.yaml**

Na seção `components/schemas`, adicionar após o bloco `DashboardSummaryDTO`:

```yaml
    # --- Invoices ---

    InvoiceStatus:
      type: string
      enum: [OPEN, CLOSED, PAID]

    InvoiceResponseDTO:
      type: object
      required: [id, accountId, accountName, referenceMonth, referenceYear, label, closingDate, dueDate, status, totalAmount, transactionCount]
      properties:
        id:
          type: string
          format: uuid
        accountId:
          type: string
          format: uuid
        accountName:
          type: string
        referenceMonth:
          type: integer
        referenceYear:
          type: integer
        label:
          type: string
          example: "Janeiro/2027"
        closingDate:
          type: string
          format: date
        dueDate:
          type: string
          format: date
        status:
          $ref: '#/components/schemas/InvoiceStatus'
        totalAmount:
          type: number
          format: double
        transactionCount:
          type: integer
          format: int64
```

- [ ] **Step 2: Atualizar TransactionResponseDTO no openapi.yaml**

Na seção `TransactionResponseDTO`, adicionar após `totalInstallments`:

```yaml
        invoiceId:
          type: string
          format: uuid
          nullable: true
        invoiceDueDate:
          type: string
          format: date
          nullable: true
        invoiceStatus:
          $ref: '#/components/schemas/InvoiceStatus'
          nullable: true
```

- [ ] **Step 3: Adicionar parâmetro invoiceId em GET /api/transactions e endpoints de Invoice**

Na definição de `GET /api/transactions`, adicionar bloco `parameters` antes de `responses`:

```yaml
      parameters:
        - name: invoiceId
          in: query
          required: false
          schema:
            type: string
            format: uuid
```

Adicionar os paths de Invoice antes dos paths de accounts (ou no final da seção paths):

```yaml
  # --- Invoices ---

  /api/invoices:
    get:
      tags: [invoices]
      operationId: listInvoices
      parameters:
        - name: accountId
          in: query
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Lista de faturas da conta
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/InvoiceResponseDTO'

  /api/invoices/{id}:
    get:
      tags: [invoices]
      operationId: getInvoice
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Detalhe da fatura
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InvoiceResponseDTO'

  /api/invoices/{id}/close:
    post:
      tags: [invoices]
      operationId: closeInvoice
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Fatura fechada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InvoiceResponseDTO'

  /api/invoices/{id}/pay:
    post:
      tags: [invoices]
      operationId: payInvoice
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Fatura paga
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InvoiceResponseDTO'
```

- [ ] **Step 4: Regenerar interfaces Java via Maven**

```bash
cd backend && ./mvnw generate-sources -q
```

Expected: BUILD SUCCESS. As interfaces `InvoicesApi` e `TransactionsApi` (atualizada) estarão em `target/generated-sources/`.

- [ ] **Step 5: Fazer InvoiceController implementar InvoicesApi**

Se a interface `InvoicesApi` foi gerada, atualizar `InvoiceController` para implementá-la:

```java
// Adicionar import no InvoiceController:
import com.fintech.api.openapi.InvoicesApi;

// Atualizar a declaração da classe:
public class InvoiceController implements InvoicesApi {
```

Verificar se os métodos do controller batem com as assinaturas da interface gerada. Se houver divergência de nomes de parâmetros, ajustar para seguir a interface.

- [ ] **Step 6: Rodar todos os testes para garantir nada quebrou**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS, todos os testes passando.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/static/openapi.yaml \
        backend/src/main/java/com/fintech/api/controller/InvoiceController.java
git commit -m "feat(openapi): adiciona Invoice ao spec e atualiza TransactionResponseDTO com campos de fatura"
```

---

## Task 9: Regenerar cliente frontend via Orval

**Files:**
- Modify: `frontend/src/app/core/api/` (gerado automaticamente)

- [ ] **Step 1: Verificar comando Orval disponível**

```bash
cd frontend && cat package.json | grep -A3 '"orval"'
```

- [ ] **Step 2: Executar geração**

```bash
cd frontend && npm run generate-api 2>&1 | tail -20
```

Se o script tiver nome diferente, verificar em `package.json` a key correta (pode ser `orval`, `gen`, `generate`, etc.).

Expected: arquivos em `src/app/core/api/` atualizados — `fintechSaaSAPI.schemas.ts` ganha `InvoiceStatus`, `InvoiceResponseDTO`, campos novos em `TransactionResponseDTO`.

- [ ] **Step 3: Verificar que o projeto compila**

```bash
cd frontend && npm run build -- --no-progress 2>&1 | tail -20
```

Expected: sem erros de TypeScript.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/core/api/
git commit -m "chore(frontend): regenera cliente Orval com tipos de Invoice"
```

---

## Task 10: Frontend — installment-preview com label de fatura para CREDIT_CARD

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-form/installment-preview.ts`
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.spec.ts`

- [ ] **Step 1: Escrever os testes de installment-preview com creditCard**

Adicionar ao arquivo `transaction-form.spec.ts` (após os testes existentes de `buildInstallmentPreview`):

```typescript
import { describe, it, expect } from 'vitest';
import { buildInstallmentPreview } from './installment-preview';

// --- Testes existentes mantidos ---

describe('buildInstallmentPreview — CREDIT_CARD', () => {
  const creditCard = { closingDay: 5, dueDay: 15 };

  it('compra antes do fechamento: parcela 1 vai para o mesmo mês', () => {
    // dia 3 de junho, fechamento dia 5 → fatura junho
    const rows = buildInstallmentPreview(300, 1, new Date('2026-06-03'), 'total', creditCard);
    expect(rows[0].invoiceLabel).toContain('Jun/2026');
  });

  it('compra após fechamento: parcela 1 vai para o mês seguinte', () => {
    // dia 8 de junho, fechamento dia 5 → fatura julho
    const rows = buildInstallmentPreview(300, 1, new Date('2026-06-08'), 'total', creditCard);
    expect(rows[0].invoiceLabel).toContain('Jul/2026');
  });

  it('3 parcelas: cada uma em uma fatura diferente', () => {
    const rows = buildInstallmentPreview(900, 3, new Date('2026-06-03'), 'total', creditCard);
    expect(rows[0].invoiceLabel).toContain('Jun/2026');
    expect(rows[1].invoiceLabel).toContain('Jul/2026');
    expect(rows[2].invoiceLabel).toContain('Ago/2026');
  });

  it('vencimento no mesmo mês quando dueDay >= closingDay', () => {
    // fechamento 5, vencimento 15: vence 15/junho
    const rows = buildInstallmentPreview(100, 1, new Date('2026-06-03'), 'total', creditCard);
    expect(rows[0].invoiceLabel).toContain('vence 15/06');
  });

  it('vencimento no mês seguinte quando dueDay < closingDay', () => {
    // fechamento 25, vencimento 5: fatura dez/2026 fecha 25/dez, vence 05/jan
    const cc = { closingDay: 25, dueDay: 5 };
    const rows = buildInstallmentPreview(100, 1, new Date('2026-12-10'), 'total', cc);
    expect(rows[0].invoiceLabel).toContain('Dez/2026');
    expect(rows[0].invoiceLabel).toContain('vence 05/01');
  });

  it('sem creditCard: invoiceLabel é undefined', () => {
    const rows = buildInstallmentPreview(300, 2, new Date('2026-06-01'), 'total');
    rows.forEach(r => expect(r.invoiceLabel).toBeUndefined());
  });
});
```

- [ ] **Step 2: Executar testes e confirmar falha**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | grep -E "(PASS|FAIL|invoice)" | head -20
```

Expected: falhas nos novos testes de CREDIT_CARD.

- [ ] **Step 3: Implementar a atualização de installment-preview.ts**

Substituir o conteúdo completo de `installment-preview.ts`:

```typescript
export interface InstallmentPreviewRow {
  number: number;
  date: string;
  invoiceLabel?: string;
  amount: number;
}

export interface CreditCardPreviewConfig {
  closingDay: number;
  dueDay: number;
}

const MONTH_ABBR = ['Jan', 'Fev', 'Mar', 'Abr', 'Mai', 'Jun',
                    'Jul', 'Ago', 'Set', 'Out', 'Nov', 'Dez'];

export function buildInstallmentPreview(
  totalAmount: number,
  installments: number,
  startDate: Date,
  valueMode: 'total' | 'per-installment',
  creditCard?: CreditCardPreviewConfig
): InstallmentPreviewRow[] {
  if (installments <= 0 || totalAmount <= 0) return [];

  const installmentAmount = valueMode === 'total'
    ? Math.round((totalAmount / installments) * 100) / 100
    : totalAmount;

  return Array.from({ length: installments }, (_, i) => {
    const d = new Date(startDate);
    d.setMonth(d.getMonth() + i);

    const row: InstallmentPreviewRow = {
      number: i + 1,
      date: d.toLocaleDateString('pt-BR'),
      amount: installmentAmount
    };

    if (creditCard) {
      // Parcela i vai para (invoiceBaseMonth + i)
      const invoiceMonth = resolveInvoiceMonth(startDate, creditCard.closingDay);
      const targetMonth = new Date(invoiceMonth.getFullYear(), invoiceMonth.getMonth() + i, 1);
      const dueDate = calcDueDate(targetMonth, creditCard.closingDay, creditCard.dueDay);
      const mm = String(dueDate.getMonth() + 1).padStart(2, '0');
      const dd = String(dueDate.getDate()).padStart(2, '0');
      row.invoiceLabel =
        `${MONTH_ABBR[targetMonth.getMonth()]}/${targetMonth.getFullYear()} · vence ${dd}/${mm}`;
    }

    return row;
  });
}

function resolveInvoiceMonth(purchaseDate: Date, closingDay: number): Date {
  if (purchaseDate.getDate() <= closingDay) {
    return new Date(purchaseDate.getFullYear(), purchaseDate.getMonth(), 1);
  }
  return new Date(purchaseDate.getFullYear(), purchaseDate.getMonth() + 1, 1);
}

function calcDueDate(invoiceMonth: Date, closingDay: number, dueDay: number): Date {
  if (dueDay >= closingDay) {
    return new Date(invoiceMonth.getFullYear(), invoiceMonth.getMonth(), dueDay);
  }
  return new Date(invoiceMonth.getFullYear(), invoiceMonth.getMonth() + 1, dueDay);
}
```

- [ ] **Step 4: Atualizar transaction-form.ts para passar creditCard ao buildInstallmentPreview**

No arquivo `transaction-form.ts`, localizar o computed `installmentPreview` e atualizá-lo:

```typescript
  // Adicionar signal para conta selecionada (já existe accounts signal)
  private accountIdSignal = toSignal(this.form.controls.accountId.valueChanges, { initialValue: this.form.controls.accountId.value });

  installmentPreview = computed(() => {
    if (!this.isInstallment()) return [];
    const amount = this.amountSignal() ?? 0;
    const installments = this.installmentsSignal() ?? 1;
    const date = this.form.controls.date.value ?? new Date();

    const selectedAccountId = this.accountIdSignal();
    const selectedAccount = this.accounts().find(a => a.id === selectedAccountId);
    const creditCard = selectedAccount?.type === 'CREDIT_CARD' && selectedAccount.creditCardDetails
      ? { closingDay: selectedAccount.creditCardDetails.closingDay, dueDay: selectedAccount.creditCardDetails.dueDay }
      : undefined;

    return buildInstallmentPreview(amount, installments, date, this.valueMode(), creditCard);
  });
```

Também adicionar o import do tipo:
```typescript
import { buildInstallmentPreview, CreditCardPreviewConfig } from './installment-preview';
```

- [ ] **Step 5: Executar testes e confirmar que passam**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | grep -E "(PASS|FAIL)" | head -20
```

Expected: todos os testes passando.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-form/installment-preview.ts \
        frontend/src/app/features/transaction/transaction-form/transaction-form.ts \
        frontend/src/app/features/transaction/transaction-form/transaction-form.spec.ts
git commit -m "feat(frontend): preview de parcelas exibe fatura e vencimento para CREDIT_CARD"
```

---

## Task 11: Frontend — chip informativo de fatura na listagem de transações

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.html`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`

- [ ] **Step 1: Adicionar helper de label de fatura no transaction-list.ts**

No arquivo `transaction-list.ts`, adicionar método após `statusLabel`:

```typescript
  invoiceChipClass(status: string | undefined): string {
    const map: Record<string, string> = {
      OPEN: 'invoice-open',
      CLOSED: 'invoice-closed',
      PAID: 'invoice-paid'
    };
    return 'invoice-chip ' + (map[status ?? ''] ?? '');
  }

  invoiceLabel(t: TransactionResponseDTO | undefined): string | null {
    if (!t?.invoiceId || !t.invoiceDueDate) return null;
    const d = new Date(t.invoiceDueDate + 'T00:00:00');
    const month = d.toLocaleDateString('pt-BR', { month: 'short', year: 'numeric' });
    return `Fatura ${month}`;
  }
```

- [ ] **Step 2: Atualizar o template para mostrar o chip na coluna account**

Na coluna `account` do `transaction-list.html`, substituir:

```html
      <ng-container matColumnDef="account">
        <th mat-header-cell *matHeaderCellDef>Conta</th>
        <td mat-cell *matCellDef="let row">
          <div class="account-cell">
            <span>{{ $any(row).data?.accountName ?? '—' }}</span>
            @if (invoiceLabel($any(row).data); as label) {
              <span [class]="invoiceChipClass($any(row).data?.invoiceStatus)">{{ label }}</span>
            }
          </div>
        </td>
      </ng-container>
```

- [ ] **Step 3: Adicionar estilos ao transaction-list.scss**

No arquivo `frontend/src/app/features/transaction/transaction-list/transaction-list.scss`, adicionar:

```scss
.account-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.invoice-chip {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 10px;
  width: fit-content;
  font-weight: 500;
}

.invoice-open   { background: #e0e0e0; color: #555; }
.invoice-closed { background: #fff3e0; color: #e65100; }
.invoice-paid   { background: #e8f5e9; color: #2e7d32; }
```

- [ ] **Step 4: Verificar que o projeto compila**

```bash
cd frontend && npm run build -- --no-progress 2>&1 | tail -10
```

Expected: BUILD SUCCESS sem erros.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.html \
        frontend/src/app/features/transaction/transaction-list/transaction-list.ts \
        frontend/src/app/features/transaction/transaction-list/transaction-list.scss
git commit -m "feat(frontend): chip informativo de fatura na listagem de transações"
```

---

## Task 12: Verificação final

- [ ] **Step 1: Rodar todos os testes de backend**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` — zero falhas.

- [ ] **Step 2: Rodar todos os testes de frontend**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | tail -20
```

Expected: todos passando.

- [ ] **Step 3: Subir o sistema completo e testar manualmente**

```bash
# Terminal 1:
cd backend && ./mvnw spring-boot:run

# Terminal 2:
cd frontend && npm start
```

Fluxo de teste:
1. Criar uma conta CREDIT_CARD com `closingDay=5` e `dueDay=15`
2. Criar uma transação avulsa nessa conta com data 03/06/2026 → verificar que `invoiceId` aparece na resposta
3. Criar uma transação parcelada em 3x nessa conta com data 03/06/2026 → verificar que cada parcela tem fatura diferente (Jun, Jul, Ago)
4. Criar uma transação com data 08/06/2026 (pós-fechamento) → verificar que vai para fatura de julho
5. Verificar no frontend que o preview de parcelas exibe "Jun/2026 · vence 15/06", etc.
6. Verificar que o chip aparece na listagem de transações
7. Acessar `GET /api/invoices?accountId=...` e verificar as faturas criadas

- [ ] **Step 4: Commit final se houver ajustes pós-smoke-test**

```bash
git add -p  # adicionar apenas arquivos modificados
git commit -m "fix: ajustes pós-smoke-test da feature de Invoice"
```
