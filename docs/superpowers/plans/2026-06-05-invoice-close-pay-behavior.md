# Invoice Close/Pay Behavior — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar o comportamento real de fechar e pagar faturas: fechar é um marcador administrativo sem side effects; pagar marca transações como PAID e cria uma EXPENSE na conta de origem.

**Architecture:** Backend-first em ordem de dependências (repositório → serviço → controller → OpenAPI); depois frontend (codegen → dialog → lista). O service orquestra tudo em `@Transactional` única. O controller permanece fino — apenas extrai dados do request e chama o service.

**Tech Stack:** Java 21, Spring Boot, JPA/Hibernate, JUnit 5 + Mockito (backend); Angular 21 Zoneless com Signals, Angular Material, Orval (frontend); OpenAPI Generator (codegen).

---

## File Map

| Arquivo | Ação |
|---------|------|
| `backend/.../repository/TransactionRepository.java` | Modify — adicionar batch update query |
| `backend/.../service/InvoiceService.java` | Modify — atualizar `pay()` |
| `backend/.../service/InvoiceServiceTest.java` | Modify — atualizar + adicionar testes |
| `backend/.../dto/invoice/InvoicePayDTO.java` | Create — novo record |
| `api-spec/openapi.yaml` | Modify — schema + request body |
| `backend/.../resources/static/openapi.yaml` | Modify — sync do api-spec |
| `backend/.../controller/InvoiceController.java` | Modify — aceitar request body |
| `frontend/.../core/api/invoices/invoices.service.ts` | Auto-gerado pelo Orval — não editar |
| `frontend/.../core/api/fintechSaaSAPI.schemas.ts` | Auto-gerado pelo Orval — não editar |
| `frontend/.../invoice/invoice-pay-dialog/invoice-pay-dialog.ts` | Create |
| `frontend/.../invoice/invoice-pay-dialog/invoice-pay-dialog.html` | Create |
| `frontend/.../invoice/invoice-list/invoice-list.ts` | Modify — onClose/onPay |
| `frontend/.../invoice/invoice-list/invoice-list.html` | Modify — botões fechar/pagar |

---

## Task 1: TransactionRepository — batch update query

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`

- [ ] **Step 1: Adicionar o método ao repositório**

Abrir `TransactionRepository.java` e adicionar após o método `countByInvoice`:

```java
// Atualiza em batch todas as transações de uma fatura com status específico.
// @Modifying exige @Transactional no caller — evita loop N+1 de saves.
@Modifying
@Query("""
    UPDATE Transaction t
       SET t.status = :newStatus
     WHERE t.invoice = :invoice
       AND t.status = :currentStatus
    """)
int updateStatusByInvoiceAndStatus(
    @Param("invoice") Invoice invoice,
    @Param("currentStatus") TransactionStatus currentStatus,
    @Param("newStatus") TransactionStatus newStatus
);
```

- [ ] **Step 2: Verificar compilação**

```bash
cd backend && ./mvnw compile -q
```

Esperado: BUILD SUCCESS sem erros.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/fintech/api/repository/TransactionRepository.java
git commit -m "feat(invoice): adiciona query batch para atualizar status de transações por fatura"
```

---

## Task 2: InvoiceService — TDD do pay() atualizado

**Files:**
- Modify: `backend/src/test/java/com/fintech/api/service/InvoiceServiceTest.java`
- Modify: `backend/src/main/java/com/fintech/api/service/InvoiceService.java`

### Step 2a: Escrever testes com stub (falham na compilação)

- [ ] **Step 1: Atualizar InvoiceServiceTest — adicionar mocks e helpers**

Substituir o cabeçalho da classe (imports + mocks) por:

```java
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
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock InvoiceRepository repository;
    @Mock CreditCardDetailsRepository creditCardDetailsRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock AccountRepository accountRepository;
    @InjectMocks InvoiceService service;
```

- [ ] **Step 2: Atualizar testes existentes de pay() para a nova assinatura**

Substituir os dois testes de pay existentes:

```java
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
        Account source = buildNonCreditCardAccount(invoice.getTenant());
        User user = buildUser();

        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdAndTenant(source.getId(), invoice.getTenant()))
                .thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.pay(invoice.getId(), invoice.getTenant(), user, source.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }
```

- [ ] **Step 3: Adicionar novos testes**

Adicionar após os testes de pay:

```java
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
    @DisplayName("pay: lança IllegalArgumentException se conta de origem for CREDIT_CARD")
    void payRejectsCreditCardSourceAccount() {
        Invoice invoice = buildInvoice(InvoiceStatus.CLOSED);
        Account creditCard = buildAccount();
        creditCard.setType(AccountType.CREDIT_CARD);
        User user = buildUser();

        when(repository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(accountRepository.findByIdAndTenant(creditCard.getId(), invoice.getTenant()))
                .thenReturn(Optional.of(creditCard));

        assertThatThrownBy(() -> service.pay(invoice.getId(), invoice.getTenant(), user, creditCard.getId()))
                .isInstanceOf(IllegalArgumentException.class)
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
```

- [ ] **Step 4: Adicionar helpers ao final da classe**

```java
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
```

- [ ] **Step 5: Tentar compilar — deve falhar**

```bash
cd backend && ./mvnw test-compile -pl . 2>&1 | grep "ERROR\|error:"
```

Esperado: erros de compilação indicando que `service.pay(id, tenant, user, sourceId)` não existe e que `AccountRepository` não é campo de `InvoiceService`.

### Step 2b: Implementar o método pay() atualizado

- [ ] **Step 6: Atualizar InvoiceService — adicionar AccountRepository e novo pay()**

Substituir o bloco completo da classe `InvoiceService`:

```java
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
        return repository.findById(id)
                .filter(inv -> inv.getAccount().getTenant().getId().equals(tenant.getId()))
                .orElseThrow(() -> new EntityNotFoundException("Fatura não encontrada."));
    }

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
```

- [ ] **Step 7: Rodar testes — devem passar**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvoiceServiceTest -q
```

Esperado: `Tests run: 13, Failures: 0, Errors: 0` (8 testes originais → 2 atualizados + 5 novos = 13 total).

- [ ] **Step 8: Rodar suite completa para verificar regressões**

```bash
cd backend && ./mvnw test -q
```

Esperado: BUILD SUCCESS, todos os testes passando.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/InvoiceService.java \
        backend/src/test/java/com/fintech/api/service/InvoiceServiceTest.java
git commit -m "feat(invoice): implementa pay() com marcação de transações e criação de débito"
```

---

## Task 3: InvoicePayDTO — novo record

**Files:**
- Create: `backend/src/main/java/com/fintech/api/dto/invoice/InvoicePayDTO.java`

- [ ] **Step 1: Criar o record**

```java
package com.fintech.api.dto.invoice;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InvoicePayDTO(
        @NotNull(message = "A conta de origem é obrigatória") UUID sourceAccountId
) {}
```

- [ ] **Step 2: Verificar compilação**

```bash
cd backend && ./mvnw compile -q
```

Esperado: BUILD SUCCESS.

---

## Task 4: OpenAPI spec — InvoicePayDTO schema + request body

**Files:**
- Modify: `api-spec/openapi.yaml`

- [ ] **Step 1: Adicionar schema InvoicePayDTO**

No arquivo `api-spec/openapi.yaml`, localizar a seção `components: schemas:` e adicionar após `InvoiceResponseDTO`:

```yaml
    InvoicePayDTO:
      type: object
      required:
        - sourceAccountId
      properties:
        sourceAccountId:
          type: string
          format: uuid
```

- [ ] **Step 2: Atualizar endpoint POST /api/invoices/{id}/pay**

Localizar o bloco do endpoint `POST /api/invoices/{id}/pay` (em torno da linha 1175) e substituir por:

```yaml
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
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/InvoicePayDTO'
      responses:
        '200':
          description: Fatura paga
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InvoiceResponseDTO'
```

---

## Task 5: Backend codegen + InvoiceController

**Files:**
- Run: `cd backend && ./mvnw generate-sources`
- Modify: `backend/src/main/java/com/fintech/api/controller/InvoiceController.java`
- Modify: `backend/src/main/resources/static/openapi.yaml`

- [ ] **Step 1: Executar codegen**

```bash
cd backend && ./mvnw generate-sources -q
```

Esperado: BUILD SUCCESS. A interface `InvoicesApi` em `target/generated-sources/` terá o método `payInvoice(UUID id, InvoicePayDTO invoicePayDTO)`.

- [ ] **Step 2: Verificar interface gerada**

```bash
grep -A3 "payInvoice" backend/target/generated-sources/openapi/src/main/java/com/fintech/api/openapi/InvoicesApi.java
```

Esperado: `ResponseEntity<InvoiceResponseDTO> payInvoice(UUID id, @Valid @RequestBody InvoicePayDTO invoicePayDTO)` (o nome exato do parâmetro pode variar).

- [ ] **Step 3: Atualizar InvoiceController**

Substituir o método `payInvoice` em `InvoiceController.java`:

```java
@PostMapping("/{id}/pay")
public ResponseEntity<InvoiceResponseDTO> payInvoice(
        @PathVariable UUID id,
        @Valid @RequestBody InvoicePayDTO dto) {
    User user = getAuthenticatedUser();
    return ResponseEntity.ok(
        invoiceService.pay(id, user.getTenant(), user, dto.sourceAccountId())
    );
}
```

Adicionar o import se necessário:
```java
import com.fintech.api.dto.invoice.InvoicePayDTO;
```

- [ ] **Step 4: Compilar e rodar testes**

```bash
cd backend && ./mvnw test -q
```

Esperado: BUILD SUCCESS.

- [ ] **Step 5: Sincronizar openapi.yaml estático**

```bash
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
```

- [ ] **Step 6: Commit**

```bash
git add api-spec/openapi.yaml \
        backend/src/main/resources/static/openapi.yaml \
        backend/src/main/java/com/fintech/api/dto/invoice/InvoicePayDTO.java \
        backend/src/main/java/com/fintech/api/controller/InvoiceController.java
git commit -m "feat(invoice): atualiza endpoint pay para aceitar conta de origem no body"
```

---

## Task 6: Frontend codegen

**Files:**
- Run: `cd frontend && npm run api:generate`

- [ ] **Step 1: Executar Orval**

```bash
cd frontend && npm run api:generate
```

Esperado: sem erros. Os arquivos gerados são atualizados:
- `src/app/core/api/fintechSaaSAPI.schemas.ts` — novo tipo `InvoicePayDTO: { sourceAccountId: string }`
- `src/app/core/api/invoices/invoices.service.ts` — `payInvoice(id, invoicePayDTO, options?)` com body

- [ ] **Step 2: Verificar geração**

```bash
grep -A4 "InvoicePayDTO" frontend/src/app/core/api/fintechSaaSAPI.schemas.ts
grep "payInvoice" frontend/src/app/core/api/invoices/invoices.service.ts | head -3
```

Esperado:
```
export interface InvoicePayDTO {
  sourceAccountId: string;
}
```
E a assinatura de `payInvoice` incluindo o parâmetro `invoicePayDTO`.

- [ ] **Step 3: Verificar que o TypeScript compila**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Esperado: sem erros de compilação.

---

## Task 7: InvoicePayDialog — novo componente

**Files:**
- Create: `frontend/src/app/features/invoice/invoice-pay-dialog/invoice-pay-dialog.ts`
- Create: `frontend/src/app/features/invoice/invoice-pay-dialog/invoice-pay-dialog.html`

- [ ] **Step 1: Criar o componente TypeScript**

```typescript
import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { AccountResponse, AccountType, InvoiceResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export interface InvoicePayDialogResult {
  sourceAccountId: string;
}

interface DialogData {
  invoice: InvoiceResponseDTO;
}

@Component({
  selector: 'app-invoice-pay-dialog',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, FormsModule,
    MatDialogModule, MatSelectModule, MatFormFieldModule, MatButtonModule
  ],
  templateUrl: './invoice-pay-dialog.html'
})
export class InvoicePayDialogComponent implements OnInit {
  private dialogRef = inject(MatDialogRef<InvoicePayDialogComponent>);
  data = inject<DialogData>(MAT_DIALOG_DATA);
  private accountsService = inject(AccountsService);

  accounts = signal<AccountResponse[]>([]);
  selectedAccountId = signal<string | null>(null);

  eligibleAccounts = computed(() =>
    this.accounts().filter(a => a.type !== AccountType.CREDIT_CARD && a.active)
  );

  hasNoEligibleAccounts = computed(() => this.eligibleAccounts().length === 0);

  get invoice(): InvoiceResponseDTO { return this.data.invoice; }

  ngOnInit(): void {
    this.accountsService.listAccounts().subscribe({
      next: (data) => this.accounts.set(data)
    });
  }

  confirm(): void {
    const id = this.selectedAccountId();
    if (!id) return;
    this.dialogRef.close({ sourceAccountId: id } as InvoicePayDialogResult);
  }

  cancel(): void {
    this.dialogRef.close(undefined);
  }
}
```

- [ ] **Step 2: Criar o template HTML**

```html
<h2 mat-dialog-title>Pagar fatura</h2>

<mat-dialog-content>
  <p>
    <strong>{{ invoice.accountName }}</strong> — {{ invoice.label }}<br>
    Total: <strong>{{ invoice.totalAmount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</strong>
  </p>

  @if (hasNoEligibleAccounts()) {
    <p style="color: #e65100; margin-top: 12px;">
      Nenhuma conta disponível para pagamento. Cadastre uma conta corrente ou carteira.
    </p>
  } @else {
    <mat-form-field appearance="outline" style="width: 100%; margin-top: 8px;">
      <mat-label>Conta de origem</mat-label>
      <mat-select
        [ngModel]="selectedAccountId()"
        (ngModelChange)="selectedAccountId.set($event)">
        @for (acc of eligibleAccounts(); track acc.id) {
          <mat-option [value]="acc.id">{{ acc.name }}</mat-option>
        }
      </mat-select>
    </mat-form-field>
  }
</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button (click)="cancel()">Cancelar</button>
  <button
    mat-flat-button
    color="primary"
    [disabled]="!selectedAccountId() || hasNoEligibleAccounts()"
    (click)="confirm()">
    Confirmar pagamento
  </button>
</mat-dialog-actions>
```

- [ ] **Step 3: Verificar TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Esperado: sem erros.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/features/invoice/invoice-pay-dialog/
git commit -m "feat(invoice): adiciona InvoicePayDialog para seleção de conta de origem"
```

---

## Task 8: InvoiceList — botões fechar/pagar

**Files:**
- Modify: `frontend/src/app/features/invoice/invoice-list/invoice-list.ts`
- Modify: `frontend/src/app/features/invoice/invoice-list/invoice-list.html`

- [ ] **Step 1: Atualizar invoice-list.ts**

Substituir o conteúdo completo do arquivo:

```typescript
import { Component, inject, OnInit, signal, effect } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

import { finalize } from 'rxjs/operators';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { AccountResponse, InvoiceResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { InvoicePayDialogComponent, InvoicePayDialogResult } from '../invoice-pay-dialog/invoice-pay-dialog';

@Component({
  selector: 'app-invoice-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
    MatTableModule, MatButtonModule, MatIconModule,
    MatSelectModule, MatFormFieldModule, MatSnackBarModule, MatDialogModule
  ],
  templateUrl: './invoice-list.html',
  styleUrl: './invoice-list.scss'
})
export class InvoiceList implements OnInit {
  private accountsService = inject(AccountsService);
  private invoicesService = inject(InvoicesService);
  private route = inject(ActivatedRoute);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);

  creditCardAccounts = signal<AccountResponse[]>([]);
  selectedId = signal<string | null>(null);
  invoices = signal<InvoiceResponseDTO[]>([]);
  loading = signal(false);

  displayedColumns = ['label', 'closingDate', 'dueDate', 'transactionCount', 'totalAmount', 'status', 'actions'];

  constructor() {
    effect((onCleanup) => {
      const id = this.selectedId();
      if (!id) {
        this.invoices.set([]);
        return;
      }
      this.loading.set(true);
      const sub = this.invoicesService.listInvoices({ accountId: id })
        .pipe(finalize(() => this.loading.set(false)))
        .subscribe({
          next: (data) => this.invoices.set(data),
          error: () => this.snackBar.open('Erro ao carregar faturas.', 'Fechar', { duration: 5000 })
        });
      onCleanup(() => sub.unsubscribe());
    });
  }

  ngOnInit(): void {
    this.accountsService.listAccounts().subscribe({
      next: (data) => {
        const cc = data.filter(a => a.type === 'CREDIT_CARD');
        this.creditCardAccounts.set(cc);
        const preselect = this.route.snapshot.queryParamMap.get('accountId');
        if (preselect && cc.some(a => a.id === preselect)) {
          this.selectedId.set(preselect);
        }
      },
      error: () => this.snackBar.open('Erro ao carregar contas.', 'Fechar', { duration: 5000 })
    });
  }

  onClose(invoice: InvoiceResponseDTO): void {
    this.loading.set(true);
    this.invoicesService.closeInvoice(invoice.id)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (updated) => {
          this.invoices.update(list => list.map(inv => inv.id === updated.id ? updated : inv));
          this.snackBar.open('Fatura fechada com sucesso.', 'Fechar', { duration: 3000 });
        },
        error: () => this.snackBar.open('Erro ao fechar fatura.', 'Fechar', { duration: 5000 })
      });
  }

  onPay(invoice: InvoiceResponseDTO): void {
    const dialogRef = this.dialog.open(InvoicePayDialogComponent, {
      data: { invoice },
      width: '480px'
    });
    dialogRef.afterClosed().subscribe((result: InvoicePayDialogResult | undefined) => {
      if (!result) return;
      this.loading.set(true);
      this.invoicesService.payInvoice(invoice.id, { sourceAccountId: result.sourceAccountId })
        .pipe(finalize(() => this.loading.set(false)))
        .subscribe({
          next: (updated) => {
            this.invoices.update(list => list.map(inv => inv.id === updated.id ? updated : inv));
            this.snackBar.open('Fatura paga com sucesso.', 'Fechar', { duration: 3000 });
          },
          error: () => this.snackBar.open('Erro ao pagar fatura.', 'Fechar', { duration: 5000 })
        });
    });
  }

  statusChipClass(status: string): string {
    const map: Record<string, string> = {
      OPEN:   'status-chip status-open',
      CLOSED: 'status-chip status-closed',
      PAID:   'status-chip status-paid'
    };
    return map[status] ?? 'status-chip';
  }

  statusLabel(status: string): string {
    const map: Record<string, string> = { OPEN: 'Aberta', CLOSED: 'Fechada', PAID: 'Paga' };
    return map[status] ?? status;
  }
}
```

- [ ] **Step 2: Atualizar invoice-list.html — coluna actions com botões**

Substituir apenas o bloco `<ng-container matColumnDef="actions">`:

```html
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let row">
            @if (row.status === 'OPEN') {
              <button mat-button color="accent" (click)="onClose(row)">Fechar</button>
            }
            @if (row.status === 'CLOSED') {
              <button mat-flat-button color="primary" (click)="onPay(row)">Pagar</button>
            }
            <a mat-button [routerLink]="['/invoices', row.id]">Ver detalhes</a>
          </td>
        </ng-container>
```

- [ ] **Step 3: Verificar TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Esperado: sem erros.

- [ ] **Step 4: Rodar testes do frontend**

```bash
cd frontend && npm test
```

Esperado: testes existentes passando sem regressões.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/invoice/invoice-list/
git commit -m "feat(invoice): adiciona botões fechar/pagar na listagem de faturas"
```

---

## Verificação final

- [ ] **Subir backend e testar smoke**

```bash
# Em terminal separado: cd backend && ./mvnw spring-boot:run
# Aguardar "Started Application"
curl -s http://localhost:8080/actuator/health | grep UP
```

- [ ] **Subir frontend e testar fluxo**

```bash
# Em terminal separado: cd frontend && npm start
# Navegar para http://localhost:4200/invoices
# 1. Selecionar um cartão de crédito com fatura OPEN
# 2. Clicar "Fechar" → fatura deve virar CLOSED, botão "Fechar" some, botão "Pagar" aparece
# 3. Clicar "Pagar" → dialog abre com select de contas (sem CREDIT_CARD)
# 4. Selecionar conta corrente, confirmar → fatura vira PAID, botão "Pagar" some
# 5. Verificar listagem de transações: transações da fatura aparecem com status PAID
# 6. Verificar que uma transação de "Pagamento fatura" aparece na conta corrente selecionada
```

- [ ] **Commit final de documentação se necessário**

Verificar se há arquivos gerados não versionados que devem ser incluídos:

```bash
git status
```
