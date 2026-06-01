# Installment Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar gerenciamento completo de transações parceladas com agrupamento via `InstallmentGroup`, exclusão/edição em massa com proteção de parcelas pagas, e melhorias de UX na listagem e formulário.

**Architecture:** Nova entidade `InstallmentGroup` em tabela separada armazena metadados do grupo. Cada `Transaction` parcelada recebe FK nullable `installment_group_id`. A listagem do frontend agrupa transações pelo `installmentGroupId` via `computed()`, exibindo linhas colapsáveis. Operações em massa (delete com scope, update com propagate) aplicam-se apenas a parcelas com `status != PAID`.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, Flyway, Angular 21 Zoneless, Angular Material 3, Vitest, Orval (geração de cliente HTTP)

---

## Mapa de Arquivos

**Criar:**
- `api-spec/openapi.yaml` — atualizar (schemas + endpoints novos)
- `backend/src/main/resources/db/migration/V8__installment_groups.sql`
- `backend/src/main/java/com/fintech/api/domain/installment/InstallmentGroup.java`
- `backend/src/main/java/com/fintech/api/domain/enums/DeleteInstallmentScope.java`
- `backend/src/main/java/com/fintech/api/repository/InstallmentGroupRepository.java`
- `backend/src/main/java/com/fintech/api/dto/installment/InstallmentGroupResponseDTO.java`
- `backend/src/main/java/com/fintech/api/dto/installment/InstallmentGroupPatchDTO.java`
- `backend/src/main/java/com/fintech/api/dto/installment/DeleteInstallmentResultDTO.java`
- `backend/src/main/java/com/fintech/api/service/InstallmentGroupService.java`
- `backend/src/main/java/com/fintech/api/controller/InstallmentGroupController.java`
- `backend/src/test/java/com/fintech/api/service/InstallmentGroupServiceTest.java`
- `frontend/src/app/features/transaction/transaction-list/delete-installment-dialog/delete-installment-dialog.ts`
- `frontend/src/app/features/transaction/transaction-list/delete-installment-dialog/delete-installment-dialog.html`

**Modificar:**
- `backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java` — adicionar campo `installmentGroup`
- `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java` — novas queries
- `backend/src/main/java/com/fintech/api/dto/transaction/TransactionResponseDTO.java` — campos de grupo
- `backend/src/main/java/com/fintech/api/dto/transaction/TransactionUpdateDTO.java` — campo `propagate`
- `backend/src/main/java/com/fintech/api/service/TransactionService.java` — create/delete/update
- `backend/src/main/java/com/fintech/api/controller/TransactionController.java` — scope no delete
- `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java` — novos testes
- `backend/pom.xml` — importMappings para novos DTOs
- `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`
- `frontend/src/app/features/transaction/transaction-list/transaction-list.html`
- `frontend/src/app/features/transaction/transaction-list/transaction-list.scss`
- `frontend/src/app/features/transaction/transaction-form/transaction-form.ts`
- `frontend/src/app/features/transaction/transaction-form/transaction-form.html`
- `frontend/src/app/features/transaction/transaction-form/transaction-form.scss`

---

## Task 1: Migration V8 — Tabela installment_groups

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__installment_groups.sql`

- [ ] **Step 1: Criar migration**

```sql
CREATE TABLE installment_groups (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    description        VARCHAR(255) NOT NULL,
    total_amount       DECIMAL(15,2) NOT NULL,
    total_installments INTEGER      NOT NULL,
    account_id         UUID         NOT NULL REFERENCES accounts(id),
    category_id        UUID         REFERENCES categories(id),
    tenant_id          UUID         NOT NULL REFERENCES tenants(id),
    created_at         TIMESTAMP    NOT NULL DEFAULT now()
);

ALTER TABLE transactions
    ADD COLUMN installment_group_id UUID REFERENCES installment_groups(id);

CREATE INDEX idx_transactions_group     ON transactions(tenant_id, installment_group_id);
CREATE INDEX idx_installment_groups_tenant ON installment_groups(tenant_id);
```

- [ ] **Step 2: Aplicar migration via build**

```bash
cd backend && ./mvnw flyway:migrate -q
```

Saída esperada: `Successfully applied 1 migration to schema "public"` (ou sem output se usando `spring.flyway.enabled=true` — basta subir o backend).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V8__installment_groups.sql
git commit -m "feat(db): cria tabela installment_groups e FK em transactions (V8)"
```

---

## Task 2: Entidade InstallmentGroup + Repository

**Files:**
- Create: `backend/src/main/java/com/fintech/api/domain/installment/InstallmentGroup.java`
- Create: `backend/src/main/java/com/fintech/api/repository/InstallmentGroupRepository.java`

- [ ] **Step 1: Criar entidade**

```java
package com.fintech.api.domain.installment;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.tenant.Tenant;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "installment_groups")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class InstallmentGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @NotBlank
    @Column(nullable = false)
    private String description;

    @NotNull
    @Column(nullable = false)
    private BigDecimal totalAmount;

    @NotNull
    @Column(nullable = false)
    private Integer totalInstallments;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @ToString.Exclude
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @ToString.Exclude
    private Category category;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude
    private Tenant tenant;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: Criar repository**

```java
package com.fintech.api.repository;

import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstallmentGroupRepository extends JpaRepository<InstallmentGroup, UUID> {
    Optional<InstallmentGroup> findByIdAndTenant(UUID id, Tenant tenant);
    List<InstallmentGroup> findByTenantOrderByCreatedAtDesc(Tenant tenant);
}
```

- [ ] **Step 3: Compilar para verificar erros**

```bash
cd backend && ./mvnw compile -q
```

Saída esperada: BUILD SUCCESS sem erros.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/domain/installment/ \
        backend/src/main/java/com/fintech/api/repository/InstallmentGroupRepository.java
git commit -m "feat(domain): adiciona entidade InstallmentGroup e seu repository"
```

---

## Task 3: Transaction — adicionar FK + novas queries no repository

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java`
- Modify: `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`

- [ ] **Step 1: Adicionar campo `installmentGroup` em Transaction**

Adicionar após o campo `transferId` (linha ~81):

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "installment_group_id")
@ToString.Exclude
private InstallmentGroup installmentGroup;
```

Adicionar import no topo:
```java
import com.fintech.api.domain.installment.InstallmentGroup;
```

- [ ] **Step 2: Adicionar novas queries em TransactionRepository**

Adicionar após o método `findByTransferIdAndTenant`:

```java
// Usado na listagem — evita N+1 para category, account e installmentGroup
@Query("""
        SELECT t FROM Transaction t
        LEFT JOIN FETCH t.installmentGroup
        LEFT JOIN FETCH t.category
        LEFT JOIN FETCH t.account
        WHERE t.tenant = :tenant
        ORDER BY t.date DESC
        """)
List<Transaction> findAllByTenantWithDetails(@Param("tenant") Tenant tenant);

// Todas as parcelas do grupo, ordenadas
List<Transaction> findByInstallmentGroupOrderByInstallmentNumberAsc(InstallmentGroup group);

// Parcelas a partir de um número (inclusive), para escopo THIS_AND_NEXT
List<Transaction> findByInstallmentGroupAndInstallmentNumberGreaterThanEqualOrderByInstallmentNumberAsc(
        InstallmentGroup group, int installmentNumber);

// Parcelas futuras com status PENDING — usadas na propagação do update
@Query("""
        SELECT t FROM Transaction t
        WHERE t.installmentGroup = :group
          AND t.installmentNumber > :number
          AND t.status = :status
        ORDER BY t.installmentNumber ASC
        """)
List<Transaction> findFuturePendingInGroup(
        @Param("group") InstallmentGroup group,
        @Param("number") int installmentNumber,
        @Param("status") TransactionStatus status);
```

Adicionar import:
```java
import com.fintech.api.domain.installment.InstallmentGroup;
```

- [ ] **Step 3: Compilar**

```bash
cd backend && ./mvnw compile -q
```

Saída esperada: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java \
        backend/src/main/java/com/fintech/api/repository/TransactionRepository.java
git commit -m "feat(domain): adiciona installmentGroup em Transaction e novas queries ao repository"
```

---

## Task 4: DTOs — TransactionResponseDTO, TransactionUpdateDTO e novos DTOs de grupo

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/dto/transaction/TransactionResponseDTO.java`
- Modify: `backend/src/main/java/com/fintech/api/dto/transaction/TransactionUpdateDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/installment/InstallmentGroupResponseDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/installment/InstallmentGroupPatchDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/installment/DeleteInstallmentResultDTO.java`
- Create: `backend/src/main/java/com/fintech/api/domain/enums/DeleteInstallmentScope.java`

- [ ] **Step 1: Atualizar TransactionResponseDTO**

Substituir o record completo:

```java
package com.fintech.api.dto.transaction;

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
        String installmentGroupDescription
) {
    public static TransactionResponseDTO fromEntity(Transaction t) {
        String installLabel = null;
        if (t.getTotalInstallments() != null && t.getTotalInstallments() > 1) {
            installLabel = t.getInstallmentNumber() + "/" + t.getTotalInstallments();
        }
        var group = t.getInstallmentGroup();
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
                group != null ? group.getDescription() : null
        );
    }
}
```

- [ ] **Step 2: Atualizar TransactionUpdateDTO**

Substituir o record completo:

```java
package com.fintech.api.dto.transaction;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TransactionUpdateDTO(
        @Size(min = 1, message = "A descrição não pode ser vazia")
        String description,

        @DecimalMin(value = "0.01", message = "O valor deve ser positivo")
        BigDecimal amount,

        LocalDate date,
        TransactionType type,
        TransactionStatus status,
        UUID categoryId,
        UUID accountId,
        List<String> propagate
) {}
```

- [ ] **Step 3: Criar DeleteInstallmentScope**

```java
package com.fintech.api.domain.enums;

public enum DeleteInstallmentScope {
    SINGLE, THIS_AND_NEXT, ALL
}
```

- [ ] **Step 4: Criar DeleteInstallmentResultDTO**

```java
package com.fintech.api.dto.installment;

public record DeleteInstallmentResultDTO(int deleted, int skippedPaid) {}
```

- [ ] **Step 5: Criar InstallmentGroupResponseDTO**

```java
package com.fintech.api.dto.installment;

import com.fintech.api.dto.transaction.TransactionResponseDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InstallmentGroupResponseDTO(
        UUID id,
        String description,
        BigDecimal totalAmount,
        BigDecimal installmentAmount,
        int totalInstallments,
        long paidInstallments,
        long pendingInstallments,
        LocalDate nextDueDate,
        String categoryName,
        UUID categoryId,
        String accountName,
        UUID accountId,
        List<TransactionResponseDTO> transactions
) {}
```

- [ ] **Step 6: Criar InstallmentGroupPatchDTO**

```java
package com.fintech.api.dto.installment;

import com.fintech.api.domain.enums.TransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InstallmentGroupPatchDTO(
        String description,
        UUID categoryId,
        UUID accountId,
        BigDecimal installmentAmount,
        TransactionStatus status,
        List<String> fields
) {}
```

- [ ] **Step 7: Compilar**

```bash
cd backend && ./mvnw compile -q
```

Saída esperada: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/fintech/api/dto/ \
        backend/src/main/java/com/fintech/api/domain/enums/DeleteInstallmentScope.java
git commit -m "feat(dto): adiciona DTOs de InstallmentGroup e atualiza TransactionResponseDTO/UpdateDTO"
```

---

## Task 5: TransactionService — create com InstallmentGroup

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java`

- [ ] **Step 1: Escrever testes que falham**

Adicionar ao final de `TransactionServiceTest`, antes dos métodos `buildUser()` e `buildAccount()`:

```java
@Mock InstallmentGroupRepository installmentGroupRepository;

@Test
@DisplayName("create cria InstallmentGroup quando totalInstallments > 1")
void createBuildsInstallmentGroup() {
    User user = buildUser();
    Account account = buildAccount(user);
    TransactionRequestDTO dto = new TransactionRequestDTO(
            "Notebook", new BigDecimal("3000.00"), LocalDate.now(),
            TransactionType.EXPENSE, null, 3, null, account.getId());

    when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
            .thenReturn(Optional.of(account));
    when(installmentGroupRepository.save(any(InstallmentGroup.class)))
            .thenAnswer(i -> { var g = (InstallmentGroup) i.getArgument(0); return g; });
    when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

    service.create(dto, user);

    ArgumentCaptor<InstallmentGroup> captor = ArgumentCaptor.forClass(InstallmentGroup.class);
    verify(installmentGroupRepository, times(1)).save(captor.capture());
    InstallmentGroup group = captor.getValue();
    assertThat(group.getDescription()).isEqualTo("Notebook");
    assertThat(group.getTotalAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
    assertThat(group.getTotalInstallments()).isEqualTo(3);
}

@Test
@DisplayName("create não cria InstallmentGroup para transação única")
void createDoesNotBuildGroupForSingleTransaction() {
    User user = buildUser();
    Account account = buildAccount(user);
    TransactionRequestDTO dto = new TransactionRequestDTO(
            "Salário", new BigDecimal("5000.00"), LocalDate.now(),
            TransactionType.INCOME, null, 1, null, account.getId());

    when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
            .thenReturn(Optional.of(account));
    when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

    service.create(dto, user);

    verify(installmentGroupRepository, never()).save(any());
}

@Test
@DisplayName("create associa installmentGroup a cada parcela criada")
void createAssociatesGroupToEachInstallment() {
    User user = buildUser();
    Account account = buildAccount(user);
    InstallmentGroup savedGroup = InstallmentGroup.builder()
            .id(UUID.randomUUID()).description("Notebook")
            .totalAmount(new BigDecimal("3000.00")).totalInstallments(3)
            .account(account).tenant(user.getTenant()).build();
    TransactionRequestDTO dto = new TransactionRequestDTO(
            "Notebook", new BigDecimal("3000.00"), LocalDate.now(),
            TransactionType.EXPENSE, null, 3, null, account.getId());

    when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
            .thenReturn(Optional.of(account));
    when(installmentGroupRepository.save(any())).thenReturn(savedGroup);
    when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

    service.create(dto, user);

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(repository, times(3)).save(captor.capture());
    captor.getAllValues().forEach(t ->
            assertThat(t.getInstallmentGroup()).isEqualTo(savedGroup));
}
```

Adicionar imports no topo do test:
```java
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.repository.InstallmentGroupRepository;
```

- [ ] **Step 2: Executar testes para confirmar falha**

```bash
cd backend && ./mvnw test -Dtest=TransactionServiceTest#createBuildsInstallmentGroup,TransactionServiceTest#createDoesNotBuildGroupForSingleTransaction,TransactionServiceTest#createAssociatesGroupToEachInstallment -q 2>&1 | tail -20
```

Saída esperada: `FAILED` — `installmentGroupRepository` não está sendo injetado no service ainda.

- [ ] **Step 3: Atualizar TransactionService.create()**

Adicionar campo no service:
```java
private final InstallmentGroupRepository installmentGroupRepository;
```

Substituir o método `create()` completo:
```java
@Transactional
public List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user) {
    Category category = resolveCategory(dto.categoryId(), user);
    Account account = resolveAccount(dto.accountId(), user);

    int installments = (dto.totalInstallments() != null && dto.totalInstallments() > 1)
            ? dto.totalInstallments() : 1;
    BigDecimal installmentAmount = dto.amount()
            .divide(BigDecimal.valueOf(installments), 2, RoundingMode.HALF_EVEN);

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
    List<Transaction> created = new ArrayList<>();
    for (int i = 0; i < installments; i++) {
        created.add(repository.save(Transaction.builder()
                .description(dto.description())
                .amount(installmentAmount)
                .date(dto.date().plusMonths(i))
                .type(dto.type())
                .status(dto.status() != null ? dto.status() : TransactionStatus.PENDING)
                .installmentNumber(i + 1)
                .totalInstallments(installments)
                .installmentGroup(finalGroup)
                .tenant(user.getTenant())
                .user(user)
                .category(category)
                .account(account)
                .build()));
    }
    return created.stream().map(TransactionResponseDTO::fromEntity).toList();
}
```

Adicionar import:
```java
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.repository.InstallmentGroupRepository;
```

- [ ] **Step 4: Executar todos os testes do service**

```bash
cd backend && ./mvnw test -Dtest=TransactionServiceTest -q 2>&1 | tail -10
```

Saída esperada: `BUILD SUCCESS`, todos os testes passando.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/TransactionService.java \
        backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java
git commit -m "feat(service): TransactionService.create() cria InstallmentGroup para parcelamentos"
```

---

## Task 6: TransactionService — delete com scope + update com propagate

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Modify: `backend/src/main/java/com/fintech/api/controller/TransactionController.java`
- Modify: `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java`

- [ ] **Step 1: Escrever testes para delete com scope**

Adicionar ao `TransactionServiceTest`:

```java
@Test
@DisplayName("delete com SINGLE remove apenas a transação informada")
void deleteWithSingleScopeRemovesOnlyOne() {
    User user = buildUser();
    Account account = buildAccount(user);
    UUID txId = UUID.randomUUID();
    Transaction t = Transaction.builder().id(txId)
            .installmentNumber(2).totalInstallments(3)
            .status(TransactionStatus.PENDING)
            .tenant(user.getTenant()).account(account).build();

    when(repository.findByIdAndTenant(txId, user.getTenant())).thenReturn(Optional.of(t));

    DeleteInstallmentResultDTO result = service.delete(txId, DeleteInstallmentScope.SINGLE, user);

    verify(repository).delete(t);
    assertThat(result.deleted()).isEqualTo(1);
    assertThat(result.skippedPaid()).isEqualTo(0);
}

@Test
@DisplayName("delete com ALL pula parcelas PAID e informa quantidade ignorada")
void deleteWithAllScopeSkipsPaidInstallments() {
    User user = buildUser();
    Account account = buildAccount(user);
    UUID groupId = UUID.randomUUID();
    InstallmentGroup group = InstallmentGroup.builder().id(groupId)
            .tenant(user.getTenant()).account(account).build();
    UUID txId = UUID.randomUUID();
    Transaction t = Transaction.builder().id(txId)
            .installmentNumber(1).totalInstallments(3)
            .installmentGroup(group)
            .status(TransactionStatus.PENDING)
            .tenant(user.getTenant()).account(account).build();
    Transaction paid = Transaction.builder().id(UUID.randomUUID())
            .installmentNumber(2).totalInstallments(3)
            .installmentGroup(group)
            .status(TransactionStatus.PAID)
            .tenant(user.getTenant()).account(account).build();
    Transaction pending = Transaction.builder().id(UUID.randomUUID())
            .installmentNumber(3).totalInstallments(3)
            .installmentGroup(group)
            .status(TransactionStatus.PENDING)
            .tenant(user.getTenant()).account(account).build();

    when(repository.findByIdAndTenant(txId, user.getTenant())).thenReturn(Optional.of(t));
    when(repository.findByInstallmentGroupOrderByInstallmentNumberAsc(group))
            .thenReturn(List.of(t, paid, pending));

    DeleteInstallmentResultDTO result = service.delete(txId, DeleteInstallmentScope.ALL, user);

    verify(repository).deleteAll(List.of(t, pending));
    assertThat(result.deleted()).isEqualTo(2);
    assertThat(result.skippedPaid()).isEqualTo(1);
}
```

Adicionar imports:
```java
import com.fintech.api.domain.enums.DeleteInstallmentScope;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
```

- [ ] **Step 2: Executar para confirmar falha**

```bash
cd backend && ./mvnw test -Dtest=TransactionServiceTest#deleteWithSingleScopeRemovesOnlyOne,TransactionServiceTest#deleteWithAllScopeSkipsPaidInstallments -q 2>&1 | tail -10
```

Saída esperada: FAILED — método `delete` ainda tem assinatura antiga.

- [ ] **Step 3: Substituir método `delete()` e atualizar `update()` em TransactionService**

Substituir o método `delete()`:
```java
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
```

Substituir o método `update()`:
```java
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
```

Adicionar imports no TransactionService:
```java
import com.fintech.api.domain.enums.DeleteInstallmentScope;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
```

- [ ] **Step 4: Atualizar TransactionController.deleteTransaction()**

Substituir o método `deleteTransaction`:
```java
@Override
@DeleteMapping("/{id}")
public ResponseEntity<DeleteInstallmentResultDTO> deleteTransaction(
        @PathVariable UUID id,
        @RequestParam(value = "scope", defaultValue = "SINGLE") DeleteInstallmentScope scope) {
    DeleteInstallmentResultDTO result = service.delete(id, scope, getAuthenticatedUser());
    return ResponseEntity.ok(result);
}
```

Adicionar import:
```java
import com.fintech.api.domain.enums.DeleteInstallmentScope;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
```

- [ ] **Step 5: Executar todos os testes**

```bash
cd backend && ./mvnw test -Dtest=TransactionServiceTest -q 2>&1 | tail -10
```

Saída esperada: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/TransactionService.java \
        backend/src/main/java/com/fintech/api/controller/TransactionController.java \
        backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java
git commit -m "feat(service): delete com scope e update com propagate em TransactionService"
```

---

## Task 7: InstallmentGroupService + Controller

**Files:**
- Create: `backend/src/main/java/com/fintech/api/service/InstallmentGroupService.java`
- Create: `backend/src/main/java/com/fintech/api/controller/InstallmentGroupController.java`
- Create: `backend/src/test/java/com/fintech/api/service/InstallmentGroupServiceTest.java`

- [ ] **Step 1: Escrever testes**

```java
package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.installment.InstallmentGroupResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.InstallmentGroupRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstallmentGroupServiceTest {

    @Mock InstallmentGroupRepository groupRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock AccountRepository accountRepository;
    @InjectMocks InstallmentGroupService service;

    @Test
    @DisplayName("findById lança EntityNotFoundException quando grupo não pertence ao tenant")
    void findByIdThrowsForOtherTenant() {
        User user = buildUser();
        UUID groupId = UUID.randomUUID();
        when(groupRepository.findByIdAndTenant(groupId, user.getTenant()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(groupId, user))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("deleteGroup remove apenas parcelas PENDING e retorna contagens corretas")
    void deleteGroupSkipsPaidInstallments() {
        User user = buildUser();
        Account account = buildAccount(user);
        UUID groupId = UUID.randomUUID();
        InstallmentGroup group = buildGroup(groupId, user, account);

        Transaction paid = buildTransaction(group, 1, TransactionStatus.PAID);
        Transaction pending1 = buildTransaction(group, 2, TransactionStatus.PENDING);
        Transaction pending2 = buildTransaction(group, 3, TransactionStatus.PENDING);

        when(groupRepository.findByIdAndTenant(groupId, user.getTenant()))
                .thenReturn(Optional.of(group));
        when(transactionRepository.findByInstallmentGroupOrderByInstallmentNumberAsc(group))
                .thenReturn(List.of(paid, pending1, pending2));

        DeleteInstallmentResultDTO result = service.deleteGroup(groupId, user);

        verify(transactionRepository).deleteAll(List.of(pending1, pending2));
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.skippedPaid()).isEqualTo(1);
    }

    @Test
    @DisplayName("findById retorna DTO com contagens corretas de paidInstallments e pendingInstallments")
    void findByIdReturnsDTOWithCorrectCounts() {
        User user = buildUser();
        Account account = buildAccount(user);
        UUID groupId = UUID.randomUUID();
        InstallmentGroup group = buildGroup(groupId, user, account);

        Transaction paid = buildTransaction(group, 1, TransactionStatus.PAID);
        Transaction pending = buildTransaction(group, 2, TransactionStatus.PENDING);

        when(groupRepository.findByIdAndTenant(groupId, user.getTenant()))
                .thenReturn(Optional.of(group));
        when(transactionRepository.findByInstallmentGroupOrderByInstallmentNumberAsc(group))
                .thenReturn(List.of(paid, pending));

        InstallmentGroupResponseDTO dto = service.findById(groupId, user);

        assertThat(dto.paidInstallments()).isEqualTo(1);
        assertThat(dto.pendingInstallments()).isEqualTo(1);
        assertThat(dto.transactions()).hasSize(2);
    }

    private User buildUser() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        User user = new User();
        user.setTenant(tenant);
        return user;
    }

    private Account buildAccount(User user) {
        return Account.builder()
                .id(UUID.randomUUID())
                .type(AccountType.CHECKING)
                .tenant(user.getTenant())
                .build();
    }

    private InstallmentGroup buildGroup(UUID id, User user, Account account) {
        return InstallmentGroup.builder()
                .id(id)
                .description("Notebook")
                .totalAmount(new BigDecimal("3000.00"))
                .totalInstallments(3)
                .account(account)
                .tenant(user.getTenant())
                .build();
    }

    private Transaction buildTransaction(InstallmentGroup group, int number, TransactionStatus status) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .description("Notebook")
                .amount(new BigDecimal("1000.00"))
                .date(LocalDate.now().plusMonths(number - 1))
                .type(TransactionType.EXPENSE)
                .installmentNumber(number)
                .totalInstallments(3)
                .installmentGroup(group)
                .status(status)
                .tenant(group.getTenant())
                .account(group.getAccount())
                .build();
    }
}
```

- [ ] **Step 2: Executar para confirmar falha**

```bash
cd backend && ./mvnw test -Dtest=InstallmentGroupServiceTest -q 2>&1 | tail -10
```

Saída esperada: FAILED — classe `InstallmentGroupService` não existe.

- [ ] **Step 3: Criar InstallmentGroupService**

```java
package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.installment.InstallmentGroupPatchDTO;
import com.fintech.api.dto.installment.InstallmentGroupResponseDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.InstallmentGroupRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InstallmentGroupService {

    private final InstallmentGroupRepository groupRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<InstallmentGroupResponseDTO> findAll(User user) {
        return groupRepository.findByTenantOrderByCreatedAtDesc(user.getTenant())
                .stream()
                .map(g -> toDTO(g, transactionRepository
                        .findByInstallmentGroupOrderByInstallmentNumberAsc(g)))
                .toList();
    }

    @Transactional(readOnly = true)
    public InstallmentGroupResponseDTO findById(UUID id, User user) {
        InstallmentGroup group = resolveGroup(id, user);
        return toDTO(group, transactionRepository
                .findByInstallmentGroupOrderByInstallmentNumberAsc(group));
    }

    @Transactional
    public DeleteInstallmentResultDTO deleteGroup(UUID id, User user) {
        InstallmentGroup group = resolveGroup(id, user);
        List<Transaction> all = transactionRepository
                .findByInstallmentGroupOrderByInstallmentNumberAsc(group);
        List<Transaction> toDelete = all.stream()
                .filter(t -> t.getStatus() != TransactionStatus.PAID)
                .toList();
        transactionRepository.deleteAll(toDelete);
        return new DeleteInstallmentResultDTO(toDelete.size(), all.size() - toDelete.size());
    }

    @Transactional
    public InstallmentGroupResponseDTO patch(UUID id, InstallmentGroupPatchDTO dto, User user) {
        InstallmentGroup group = resolveGroup(id, user);
        List<Transaction> pending = transactionRepository
                .findByInstallmentGroupOrderByInstallmentNumberAsc(group)
                .stream()
                .filter(t -> t.getStatus() != TransactionStatus.PAID)
                .toList();
        List<String> fields = dto.fields() != null ? dto.fields() : List.of();

        if (fields.contains("description") && dto.description() != null) {
            group.setDescription(dto.description());
            pending.forEach(t -> t.setDescription(dto.description()));
        }
        if (fields.contains("categoryId")) {
            Category cat = dto.categoryId() != null ? resolveCategory(dto.categoryId(), user) : null;
            group.setCategory(cat);
            pending.forEach(t -> t.setCategory(cat));
        }
        if (fields.contains("accountId") && dto.accountId() != null) {
            Account acc = resolveAccount(dto.accountId(), user);
            group.setAccount(acc);
            pending.forEach(t -> t.setAccount(acc));
        }
        if (fields.contains("installmentAmount") && dto.installmentAmount() != null) {
            pending.forEach(t -> t.setAmount(dto.installmentAmount()));
        }
        if (fields.contains("status") && dto.status() != null) {
            pending.forEach(t -> t.setStatus(dto.status()));
        }

        return toDTO(group, transactionRepository
                .findByInstallmentGroupOrderByInstallmentNumberAsc(group));
    }

    private InstallmentGroup resolveGroup(UUID id, User user) {
        return groupRepository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Grupo de parcelamento não encontrado."));
    }

    private Category resolveCategory(UUID categoryId, User user) {
        return categoryRepository.findByIdAndTenantIdAndDeletedAtIsNull(categoryId, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
    }

    private Account resolveAccount(UUID accountId, User user) {
        return accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
    }

    private InstallmentGroupResponseDTO toDTO(InstallmentGroup group, List<Transaction> txs) {
        long paidCount    = txs.stream().filter(t -> t.getStatus() == TransactionStatus.PAID).count();
        long pendingCount = txs.stream().filter(t -> t.getStatus() == TransactionStatus.PENDING).count();
        LocalDate nextDue = txs.stream()
                .filter(t -> t.getStatus() == TransactionStatus.PENDING)
                .map(Transaction::getDate)
                .min(LocalDate::compareTo)
                .orElse(null);
        BigDecimal installmentAmt = txs.isEmpty() ? BigDecimal.ZERO : txs.get(0).getAmount();

        return new InstallmentGroupResponseDTO(
                group.getId(),
                group.getDescription(),
                group.getTotalAmount(),
                installmentAmt,
                group.getTotalInstallments(),
                paidCount,
                pendingCount,
                nextDue,
                group.getCategory() != null ? group.getCategory().getName() : null,
                group.getCategory() != null ? group.getCategory().getId() : null,
                group.getAccount() != null ? group.getAccount().getName() : null,
                group.getAccount() != null ? group.getAccount().getId() : null,
                txs.stream().map(TransactionResponseDTO::fromEntity).toList()
        );
    }
}
```

- [ ] **Step 4: Criar InstallmentGroupController (stub — implementa interface após geração OpenAPI)**

```java
package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.installment.InstallmentGroupPatchDTO;
import com.fintech.api.dto.installment.InstallmentGroupResponseDTO;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.InstallmentGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/installment-groups")
@RequiredArgsConstructor
public class InstallmentGroupController {

    private final InstallmentGroupService service;

    @GetMapping
    public ResponseEntity<List<InstallmentGroupResponseDTO>> listInstallmentGroups() {
        return ResponseEntity.ok(service.findAll(getAuthenticatedUser()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InstallmentGroupResponseDTO> getInstallmentGroup(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id, getAuthenticatedUser()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<DeleteInstallmentResultDTO> deleteInstallmentGroup(@PathVariable UUID id) {
        return ResponseEntity.ok(service.deleteGroup(id, getAuthenticatedUser()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<InstallmentGroupResponseDTO> patchInstallmentGroup(
            @PathVariable UUID id,
            @RequestBody InstallmentGroupPatchDTO dto) {
        return ResponseEntity.ok(service.patch(id, dto, getAuthenticatedUser()));
    }

    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 5: Executar testes**

```bash
cd backend && ./mvnw test -Dtest=InstallmentGroupServiceTest -q 2>&1 | tail -10
```

Saída esperada: BUILD SUCCESS, 3 testes passando.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/InstallmentGroupService.java \
        backend/src/main/java/com/fintech/api/controller/InstallmentGroupController.java \
        backend/src/test/java/com/fintech/api/service/InstallmentGroupServiceTest.java
git commit -m "feat(service): adiciona InstallmentGroupService e InstallmentGroupController"
```

---

## Task 8: OpenAPI spec + regenerar clientes backend e frontend

**Files:**
- Modify: `api-spec/openapi.yaml`
- Modify: `backend/src/main/resources/static/openapi.yaml` (manter em sync)
- Modify: `backend/pom.xml`

- [ ] **Step 1: Adicionar schemas ao openapi.yaml**

Em `api-spec/openapi.yaml`, dentro da seção `components.schemas`, adicionar após o schema `TransactionResponseDTO`:

```yaml
    DeleteInstallmentScope:
      type: string
      enum: [SINGLE, THIS_AND_NEXT, ALL]
      default: SINGLE

    DeleteInstallmentResultDTO:
      type: object
      required: [deleted, skippedPaid]
      properties:
        deleted:
          type: integer
        skippedPaid:
          type: integer

    InstallmentGroupPatchDTO:
      type: object
      properties:
        description:
          type: string
          nullable: true
        categoryId:
          type: string
          format: uuid
          nullable: true
        accountId:
          type: string
          format: uuid
          nullable: true
        installmentAmount:
          type: number
          format: double
          nullable: true
        status:
          $ref: '#/components/schemas/TransactionStatus'
          nullable: true
        fields:
          type: array
          items:
            type: string

    InstallmentGroupResponseDTO:
      type: object
      required: [id, description, totalAmount, installmentAmount, totalInstallments, paidInstallments, pendingInstallments, accountName, transactions]
      properties:
        id:
          type: string
          format: uuid
        description:
          type: string
        totalAmount:
          type: number
          format: double
        installmentAmount:
          type: number
          format: double
        totalInstallments:
          type: integer
        paidInstallments:
          type: integer
          format: int64
        pendingInstallments:
          type: integer
          format: int64
        nextDueDate:
          type: string
          format: date
          nullable: true
        categoryName:
          type: string
          nullable: true
        categoryId:
          type: string
          format: uuid
          nullable: true
        accountName:
          type: string
        accountId:
          type: string
          format: uuid
        transactions:
          type: array
          items:
            $ref: '#/components/schemas/TransactionResponseDTO'
```

- [ ] **Step 2: Atualizar schemas existentes no openapi.yaml**

No schema `TransactionResponseDTO`, adicionar após `transferId`:
```yaml
        installmentGroupId:
          type: string
          format: uuid
          nullable: true
        installmentGroupDescription:
          type: string
          nullable: true
        installmentNumber:
          type: integer
          nullable: true
        totalInstallments:
          type: integer
          nullable: true
```

No schema `TransactionUpdateDTO`, adicionar ao final:
```yaml
        propagate:
          type: array
          items:
            type: string
          nullable: true
```

- [ ] **Step 3: Atualizar endpoint DELETE /api/transactions/{id} no openapi.yaml**

Localizar o path `/api/transactions/{id}` e substituir o bloco `delete`:
```yaml
    delete:
      tags: [transactions]
      operationId: deleteTransaction
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
        - name: scope
          in: query
          required: false
          schema:
            $ref: '#/components/schemas/DeleteInstallmentScope'
      responses:
        '200':
          description: Resultado da exclusão
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/DeleteInstallmentResultDTO'
        '404':
          description: Transação não encontrada
```

- [ ] **Step 4: Adicionar paths de /api/installment-groups ao openapi.yaml**

Adicionar ao final da seção `paths`:
```yaml
  /api/installment-groups:
    get:
      tags: [installment-groups]
      operationId: listInstallmentGroups
      responses:
        '200':
          description: Lista de grupos de parcelamento
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/InstallmentGroupResponseDTO'

  /api/installment-groups/{id}:
    get:
      tags: [installment-groups]
      operationId: getInstallmentGroup
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Detalhe do grupo
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InstallmentGroupResponseDTO'
        '404':
          description: Grupo não encontrado
    delete:
      tags: [installment-groups]
      operationId: deleteInstallmentGroup
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Resultado da exclusão
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/DeleteInstallmentResultDTO'
        '404':
          description: Grupo não encontrado
    patch:
      tags: [installment-groups]
      operationId: patchInstallmentGroup
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
              $ref: '#/components/schemas/InstallmentGroupPatchDTO'
      responses:
        '200':
          description: Grupo atualizado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InstallmentGroupResponseDTO'
```

- [ ] **Step 5: Copiar spec para static (manter em sync)**

```bash
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
```

- [ ] **Step 6: Adicionar importMappings ao pom.xml**

Dentro do bloco `<importMappings>` do plugin openapi-generator, adicionar:
```xml
<importMapping>InstallmentGroupResponseDTO=com.fintech.api.dto.installment.InstallmentGroupResponseDTO</importMapping>
<importMapping>InstallmentGroupPatchDTO=com.fintech.api.dto.installment.InstallmentGroupPatchDTO</importMapping>
<importMapping>DeleteInstallmentResultDTO=com.fintech.api.dto.installment.DeleteInstallmentResultDTO</importMapping>
<importMapping>DeleteInstallmentScope=com.fintech.api.domain.enums.DeleteInstallmentScope</importMapping>
```

- [ ] **Step 7: Regenerar interfaces backend**

```bash
cd backend && ./mvnw generate-sources -q
```

Saída esperada: BUILD SUCCESS. Verificar que `target/generated-sources/openapi/com/fintech/api/openapi/InstallmentGroupsApi.java` foi criado.

```bash
ls backend/target/generated-sources/openapi/com/fintech/api/openapi/
```

Saída esperada: inclui `InstallmentGroupsApi.java` e `TransactionsApi.java` (atualizado).

- [ ] **Step 8: Fazer InstallmentGroupController implementar a interface gerada**

Atualizar `InstallmentGroupController.java` — adicionar `implements InstallmentGroupsApi`:
```java
import com.fintech.api.openapi.InstallmentGroupsApi;

@RestController
@RequestMapping("/api/installment-groups")
@RequiredArgsConstructor
public class InstallmentGroupController implements InstallmentGroupsApi {
    // ... (adicionar @Override em cada método)
```

Adicionar `@Override` em cada um dos 4 métodos do controller.

- [ ] **Step 9: Compilar backend completo**

```bash
cd backend && ./mvnw compile -q
```

Saída esperada: BUILD SUCCESS.

- [ ] **Step 10: Regenerar cliente Orval no frontend**

```bash
cd frontend && npm run api:generate
```

Saída esperada: arquivos gerados em `src/app/core/api/installment-groups/` e `src/app/core/api/transactions/` atualizados.

- [ ] **Step 11: Commit**

```bash
git add api-spec/openapi.yaml \
        backend/src/main/resources/static/openapi.yaml \
        backend/pom.xml \
        frontend/src/app/core/api/
git commit -m "feat(openapi): adiciona schemas e endpoints de InstallmentGroup; regenera clientes"
```

---

## Task 9: Frontend — listagem com grupos colapsáveis

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.html`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.scss`

- [ ] **Step 1: Escrever testes para a lógica de agrupamento**

Criar `frontend/src/app/features/transaction/transaction-list/transaction-list.spec.ts`:

```typescript
import { describe, it, expect } from 'vitest';

// Pure function to test — will be extracted from the component
function buildDisplayRows(
  transactions: Array<{ id: string; installmentGroupId?: string; installmentGroupDescription?: string; description: string; status: string; installmentNumber?: number; totalInstallments?: number; amount: number; date: string }>,
  expandedGroups: Set<string>
) {
  type GroupRow = { kind: 'group'; groupId: string; description: string; totalInstallments: number; paidInstallments: number; transactions: typeof transactions };
  type TxRow = { kind: 'transaction'; data: (typeof transactions)[0] };
  type SingleRow = { kind: 'single'; data: (typeof transactions)[0] };

  const result: (GroupRow | TxRow | SingleRow)[] = [];
  const seenGroups = new Set<string>();
  const groupsMap = new Map<string, typeof transactions>();

  for (const t of transactions) {
    if (t.installmentGroupId) {
      const existing = groupsMap.get(t.installmentGroupId) ?? [];
      existing.push(t);
      groupsMap.set(t.installmentGroupId, existing);
    }
  }

  for (const t of transactions) {
    if (t.installmentGroupId) {
      if (!seenGroups.has(t.installmentGroupId)) {
        seenGroups.add(t.installmentGroupId);
        const groupTxs = groupsMap.get(t.installmentGroupId)!;
        const paidCount = groupTxs.filter(tx => tx.status === 'PAID').length;
        result.push({
          kind: 'group',
          groupId: t.installmentGroupId,
          description: t.installmentGroupDescription ?? t.description,
          totalInstallments: groupTxs.length,
          paidInstallments: paidCount,
          transactions: groupTxs
        });
        if (expandedGroups.has(t.installmentGroupId)) {
          groupTxs.forEach(tx => result.push({ kind: 'transaction', data: tx }));
        }
      }
    } else {
      result.push({ kind: 'single', data: t });
    }
  }
  return result;
}

describe('buildDisplayRows', () => {
  it('agrupa transações do mesmo installmentGroupId em uma única linha group', () => {
    const txs = [
      { id: '1', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PAID', installmentNumber: 1, totalInstallments: 3, amount: 1000, date: '2026-01-01' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 3, amount: 1000, date: '2026-02-01' },
      { id: '3', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 3, totalInstallments: 3, amount: 1000, date: '2026-03-01' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(1);
    expect(rows[0].kind).toBe('group');
    if (rows[0].kind === 'group') {
      expect(rows[0].paidInstallments).toBe(1);
      expect(rows[0].totalInstallments).toBe(3);
    }
  });

  it('expande parcelas individuais quando grupo está no expandedGroups', () => {
    const txs = [
      { id: '1', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 1, totalInstallments: 2, amount: 500, date: '2026-01-01' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 2, amount: 500, date: '2026-02-01' },
    ];

    const rows = buildDisplayRows(txs, new Set(['g1']));

    expect(rows).toHaveLength(3); // 1 group header + 2 installment rows
    expect(rows[0].kind).toBe('group');
    expect(rows[1].kind).toBe('transaction');
    expect(rows[2].kind).toBe('transaction');
  });

  it('mantém transações avulsas como linhas single separadas', () => {
    const txs = [
      { id: '1', description: 'Salário', status: 'PAID', amount: 5000, date: '2026-01-05' },
      { id: '2', description: 'Mercado', status: 'PAID', amount: 300, date: '2026-01-10' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(2);
    rows.forEach(r => expect(r.kind).toBe('single'));
  });
});
```

- [ ] **Step 2: Executar testes para confirmar falha**

```bash
cd frontend && npm test -- --run transaction-list.spec 2>&1 | tail -15
```

Saída esperada: FAIL — `buildDisplayRows` não está importável ainda (existe apenas no spec).

- [ ] **Step 3: Atualizar transaction-list.ts**

Substituir o conteúdo completo do arquivo:

```typescript
import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { InstallmentGroupsService } from '../../../core/api/installment-groups/installment-groups.service';
import { TransfersService } from '../../../core/api/transfers/transfers.service';
import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';
import { DeleteInstallmentDialogComponent, DeleteInstallmentDialogResult } from './delete-installment-dialog/delete-installment-dialog';

export type GroupRow = {
  kind: 'group';
  groupId: string;
  description: string;
  totalInstallments: number;
  paidInstallments: number;
  installmentAmount: number;
  categoryName: string | null;
  accountName: string | null;
  transactions: TransactionResponseDTO[];
};

export type DisplayRow =
  | GroupRow
  | { kind: 'transaction'; data: TransactionResponseDTO }
  | { kind: 'single'; data: TransactionResponseDTO };

export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedGroups: Set<string>
): DisplayRow[] {
  const result: DisplayRow[] = [];
  const seenGroups = new Set<string>();
  const groupsMap = new Map<string, TransactionResponseDTO[]>();

  for (const t of transactions) {
    if (t.installmentGroupId) {
      const existing = groupsMap.get(t.installmentGroupId) ?? [];
      existing.push(t);
      groupsMap.set(t.installmentGroupId, existing);
    }
  }

  for (const t of transactions) {
    if (t.installmentGroupId) {
      if (!seenGroups.has(t.installmentGroupId)) {
        seenGroups.add(t.installmentGroupId);
        const groupTxs = groupsMap.get(t.installmentGroupId)!;
        const paidCount = groupTxs.filter(tx => tx.status === 'PAID').length;
        result.push({
          kind: 'group',
          groupId: t.installmentGroupId,
          description: t.installmentGroupDescription ?? t.description ?? '',
          totalInstallments: groupTxs.length,
          paidInstallments: paidCount,
          installmentAmount: groupTxs[0]?.amount ?? 0,
          categoryName: t.categoryName ?? null,
          accountName: t.accountName ?? null,
          transactions: groupTxs
        });
        if (expandedGroups.has(t.installmentGroupId)) {
          groupTxs.forEach(tx => result.push({ kind: 'transaction', data: tx }));
        }
      }
    } else {
      result.push({ kind: 'single', data: t });
    }
  }
  return result;
}

@Component({
  selector: 'app-transaction-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatDialogModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatProgressBarModule,
    CurrencyPipe,
    DatePipe
  ],
  templateUrl: './transaction-list.html',
  styleUrl: './transaction-list.scss'
})
export class TransactionList implements OnInit {
  private service = inject(TransactionsService);
  private groupService = inject(InstallmentGroupsService);
  private transferService = inject(TransfersService);
  private router = inject(Router);
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  transactions = signal<TransactionResponseDTO[]>([]);
  expandedGroups = signal(new Set<string>());

  displayedColumns = ['description', 'amount', 'date', 'type', 'status', 'category', 'account', 'actions'];
  groupColumns = ['group-header'];

  displayRows = computed(() => buildDisplayRows(this.transactions(), this.expandedGroups()));

  isGroupRow = (_: number, row: DisplayRow) => row.kind === 'group';
  isDataRow = (_: number, row: DisplayRow) => row.kind === 'transaction' || row.kind === 'single';

  ngOnInit(): void {
    this.loadTransactions();
  }

  loadTransactions(): void {
    this.service.listTransactions().subscribe({
      next: (data) => this.transactions.set(data),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 })
    });
  }

  toggleGroup(groupId: string): void {
    this.expandedGroups.update(set => {
      const next = new Set(set);
      next.has(groupId) ? next.delete(groupId) : next.add(groupId);
      return next;
    });
  }

  onEdit(t: TransactionResponseDTO): void {
    this.router.navigate(['/transactions', t.id]);
  }

  onDeleteGroup(row: GroupRow): void {
    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir grupo de parcelamento',
        message: `Deseja excluir o grupo "${row.description}"? Parcelas já pagas serão mantidas no histórico.`,
        confirmText: 'Sim, excluir pendentes'
      }
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed !== true) return;
      this.groupService.deleteInstallmentGroup(row.groupId).subscribe({
        next: (result) => {
          const msg = result.skippedPaid > 0
            ? `${result.deleted} parcela(s) excluída(s). ${result.skippedPaid} pagas foram mantidas.`
            : `${result.deleted} parcela(s) excluída(s).`;
          this.snackBar.open(msg, 'OK', { duration: 4000 });
          this.loadTransactions();
        },
        error: () => this.snackBar.open('Erro ao excluir grupo.', 'Fechar', { duration: 5000 })
      });
    });
  }

  onDelete(t: TransactionResponseDTO): void {
    const isTransfer = !!t.transferId;
    const isInstallment = !!t.installmentGroupId;

    if (isTransfer) {
      this.confirmDeleteTransfer(t);
      return;
    }

    if (isInstallment) {
      this.confirmDeleteInstallment(t);
      return;
    }

    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir Transação',
        message: `Deseja excluir "${t.description}"? Esta ação não pode ser desfeita.`,
        confirmText: 'Sim, excluir'
      }
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed !== true) return;
      this.service.deleteTransaction(t.id).subscribe({
        next: () => { this.snackBar.open('Transação excluída.', 'OK', { duration: 3000 }); this.loadTransactions(); },
        error: () => this.snackBar.open('Erro ao excluir transação.', 'Fechar', { duration: 5000 })
      });
    });
  }

  private confirmDeleteTransfer(t: TransactionResponseDTO): void {
    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir Transferência',
        message: 'Deseja excluir esta transferência? Os dois lançamentos serão removidos.',
        confirmText: 'Sim, excluir'
      }
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed !== true) return;
      this.transferService.deleteTransfer(t.transferId!).subscribe({
        next: () => { this.snackBar.open('Transferência excluída.', 'OK', { duration: 3000 }); this.loadTransactions(); },
        error: () => this.snackBar.open('Erro ao excluir transferência.', 'Fechar', { duration: 5000 })
      });
    });
  }

  private confirmDeleteInstallment(t: TransactionResponseDTO): void {
    const dialogRef = this.dialog.open(DeleteInstallmentDialogComponent, {
      width: '460px',
      data: { transaction: t }
    });
    dialogRef.afterClosed().subscribe((result: DeleteInstallmentDialogResult | undefined) => {
      if (!result) return;
      this.service.deleteTransaction(t.id, { scope: result.scope }).subscribe({
        next: (res) => {
          const msg = res.skippedPaid > 0
            ? `${res.deleted} parcela(s) excluída(s). ${res.skippedPaid} pagas foram mantidas.`
            : `${res.deleted} parcela(s) excluída(s).`;
          this.snackBar.open(msg, 'OK', { duration: 4000 });
          this.loadTransactions();
        },
        error: () => this.snackBar.open('Erro ao excluir parcela.', 'Fechar', { duration: 5000 })
      });
    });
  }

  typeLabel(t: TransactionResponseDTO): string {
    if (t.transferId) return 'Transferência';
    const labels: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' };
    return labels[t.type ?? ''] ?? (t.type ?? '');
  }

  statusLabel(status: string): string {
    const labels: Record<string, string> = { PENDING: 'Pendente', PAID: 'Pago', CANCELLED: 'Cancelado' };
    return labels[status] ?? status;
  }
}
```

- [ ] **Step 4: Atualizar o spec para importar de transaction-list.ts**

Substituir o import do spec:
```typescript
import { buildDisplayRows } from './transaction-list';
// Remover a definição local de buildDisplayRows do spec
```

- [ ] **Step 5: Executar testes**

```bash
cd frontend && npm test -- --run transaction-list.spec 2>&1 | tail -15
```

Saída esperada: 3 testes passando.

- [ ] **Step 6: Atualizar transaction-list.html**

Substituir o conteúdo completo:

```html
<div class="page-container">
  <header class="page-header">
    <div>
      <h1>Transações</h1>
      <p class="subtitle">Acompanhe suas receitas, despesas e transferências</p>
    </div>
    <button mat-flat-button color="primary" routerLink="/transactions/new">
      <mat-icon>add</mat-icon>
      Nova Transação
    </button>
  </header>

  <div class="table-container mat-elevation-z2">
    <table mat-table [dataSource]="displayRows()">

      <!-- Linha de grupo (colapsável) -->
      <ng-container matColumnDef="group-header">
        <td mat-cell *matCellDef="let row" [attr.colspan]="displayedColumns.length" class="group-header-cell">
          <div class="group-row">
            <button mat-icon-button (click)="toggleGroup(row.groupId)" class="expand-btn">
              <mat-icon>{{ expandedGroups().has(row.groupId) ? 'expand_less' : 'expand_more' }}</mat-icon>
            </button>
            <div class="group-info">
              <span class="group-description">{{ row.description }}</span>
              <span class="group-meta">
                {{ row.totalInstallments }}x {{ row.installmentAmount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
                &nbsp;·&nbsp; {{ row.paidInstallments }}/{{ row.totalInstallments }} pagas
              </span>
            </div>
            <mat-progress-bar
              mode="determinate"
              [value]="(row.paidInstallments / row.totalInstallments) * 100"
              class="group-progress">
            </mat-progress-bar>
            <span class="group-badge">{{ row.categoryName ?? '—' }}</span>
            <span class="group-badge">{{ row.accountName ?? '—' }}</span>
            <div class="actions-group">
              <button mat-icon-button color="warn" (click)="onDeleteGroup(row)" matTooltip="Excluir grupo">
                <mat-icon>delete_sweep</mat-icon>
              </button>
            </div>
          </div>
        </td>
      </ng-container>

      <!-- Colunas de transação (avulsas e parcelas expandidas) -->
      <ng-container matColumnDef="description">
        <th mat-header-cell *matHeaderCellDef>Descrição</th>
        <td mat-cell *matCellDef="let row">
          @if (row.kind === 'transaction') {
            <span class="installment-indent">↳</span>
          }
          <span class="description-text">{{ row.data?.description }}</span>
          @if (row.data?.installmentLabel) {
            <span class="installment-badge">{{ row.data.installmentLabel }}</span>
          }
        </td>
      </ng-container>

      <ng-container matColumnDef="amount">
        <th mat-header-cell *matHeaderCellDef>Valor</th>
        <td mat-cell *matCellDef="let row">
          <span [class]="'amount ' + (row.data?.type ?? '').toLowerCase()">
            {{ row.data?.amount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
          </span>
        </td>
      </ng-container>

      <ng-container matColumnDef="date">
        <th mat-header-cell *matHeaderCellDef>Data</th>
        <td mat-cell *matCellDef="let row">{{ row.data?.date | date:'dd/MM/yyyy' }}</td>
      </ng-container>

      <ng-container matColumnDef="type">
        <th mat-header-cell *matHeaderCellDef>Tipo</th>
        <td mat-cell *matCellDef="let row">
          <span [class]="'type-badge type-' + (row.data?.transferId ? 'transfer' : (row.data?.type ?? '').toLowerCase())">
            {{ typeLabel(row.data) }}
          </span>
        </td>
      </ng-container>

      <ng-container matColumnDef="status">
        <th mat-header-cell *matHeaderCellDef>Status</th>
        <td mat-cell *matCellDef="let row">
          <span [class]="'status-badge status-' + (row.data?.status ?? '').toLowerCase()">
            {{ statusLabel(row.data?.status ?? '') }}
          </span>
        </td>
      </ng-container>

      <ng-container matColumnDef="category">
        <th mat-header-cell *matHeaderCellDef>Categoria</th>
        <td mat-cell *matCellDef="let row">
          @if (row.data?.categoryName) {
            <span
              [class.category-archived]="row.data.categoryArchived"
              [matTooltip]="row.data.categoryArchived ? 'Categoria arquivada' : ''">
              {{ row.data.categoryName }}
            </span>
          } @else { — }
        </td>
      </ng-container>

      <ng-container matColumnDef="account">
        <th mat-header-cell *matHeaderCellDef>Conta</th>
        <td mat-cell *matCellDef="let row">{{ row.data?.accountName ?? '—' }}</td>
      </ng-container>

      <ng-container matColumnDef="actions">
        <th mat-header-cell *matHeaderCellDef></th>
        <td mat-cell *matCellDef="let row">
          <div class="actions-group">
            <button mat-icon-button color="primary"
                    (click)="onEdit(row.data)"
                    [disabled]="!!row.data?.transferId"
                    [matTooltip]="row.data?.transferId ? 'Transferências não podem ser editadas' : 'Editar'">
              <mat-icon>edit</mat-icon>
            </button>
            <button mat-icon-button color="warn" (click)="onDelete(row.data)" matTooltip="Excluir">
              <mat-icon>delete</mat-icon>
            </button>
          </div>
        </td>
      </ng-container>

      <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
      <tr mat-row *matRowDef="let row; columns: groupColumns; when: isGroupRow" class="group-row-tr"></tr>
      <tr mat-row *matRowDef="let row; columns: displayedColumns; when: isDataRow" class="element-row"></tr>

      <tr class="mat-row" *matNoDataRow>
        <td class="mat-cell empty-state" [attr.colspan]="displayedColumns.length">
          <mat-icon>receipt_long</mat-icon>
          <p>Nenhuma transação encontrada.</p>
        </td>
      </tr>
    </table>
  </div>
</div>
```

- [ ] **Step 7: Adicionar estilos ao transaction-list.scss**

Adicionar ao final do arquivo existente:

```scss
.group-header-cell {
  padding: 0 !important;
}

.group-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  background: var(--mat-sys-surface-container-low, #f5f5f5);
  border-left: 4px solid var(--mat-sys-primary, #1976d2);

  .group-info {
    flex: 1;
    display: flex;
    flex-direction: column;

    .group-description {
      font-weight: 600;
      font-size: 0.95rem;
    }

    .group-meta {
      font-size: 0.8rem;
      color: rgba(0, 0, 0, 0.6);
    }
  }

  .group-progress {
    width: 100px;
    border-radius: 4px;
  }

  .group-badge {
    font-size: 0.8rem;
    color: rgba(0, 0, 0, 0.6);
    white-space: nowrap;
  }
}

.group-row-tr td {
  border-bottom: none;
}

.installment-indent {
  margin-right: 6px;
  color: rgba(0, 0, 0, 0.4);
  font-size: 0.85rem;
}
```

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/
git commit -m "feat(frontend): listagem de transações com grupos parcelados colapsáveis"
```

---

## Task 10: Frontend — DeleteInstallmentDialog

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-list/delete-installment-dialog/delete-installment-dialog.ts`
- Create: `frontend/src/app/features/transaction/transaction-list/delete-installment-dialog/delete-installment-dialog.html`

- [ ] **Step 1: Criar o componente**

```typescript
import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FormsModule } from '@angular/forms';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';

export type DeleteInstallmentScope = 'SINGLE' | 'THIS_AND_NEXT' | 'ALL';

export interface DeleteInstallmentDialogResult {
  scope: DeleteInstallmentScope;
}

interface DialogData {
  transaction: TransactionResponseDTO;
}

@Component({
  selector: 'app-delete-installment-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatRadioModule, MatButtonModule, MatIconModule, FormsModule],
  templateUrl: './delete-installment-dialog.html'
})
export class DeleteInstallmentDialogComponent {
  private dialogRef = inject(MatDialogRef<DeleteInstallmentDialogComponent>);
  data: DialogData = inject(MAT_DIALOG_DATA);

  scope: DeleteInstallmentScope = 'SINGLE';

  get transaction() { return this.data.transaction; }

  get label(): string {
    return `${this.transaction.installmentLabel ?? ''}`;
  }

  get hasPaidWarning(): boolean {
    if (this.scope === 'SINGLE') return false;
    const txs = this.transaction; // warning is shown based on group data — simplified to backend response
    return false; // backend retorna skippedPaid; aviso pós-ação via snackbar
  }

  confirm(): void {
    this.dialogRef.close({ scope: this.scope } as DeleteInstallmentDialogResult);
  }

  cancel(): void {
    this.dialogRef.close(undefined);
  }
}
```

- [ ] **Step 2: Criar o template**

```html
<h2 mat-dialog-title>Excluir parcela</h2>

<mat-dialog-content>
  <p class="installment-title">
    <strong>{{ transaction.description }}</strong>
    @if (transaction.installmentLabel) {
      &nbsp;— Parcela {{ transaction.installmentLabel }}
    }
  </p>

  <mat-radio-group [(ngModel)]="scope" class="scope-group">
    <mat-radio-button value="SINGLE">
      Somente esta parcela
    </mat-radio-button>
    <mat-radio-button value="THIS_AND_NEXT">
      Esta e as próximas
    </mat-radio-button>
    <mat-radio-button value="ALL">
      Todo o grupo de parcelamento
    </mat-radio-button>
  </mat-radio-group>

  @if (scope !== 'SINGLE') {
    <div class="paid-warning">
      <mat-icon>info</mat-icon>
      <span>Parcelas já pagas não serão excluídas — elas fazem parte do histórico financeiro.
        Para excluí-las individualmente, selecione "Somente esta parcela" em cada uma.</span>
    </div>
  }
</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button (click)="cancel()">Cancelar</button>
  <button mat-flat-button color="warn" (click)="confirm()">Confirmar</button>
</mat-dialog-actions>
```

- [ ] **Step 3: Adicionar estilos inline via SCSS (opcional)**

Criar `delete-installment-dialog.scss` com:
```scss
.installment-title {
  margin-bottom: 16px;
  font-size: 0.95rem;
}

.scope-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.paid-warning {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: #fff8e1;
  border-radius: 4px;
  font-size: 0.85rem;
  color: #5f4000;

  mat-icon {
    font-size: 18px;
    width: 18px;
    height: 18px;
    color: #f9a825;
  }
}
```

Adicionar `styleUrl: './delete-installment-dialog.scss'` no decorator do componente.

- [ ] **Step 4: Compilar**

```bash
cd frontend && npm run build -- --configuration development 2>&1 | tail -10
```

Saída esperada: sem erros de compilação.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/delete-installment-dialog/
git commit -m "feat(frontend): adiciona DeleteInstallmentDialog com seleção de escopo"
```

---

## Task 11: Frontend — Transaction Form (toggle + preview live + propagação)

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.ts`
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.html`
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.scss`

- [ ] **Step 1: Escrever testes para o preview**

Criar `frontend/src/app/features/transaction/transaction-form/transaction-form.spec.ts`:

```typescript
import { describe, it, expect } from 'vitest';

interface InstallmentPreviewRow {
  number: number;
  date: string;
  amount: number;
}

function buildInstallmentPreview(
  totalAmount: number,
  installments: number,
  startDate: Date,
  valueMode: 'total' | 'per-installment'
): InstallmentPreviewRow[] {
  if (installments <= 0 || totalAmount <= 0) return [];
  const installmentAmount = valueMode === 'total'
    ? Math.round((totalAmount / installments) * 100) / 100
    : totalAmount;

  return Array.from({ length: installments }, (_, i) => {
    const d = new Date(startDate);
    d.setMonth(d.getMonth() + i);
    return {
      number: i + 1,
      date: d.toLocaleDateString('pt-BR'),
      amount: installmentAmount
    };
  });
}

describe('buildInstallmentPreview', () => {
  it('divide valor total igualmente entre as parcelas', () => {
    const rows = buildInstallmentPreview(3000, 3, new Date('2026-01-01'), 'total');
    expect(rows).toHaveLength(3);
    expect(rows[0].amount).toBe(1000);
    expect(rows[1].amount).toBe(1000);
  });

  it('avança data mensalmente', () => {
    const rows = buildInstallmentPreview(1000, 3, new Date('2026-01-01'), 'total');
    expect(rows[0].date).toBe('01/01/2026');
    expect(rows[1].date).toBe('01/02/2026');
    expect(rows[2].date).toBe('01/03/2026');
  });

  it('usa valor da parcela diretamente no modo per-installment', () => {
    const rows = buildInstallmentPreview(500, 4, new Date('2026-01-01'), 'per-installment');
    rows.forEach(r => expect(r.amount).toBe(500));
    expect(rows).toHaveLength(4);
  });

  it('retorna array vazio para installments <= 0', () => {
    expect(buildInstallmentPreview(1000, 0, new Date(), 'total')).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Executar para confirmar falha**

```bash
cd frontend && npm test -- --run transaction-form.spec 2>&1 | tail -10
```

- [ ] **Step 3: Adicionar lógica ao transaction-form.ts**

Adicionar os imports necessários no topo do arquivo:

```typescript
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatRadioModule } from '@angular/material/radio';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDividerModule } from '@angular/material/divider';
```

Adicionar à lista `imports` do decorator: `MatSlideToggleModule`, `MatRadioModule`, `MatCheckboxModule`, `MatDividerModule`.

Adicionar os novos signals e função depois da declaração de `mode`:

```typescript
isInstallment = signal(false);
valueMode = signal<'total' | 'per-installment'>('total');
propagateFields = signal<Set<string>>(new Set());

installmentPreview = computed(() => {
  if (!this.isInstallment()) return [];
  const amount = this.form.controls.amount.value ?? 0;
  const installments = this.form.controls.totalInstallments.value ?? 1;
  return buildInstallmentPreview(amount, installments, this.form.controls.date.value ?? new Date(), this.valueMode());
});
```

Adicionar a função `buildInstallmentPreview` exportada no topo do arquivo (antes do `@Component`):

```typescript
export interface InstallmentPreviewRow {
  number: number;
  date: string;
  amount: number;
}

export function buildInstallmentPreview(
  totalAmount: number,
  installments: number,
  startDate: Date,
  valueMode: 'total' | 'per-installment'
): InstallmentPreviewRow[] {
  if (installments <= 0 || totalAmount <= 0) return [];
  const installmentAmount = valueMode === 'total'
    ? Math.round((totalAmount / installments) * 100) / 100
    : totalAmount;

  return Array.from({ length: installments }, (_, i) => {
    const d = new Date(startDate);
    d.setMonth(d.getMonth() + i);
    return {
      number: i + 1,
      date: d.toLocaleDateString('pt-BR'),
      amount: installmentAmount
    };
  });
}
```

Adicionar método para propagação:
```typescript
togglePropagate(field: string): void {
  this.propagateFields.update(set => {
    const next = new Set(set);
    next.has(field) ? next.delete(field) : next.add(field);
    return next;
  });
}

isPropagating(field: string): boolean {
  return this.propagateFields().has(field);
}
```

Atualizar o `onSubmit()` — no bloco `updateTransaction`, adicionar `propagate`:
```typescript
this.transactionService.updateTransaction(this.transactionId()!, {
  description: raw.description!,
  amount: raw.amount!,
  date: this.toDateString(raw.date as Date),
  type: raw.type as 'INCOME' | 'EXPENSE',
  status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
  categoryId: raw.categoryId ?? undefined,
  accountId: raw.accountId!,
  propagate: this.propagateFields().size > 0 ? Array.from(this.propagateFields()) : undefined
}).subscribe({ /* ... mesmo que antes ... */ });
```

No bloco `createTransaction`, atualizar para considerar valueMode:
```typescript
const rawAmount = raw.amount!;
const totalAmount = this.valueMode() === 'per-installment'
  ? rawAmount * (raw.totalInstallments ?? 1)
  : rawAmount;

this.transactionService.createTransaction({
  description: raw.description!,
  amount: totalAmount,
  date: this.toDateString(raw.date as Date),
  type: raw.type as 'INCOME' | 'EXPENSE',
  status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
  categoryId: raw.categoryId ?? undefined,
  accountId: raw.accountId!,
  totalInstallments: this.isInstallment() ? (raw.totalInstallments ?? 1) : 1
}).subscribe({ /* ... mesmo que antes ... */ });
```

Atualizar o spec para importar `buildInstallmentPreview` do arquivo correto:
```typescript
import { buildInstallmentPreview } from './transaction-form';
// Remover a definição local
```

- [ ] **Step 4: Executar testes**

```bash
cd frontend && npm test -- --run transaction-form.spec 2>&1 | tail -10
```

Saída esperada: 4 testes passando.

- [ ] **Step 5: Atualizar transaction-form.html**

No template, adicionar seção de parcelamento após o campo `status` e antes do `categoryId`. Localizar a linha que contém o campo `totalInstallments` e substituir toda essa área por:

```html
<!-- Toggle parcelamento — só visível no modo criação -->
@if (!isEditMode() && mode() === 'TRANSACTION') {
  <div class="installment-toggle">
    <mat-slide-toggle [checked]="isInstallment()" (change)="isInstallment.set($event.checked)">
      É uma compra parcelada?
    </mat-slide-toggle>
  </div>

  @if (isInstallment()) {
    <div class="installment-section">

      <mat-button-toggle-group [value]="valueMode()" (change)="valueMode.set($event.value)" class="value-mode-toggle">
        <mat-button-toggle value="total">Valor total</mat-button-toggle>
        <mat-button-toggle value="per-installment">Valor da parcela</mat-button-toggle>
      </mat-button-toggle-group>

      <mat-form-field appearance="outline">
        <mat-label>Número de parcelas</mat-label>
        <input matInput type="number" formControlName="totalInstallments" min="2" max="48">
      </mat-form-field>

      @if (installmentPreview().length > 0) {
        <div class="installment-preview">
          <p class="preview-label">Prévia das parcelas</p>
          <table class="preview-table">
            <thead>
              <tr><th>Parcela</th><th>Data</th><th>Valor</th></tr>
            </thead>
            <tbody>
              @for (row of installmentPreview(); track row.number) {
                <tr>
                  <td>{{ row.number }}/{{ installmentPreview().length }}</td>
                  <td>{{ row.date }}</td>
                  <td>{{ row.amount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  }
}

<!-- Seção de propagação — só visível no modo edição de parcela -->
@if (isEditMode()) {
  <mat-divider></mat-divider>
  <div class="propagate-section">
    <p class="propagate-label">Propagar alterações para parcelas futuras pendentes:</p>
    <mat-checkbox [checked]="isPropagating('description')" (change)="togglePropagate('description')">Descrição</mat-checkbox>
    <mat-checkbox [checked]="isPropagating('categoryId')" (change)="togglePropagate('categoryId')">Categoria</mat-checkbox>
    <mat-checkbox [checked]="isPropagating('accountId')" (change)="togglePropagate('accountId')">Conta</mat-checkbox>
    <mat-checkbox [checked]="isPropagating('amount')" (change)="togglePropagate('amount')">
      Valor <small>(apenas parcelas pendentes)</small>
    </mat-checkbox>
    <mat-checkbox [checked]="isPropagating('status')" (change)="togglePropagate('status')">
      Status <small>(apenas parcelas pendentes)</small>
    </mat-checkbox>
  </div>
}
```

- [ ] **Step 6: Adicionar estilos ao transaction-form.scss**

Adicionar ao final:

```scss
.installment-toggle {
  margin: 8px 0 16px;
}

.installment-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  background: var(--mat-sys-surface-container-low, #f5f5f5);
  border-radius: 8px;
  margin-bottom: 16px;
}

.value-mode-toggle {
  width: 100%;
  mat-button-toggle { flex: 1; }
}

.installment-preview {
  .preview-label {
    font-size: 0.85rem;
    font-weight: 600;
    margin-bottom: 8px;
    color: rgba(0, 0, 0, 0.7);
  }

  .preview-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.85rem;

    th, td {
      padding: 4px 8px;
      text-align: left;
      border-bottom: 1px solid rgba(0, 0, 0, 0.08);
    }

    th { font-weight: 600; }
    tr:last-child td { border-bottom: none; }
  }
}

.propagate-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 0;

  .propagate-label {
    font-size: 0.85rem;
    font-weight: 600;
    color: rgba(0, 0, 0, 0.7);
    margin-bottom: 4px;
  }

  small { color: rgba(0, 0, 0, 0.5); margin-left: 4px; }
}
```

- [ ] **Step 7: Build final**

```bash
cd frontend && npm run build -- --configuration development 2>&1 | tail -10
```

Saída esperada: BUILD SUCCESS sem erros.

- [ ] **Step 8: Executar todos os testes**

```bash
cd frontend && npm test -- --run 2>&1 | tail -15
```

Saída esperada: todos os testes passando.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-form/
git commit -m "feat(frontend): formulário com toggle de parcelamento, preview live e propagação na edição"
```

---

## Task 12: Teste de integração — subir backend e verificar endpoints

- [ ] **Step 1: Subir infraestrutura e backend**

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run &
```

Aguardar: `Started FintechApiApplication` nos logs.

- [ ] **Step 2: Verificar health**

```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

Saída esperada: `"status": "UP"`.

- [ ] **Step 3: Testar criação com parcelamento**

```bash
# Obter token primeiro
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@fintech.com","password":"senha123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

# Listar contas para pegar um accountId
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/accounts | python3 -m json.tool | head -20
```

- [ ] **Step 4: Criar transação parcelada e verificar grupo**

```bash
# Substituir <ACCOUNT_ID> pelo ID obtido no passo anterior
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "description": "Notebook Teste",
    "amount": 3000.00,
    "date": "2026-06-01",
    "type": "EXPENSE",
    "totalInstallments": 3,
    "accountId": "<ACCOUNT_ID>"
  }' | python3 -m json.tool
```

Saída esperada: array com 3 transações, cada uma com `installmentGroupId` preenchido.

- [ ] **Step 5: Verificar listagem de grupos**

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/installment-groups | python3 -m json.tool
```

Saída esperada: array com 1 grupo, `paidInstallments: 0`, `pendingInstallments: 3`.

- [ ] **Step 6: Executar todos os testes backend**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -15
```

Saída esperada: BUILD SUCCESS, todos os testes passando.

- [ ] **Step 7: Commit final do plano**

```bash
git add .
git commit -m "test(integration): validação manual dos endpoints de InstallmentGroup"
```

---

## Checklist de cobertura da spec

- [x] **Modelo de dados** → Tasks 1, 2, 3
- [x] **DTOs backend** → Task 4
- [x] **TransactionService.create() cria grupo** → Task 5
- [x] **DELETE com scope + proteção de PAID** → Task 6
- [x] **UPDATE com propagate** → Task 6
- [x] **InstallmentGroupService (list/get/delete/patch)** → Task 7
- [x] **OpenAPI spec + geração de clientes** → Task 8
- [x] **Frontend listagem colapsável** → Task 9
- [x] **Frontend diálogo de exclusão** → Task 10
- [x] **Frontend formulário de criação (toggle + preview)** → Task 11
- [x] **Frontend propagação na edição** → Task 11
