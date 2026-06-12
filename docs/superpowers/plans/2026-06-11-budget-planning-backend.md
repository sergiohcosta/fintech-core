# Planejamento Mensal — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar o backend completo de planejamento mensal (budget cycles) conforme spec `docs/superpowers/specs/2026-06-11-monthly-planning-design.md`.

**Architecture:** Migration V12 cria 4 tabelas (budget_cycles, budget_items, recurring_budget_items + coluna em tenants). Services em `BudgetCycleService` e `BudgetItemService` encapsulam toda a lógica; controllers são finos e implementam interfaces geradas pelo OpenAPI Generator.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, Flyway, JUnit 5 + Mockito, OpenAPI Generator (backend), spec-first approach.

---

## Mapa de Arquivos

**Criar:**
- `backend/src/main/resources/db/migration/V12__budget_planning.sql`
- `backend/src/main/java/com/fintech/api/domain/enums/BudgetCycleStatus.java`
- `backend/src/main/java/com/fintech/api/domain/enums/BudgetItemSource.java`
- `backend/src/main/java/com/fintech/api/domain/enums/BudgetItemStatus.java`
- `backend/src/main/java/com/fintech/api/domain/budget/BudgetCycle.java`
- `backend/src/main/java/com/fintech/api/domain/budget/RecurringBudgetItem.java`
- `backend/src/main/java/com/fintech/api/domain/budget/BudgetItem.java`
- `backend/src/main/java/com/fintech/api/repository/BudgetCycleRepository.java`
- `backend/src/main/java/com/fintech/api/repository/BudgetItemRepository.java`
- `backend/src/main/java/com/fintech/api/repository/RecurringBudgetItemRepository.java`
- `backend/src/main/java/com/fintech/api/service/BudgetCycleService.java`
- `backend/src/main/java/com/fintech/api/service/BudgetItemService.java`
- `backend/src/main/java/com/fintech/api/service/RecurringBudgetItemService.java`
- `backend/src/main/java/com/fintech/api/dto/budget/BudgetCycleOpenRequest.java`
- `backend/src/main/java/com/fintech/api/dto/budget/BudgetCycleSummaryDTO.java`
- `backend/src/main/java/com/fintech/api/dto/budget/BudgetItemResponseDTO.java`
- `backend/src/main/java/com/fintech/api/dto/budget/BudgetCycleResponseDTO.java`
- `backend/src/main/java/com/fintech/api/dto/budget/BudgetItemCreateRequest.java`
- `backend/src/main/java/com/fintech/api/dto/budget/BudgetItemUpdateRequest.java`
- `backend/src/main/java/com/fintech/api/dto/budget/BudgetItemLinkRequest.java`
- `backend/src/main/java/com/fintech/api/dto/budget/RecurringBudgetItemRequest.java`
- `backend/src/main/java/com/fintech/api/dto/budget/RecurringBudgetItemResponseDTO.java`
- `backend/src/main/java/com/fintech/api/dto/budget/TenantSettingsPatchRequest.java`
- `backend/src/main/java/com/fintech/api/controller/BudgetCycleController.java`
- `backend/src/main/java/com/fintech/api/controller/BudgetItemController.java`
- `backend/src/main/java/com/fintech/api/controller/RecurringBudgetItemController.java`
- `backend/src/main/java/com/fintech/api/controller/TenantController.java`
- `backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java`
- `backend/src/test/java/com/fintech/api/service/BudgetItemServiceTest.java`

**Modificar:**
- `backend/src/main/java/com/fintech/api/domain/tenant/Tenant.java` — add `budgetCycleStartDay`
- `backend/src/main/java/com/fintech/api/repository/AccountRepository.java` — add `sumLiquidBalanceByTenant`
- `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java` — add `findInstallmentsInPeriodByTenant`
- `backend/src/main/resources/static/openapi.yaml` — add budget schemas + paths
- `backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java` — add PATCH ao CORS

---

## Task 1: Migration V12 + campo budgetCycleStartDay em Tenant

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__budget_planning.sql`
- Modify: `backend/src/main/java/com/fintech/api/domain/tenant/Tenant.java`

- [ ] **Passo 1: Criar o arquivo de migration**

```sql
-- backend/src/main/resources/db/migration/V12__budget_planning.sql

ALTER TABLE tenants ADD COLUMN budget_cycle_start_day INT NOT NULL DEFAULT 1;

CREATE TABLE budget_cycles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    opening_balance NUMERIC(19,2) NOT NULL DEFAULT 0,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_cycle_status CHECK (status IN ('OPEN', 'CLOSED'))
);

CREATE UNIQUE INDEX uq_tenant_one_open_cycle
    ON budget_cycles(tenant_id)
    WHERE status = 'OPEN';

CREATE TABLE recurring_budget_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    description VARCHAR(255) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    type VARCHAR(10) NOT NULL,
    category_id UUID REFERENCES categories(id),
    account_id UUID REFERENCES accounts(id),
    day_of_month INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_recurring_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT chk_recurring_day CHECK (day_of_month BETWEEN 1 AND 28)
);

CREATE TABLE budget_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id UUID NOT NULL REFERENCES budget_cycles(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    description VARCHAR(255) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    type VARCHAR(10) NOT NULL,
    category_id UUID REFERENCES categories(id),
    account_id UUID REFERENCES accounts(id),
    expected_date DATE NOT NULL,
    source VARCHAR(15) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    recurring_item_id UUID REFERENCES recurring_budget_items(id),
    transaction_id UUID REFERENCES transactions(id),
    installment_group_id UUID REFERENCES installment_groups(id),
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_item_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT chk_item_source CHECK (source IN ('MANUAL', 'RECURRING', 'INSTALLMENT')),
    CONSTRAINT chk_item_status CHECK (status IN ('PENDING', 'REALIZED', 'SKIPPED'))
);
```

- [ ] **Passo 2: Adicionar `budgetCycleStartDay` em Tenant.java**

Adicionar o campo após `document`:

```java
@Column(nullable = false)
private int budgetCycleStartDay = 1;
```

O arquivo completo fica:

```java
package com.fintech.api.domain.tenant;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String document;

    @Column(nullable = false)
    private int budgetCycleStartDay = 1;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Passo 3: Verificar que o backend sobe com a migration aplicada**

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Verificar em `http://localhost:8080/actuator/health` que retorna `{"status":"UP"}`.
Parar com `Ctrl+C`.

- [ ] **Passo 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V12__budget_planning.sql \
        backend/src/main/java/com/fintech/api/domain/tenant/Tenant.java
git commit -m "feat(planning): adiciona migration V12 e campo budgetCycleStartDay em Tenant"
```

---

## Task 2: Enums e Entidades JPA

**Files:**
- Create: 3 enums + 3 entidades em `domain/budget/`

- [ ] **Passo 1: Criar os 3 enums**

```java
// backend/src/main/java/com/fintech/api/domain/enums/BudgetCycleStatus.java
package com.fintech.api.domain.enums;
public enum BudgetCycleStatus { OPEN, CLOSED }
```

```java
// backend/src/main/java/com/fintech/api/domain/enums/BudgetItemSource.java
package com.fintech.api.domain.enums;
public enum BudgetItemSource { MANUAL, RECURRING, INSTALLMENT }
```

```java
// backend/src/main/java/com/fintech/api/domain/enums/BudgetItemStatus.java
package com.fintech.api.domain.enums;
public enum BudgetItemStatus { PENDING, REALIZED, SKIPPED }
```

- [ ] **Passo 2: Criar BudgetCycle.java**

```java
package com.fintech.api.domain.budget;

import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "budget_cycles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BudgetCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude
    private Tenant tenant;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private BudgetCycleStatus status = BudgetCycleStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Passo 3: Criar RecurringBudgetItem.java**

```java
package com.fintech.api.domain.budget;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "recurring_budget_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RecurringBudgetItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude
    private Tenant tenant;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @ToString.Exclude
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    @ToString.Exclude
    private Account account;

    @Column(nullable = false)
    private int dayOfMonth;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Passo 4: Criar BudgetItem.java**

```java
package com.fintech.api.domain.budget;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "budget_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BudgetItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", nullable = false)
    @ToString.Exclude
    private BudgetCycle cycle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude
    private Tenant tenant;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @ToString.Exclude
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    @ToString.Exclude
    private Account account;

    @Column(nullable = false)
    private LocalDate expectedDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private BudgetItemSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private BudgetItemStatus status = BudgetItemStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recurring_item_id")
    @ToString.Exclude
    private RecurringBudgetItem recurringItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    @ToString.Exclude
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installment_group_id")
    @ToString.Exclude
    private InstallmentGroup installmentGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Passo 5: Compilar para garantir que as entidades estão corretas**

```bash
cd backend && ./mvnw compile -q
```

Esperado: `BUILD SUCCESS` sem erros.

- [ ] **Passo 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/domain/enums/BudgetCycleStatus.java \
        backend/src/main/java/com/fintech/api/domain/enums/BudgetItemSource.java \
        backend/src/main/java/com/fintech/api/domain/enums/BudgetItemStatus.java \
        backend/src/main/java/com/fintech/api/domain/budget/
git commit -m "feat(planning): adiciona entidades JPA BudgetCycle, RecurringBudgetItem e BudgetItem"
```

---

## Task 3: Repositórios + Queries Auxiliares

**Files:**
- Create: 3 repositories
- Modify: `AccountRepository.java`, `TransactionRepository.java`

- [ ] **Passo 1: Criar BudgetCycleRepository.java**

```java
package com.fintech.api.repository;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface BudgetCycleRepository extends JpaRepository<BudgetCycle, UUID> {

    Optional<BudgetCycle> findByTenantAndStatus(Tenant tenant, BudgetCycleStatus status);

    Page<BudgetCycle> findAllByTenantOrderByStartDateDesc(Tenant tenant, Pageable pageable);

    // Detecta sobreposição de período com ciclos existentes
    @Query("""
        SELECT COUNT(c) > 0
        FROM BudgetCycle c
        WHERE c.tenant = :tenant
          AND c.startDate <= :endDate
          AND c.endDate >= :startDate
    """)
    boolean existsOverlap(
        @Param("tenant") Tenant tenant,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );
}
```

- [ ] **Passo 2: Criar RecurringBudgetItemRepository.java**

```java
package com.fintech.api.repository;

import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurringBudgetItemRepository extends JpaRepository<RecurringBudgetItem, UUID> {

    List<RecurringBudgetItem> findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(Tenant tenant);

    Optional<RecurringBudgetItem> findByIdAndTenant(UUID id, Tenant tenant);
}
```

- [ ] **Passo 3: Criar BudgetItemRepository.java**

```java
package com.fintech.api.repository;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetItemRepository extends JpaRepository<BudgetItem, UUID> {

    @Query("""
        SELECT i FROM BudgetItem i
        LEFT JOIN FETCH i.category
        LEFT JOIN FETCH i.account
        WHERE i.cycle = :cycle
        ORDER BY i.expectedDate ASC, i.type ASC
    """)
    List<BudgetItem> findAllByCycleWithDetails(@Param("cycle") BudgetCycle cycle);

    // Usado para verificar se uma transação já está vinculada a outro item
    Optional<BudgetItem> findByTransactionAndCycleNot(Transaction transaction, BudgetCycle cycle);

    boolean existsByCycleAndSource(BudgetCycle cycle, BudgetItemSource source);
}
```

- [ ] **Passo 4: Adicionar `sumLiquidBalanceByTenant` em AccountRepository.java**

Adicionar ao final da interface:

```java
@Query("""
    SELECT COALESCE(SUM(
        CASE WHEN t.type = :incomeType THEN t.amount ELSE -t.amount END
    ), 0)
    FROM Transaction t
    WHERE t.account.tenant.id = :tenantId
      AND t.account.countInLiquidBalance = true
      AND t.account.active = true
      AND t.status <> :cancelledStatus
""")
BigDecimal sumLiquidBalanceByTenant(
    @Param("tenantId") UUID tenantId,
    @Param("incomeType") TransactionType incomeType,
    @Param("cancelledStatus") TransactionStatus cancelledStatus
);
```

Adicionar os imports necessários no topo:
```java
import com.fintech.api.domain.enums.TransactionType;
// TransactionStatus já estava no import
```

- [ ] **Passo 5: Adicionar `findInstallmentsInPeriodByTenant` em TransactionRepository.java**

Adicionar ao final da interface:

```java
// Retorna transações parceladas de cartão cujas faturas vencem no período — usado pela abertura de ciclo
@Query("""
    SELECT t FROM Transaction t
    LEFT JOIN FETCH t.installmentGroup ig
    LEFT JOIN FETCH t.invoice inv
    WHERE t.account.tenant.id = :tenantId
      AND t.installmentGroup IS NOT NULL
      AND inv IS NOT NULL
      AND inv.dueDate BETWEEN :startDate AND :endDate
      AND t.status <> :cancelledStatus
    ORDER BY ig.id, inv.dueDate
""")
List<Transaction> findInstallmentsInPeriodByTenant(
    @Param("tenantId") UUID tenantId,
    @Param("startDate") LocalDate startDate,
    @Param("endDate") LocalDate endDate,
    @Param("cancelledStatus") TransactionStatus cancelledStatus
);
```

Adicionar import `java.time.LocalDate` se ausente.

- [ ] **Passo 6: Compilar**

```bash
cd backend && ./mvnw compile -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Passo 7: Commit**

```bash
git add backend/src/main/java/com/fintech/api/repository/
git commit -m "feat(planning): adiciona repositórios de budget e queries auxiliares em Account/TransactionRepository"
```

---

## Task 4: BudgetCycleService — Lógica de Datas (TDD)

Esta task cobre apenas o cálculo de datas — a lógica mais crítica do serviço, isolada para teste unitário puro.

**Files:**
- Create: `BudgetCycleService.java` (esqueleto inicial com `calculateCycleDates`)
- Create: `BudgetCycleServiceTest.java`

- [ ] **Passo 1: Escrever o teste antes do código**

```java
// backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java
package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.BudgetCycleRepository;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.RecurringBudgetItemRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetCycleServiceTest {

    @Mock BudgetCycleRepository cycleRepository;
    @Mock BudgetItemRepository itemRepository;
    @Mock RecurringBudgetItemRepository recurringRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;

    @InjectMocks BudgetCycleService service;

    // ---- calculateCycleDates ----

    @Test
    @DisplayName("startDay=1 → ciclo calendário (1º ao último dia do mês)")
    void calculateCycleDates_startDayOne_calendarioCiclo() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 6), 1);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    @DisplayName("startDay=11, junho → 11/mai a 10/jun")
    void calculateCycleDates_startDay11_abrangeDoiMeses() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 6), 11);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 5, 11));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 6, 10));
    }

    @Test
    @DisplayName("startDay=28 → 28/mai a 27/jun")
    void calculateCycleDates_startDay28() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 6), 28);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 5, 28));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 6, 27));
    }

    @Test
    @DisplayName("startDay=11, janeiro → 11/dez do ano anterior a 10/jan")
    void calculateCycleDates_viradaDeAno() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 1), 11);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2025, 12, 11));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    @DisplayName("startDay=1, fevereiro → respeita comprimento do mês (28 dias em 2026)")
    void calculateCycleDates_fevereiro_semAnosBissextos() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 2), 1);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    // ---- open() — validações ----

    @Test
    @DisplayName("open() lança ConflictException se já existe ciclo OPEN para o tenant")
    void open_jáExisteCicloAberto_lançaConflict() {
        Tenant tenant = tenantWith(1);
        User user = new User();

        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.of(new BudgetCycle()));

        assertThatThrownBy(() -> service.open(tenant, user, "2026-06"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ciclo aberto");
    }

    @Test
    @DisplayName("open() lança ConflictException se período sobrepõe ciclo existente")
    void open_periodoSobrepos_lançaConflict() {
        Tenant tenant = tenantWith(1);
        User user = new User();

        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.empty());
        when(cycleRepository.existsOverlap(eq(tenant), any(), any()))
            .thenReturn(true);

        assertThatThrownBy(() -> service.open(tenant, user, "2026-06"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("conflita");
    }

    @Test
    @DisplayName("open() calcula openingBalance somando saldos líquidos das contas")
    void open_calculaOpeningBalanceCorreto() {
        Tenant tenant = tenantWith(1);
        User user = new User();

        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.empty());
        when(cycleRepository.existsOverlap(any(), any(), any()))
            .thenReturn(false);
        when(accountRepository.sumLiquidBalanceByTenant(
                eq(tenant.getId()), eq(TransactionType.INCOME), eq(TransactionStatus.CANCELLED)))
            .thenReturn(new BigDecimal("3200.00"));
        when(recurringRepository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(tenant))
            .thenReturn(List.of());
        when(transactionRepository.findInstallmentsInPeriodByTenant(any(), any(), any(), any()))
            .thenReturn(List.of());

        var captor = org.mockito.ArgumentCaptor.forClass(BudgetCycle.class);
        when(cycleRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.open(tenant, user, "2026-06");

        assertThat(captor.getValue().getOpeningBalance()).isEqualByComparingTo("3200.00");
    }

    // ---- calculateExpectedDate ----

    @Test
    @DisplayName("dayOfMonth >= startDay → data cai no 1º mês calendário do ciclo")
    void calculateExpectedDate_dayGeStartDay_primeiraMes() {
        // Ciclo startDate = 2026-05-11, startDay = 11
        LocalDate startDate = LocalDate.of(2026, 5, 11);
        LocalDate result = service.calculateExpectedDate(startDate, 11, 15); // day 15 >= 11
        assertThat(result).isEqualTo(LocalDate.of(2026, 5, 15));
    }

    @Test
    @DisplayName("dayOfMonth < startDay → data cai no 2º mês calendário do ciclo")
    void calculateExpectedDate_dayLtStartDay_segundoMes() {
        LocalDate startDate = LocalDate.of(2026, 5, 11);
        LocalDate result = service.calculateExpectedDate(startDate, 11, 5); // day 5 < 11
        assertThat(result).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    // helper
    private Tenant tenantWith(int startDay) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setBudgetCycleStartDay(startDay);
        return t;
    }
}
```

- [ ] **Passo 2: Rodar o teste (deve falhar — classe não existe ainda)**

```bash
cd backend && ./mvnw test -pl . -Dtest=BudgetCycleServiceTest -q 2>&1 | tail -5
```

Esperado: erro de compilação (`BudgetCycleService not found`).

- [ ] **Passo 3: Criar BudgetCycleService.java com a implementação mínima**

```java
package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.enums.*;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetCycleService {

    private final BudgetCycleRepository cycleRepository;
    private final BudgetItemRepository itemRepository;
    private final RecurringBudgetItemRepository recurringRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    // Visível para testes — não é API pública mas facilita teste unitário
    LocalDate[] calculateCycleDates(YearMonth referenceMonth, int startDay) {
        if (startDay == 1) {
            return new LocalDate[]{
                referenceMonth.atDay(1),
                referenceMonth.atEndOfMonth()
            };
        }
        LocalDate start = referenceMonth.minusMonths(1).atDay(startDay);
        LocalDate end   = referenceMonth.atDay(startDay - 1);
        return new LocalDate[]{start, end};
    }

    // Visível para testes
    LocalDate calculateExpectedDate(LocalDate cycleStartDate, int startDay, int dayOfMonth) {
        if (dayOfMonth >= startDay) {
            return cycleStartDate.withDayOfMonth(dayOfMonth);
        }
        return cycleStartDate.plusMonths(1).withDayOfMonth(dayOfMonth);
    }

    @Transactional
    public BudgetCycle open(Tenant tenant, User user, String referenceMonth) {
        if (cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN).isPresent()) {
            throw new IllegalStateException("Já existe um ciclo aberto para este tenant.");
        }

        int startDay = tenant.getBudgetCycleStartDay();
        LocalDate[] dates = calculateCycleDates(YearMonth.parse(referenceMonth), startDay);
        LocalDate startDate = dates[0];
        LocalDate endDate   = dates[1];

        if (cycleRepository.existsOverlap(tenant, startDate, endDate)) {
            throw new IllegalStateException("O período solicitado conflita com um ciclo já existente.");
        }

        BigDecimal opening = accountRepository.sumLiquidBalanceByTenant(
            tenant.getId(), TransactionType.INCOME, TransactionStatus.CANCELLED);

        BudgetCycle cycle = cycleRepository.save(BudgetCycle.builder()
            .tenant(tenant)
            .startDate(startDate)
            .endDate(endDate)
            .openingBalance(opening)
            .status(BudgetCycleStatus.OPEN)
            .createdBy(user)
            .build());

        populateRecurringItems(cycle, tenant, user, startDate, startDay);
        populateInstallmentItems(cycle, tenant, startDate, endDate);

        log.info("Ciclo de planejamento aberto [cycleId={} tenantId={} periodo={}/{}]",
            cycle.getId(), tenant.getId(), startDate, endDate);
        return cycle;
    }

    private void populateRecurringItems(BudgetCycle cycle, Tenant tenant, User user,
                                        LocalDate startDate, int startDay) {
        List<RecurringBudgetItem> templates =
            recurringRepository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(tenant);

        List<BudgetItem> items = templates.stream()
            .map(t -> BudgetItem.builder()
                .cycle(cycle)
                .tenant(tenant)
                .description(t.getDescription())
                .amount(t.getAmount())
                .type(t.getType())
                .category(t.getCategory())
                .account(t.getAccount())
                .expectedDate(calculateExpectedDate(startDate, startDay, t.getDayOfMonth()))
                .source(BudgetItemSource.RECURRING)
                .recurringItem(t)
                .createdBy(user)
                .build())
            .toList();

        itemRepository.saveAll(items);
    }

    private void populateInstallmentItems(BudgetCycle cycle, Tenant tenant,
                                          LocalDate startDate, LocalDate endDate) {
        List<Transaction> installments = transactionRepository.findInstallmentsInPeriodByTenant(
            tenant.getId(), startDate, endDate, TransactionStatus.CANCELLED);

        // Agrupa por installmentGroup — um BudgetItem por grupo, soma os valores do período
        Map<InstallmentGroup, List<Transaction>> byGroup = installments.stream()
            .collect(Collectors.groupingBy(Transaction::getInstallmentGroup));

        List<BudgetItem> items = byGroup.entrySet().stream()
            .map(entry -> {
                InstallmentGroup group = entry.getKey();
                List<Transaction> txs = entry.getValue();
                BigDecimal total = txs.stream()
                    .map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                // expectedDate = dueDate da fatura da primeira parcela do grupo no período
                LocalDate dueDate = txs.get(0).getInvoice().getDueDate();

                return BudgetItem.builder()
                    .cycle(cycle)
                    .tenant(tenant)
                    .description(group.getDescription())
                    .amount(total)
                    .type(TransactionType.EXPENSE)
                    .account(group.getAccount())
                    .category(group.getCategory())
                    .expectedDate(dueDate)
                    .source(BudgetItemSource.INSTALLMENT)
                    .installmentGroup(group)
                    .build();
            })
            .toList();

        itemRepository.saveAll(items);
    }

    @Transactional
    public BudgetCycle close(UUID cycleId, Tenant tenant) {
        BudgetCycle cycle = findByIdAndTenant(cycleId, tenant);
        if (cycle.getStatus() != BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo já está fechado.");
        }
        cycle.setStatus(BudgetCycleStatus.CLOSED);
        log.info("Ciclo fechado [cycleId={} tenantId={}]", cycleId, tenant.getId());
        return cycleRepository.save(cycle);
    }

    @Transactional
    public BudgetCycle syncInstallments(UUID cycleId, Tenant tenant, User user) {
        BudgetCycle cycle = findByIdAndTenant(cycleId, tenant);
        // Remove itens de parcela existentes e repopula
        List<BudgetItem> existing = itemRepository.findAllByCycleWithDetails(cycle);
        List<BudgetItem> toRemove = existing.stream()
            .filter(i -> i.getSource() == BudgetItemSource.INSTALLMENT)
            .toList();
        itemRepository.deleteAll(toRemove);
        populateInstallmentItems(cycle, tenant, cycle.getStartDate(), cycle.getEndDate());
        return cycle;
    }

    @Transactional(readOnly = true)
    public Optional<BudgetCycle> findOpenByTenant(Tenant tenant) {
        return cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public BudgetCycle findByIdAndTenant(UUID id, Tenant tenant) {
        return cycleRepository.findById(id)
            .filter(c -> c.getTenant().getId().equals(tenant.getId()))
            .orElseThrow(() -> new com.fintech.api.exception.EntityNotFoundException(
                "Ciclo de planejamento não encontrado."));
    }

    @Transactional(readOnly = true)
    public Page<BudgetCycle> listByTenant(Tenant tenant, Pageable pageable) {
        return cycleRepository.findAllByTenantOrderByStartDateDesc(tenant, pageable);
    }

    @Transactional(readOnly = true)
    public List<BudgetItem> listItems(BudgetCycle cycle) {
        return itemRepository.findAllByCycleWithDetails(cycle);
    }
}
```

- [ ] **Passo 4: Rodar os testes**

```bash
cd backend && ./mvnw test -Dtest=BudgetCycleServiceTest -q 2>&1 | tail -10
```

Esperado: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Passo 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/BudgetCycleService.java \
        backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java
git commit -m "feat(planning): implementa BudgetCycleService com cálculo de datas e abertura de ciclo"
```

---

## Task 5: BudgetItemService (TDD)

**Files:**
- Create: `BudgetItemService.java`, `BudgetItemServiceTest.java`

- [ ] **Passo 1: Escrever os testes**

```java
// backend/src/test/java/com/fintech/api/service/BudgetItemServiceTest.java
package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetItemCreateRequest;
import com.fintech.api.dto.budget.BudgetItemUpdateRequest;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetItemServiceTest {

    @Mock BudgetItemRepository repository;
    @Mock TransactionRepository transactionRepository;

    @InjectMocks BudgetItemService service;

    @Test
    @DisplayName("update() em item INSTALLMENT lança IllegalStateException")
    void update_itemInstallment_lança422() {
        BudgetItem item = itemWith(BudgetItemSource.INSTALLMENT);

        assertThatThrownBy(() -> service.update(item,
            new BudgetItemUpdateRequest("desc", BigDecimal.TEN, LocalDate.now(), null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("parcela");
    }

    @Test
    @DisplayName("update() em item MANUAL atualiza campos e salva")
    void update_itemManual_atualizaCampos() {
        BudgetItem item = itemWith(BudgetItemSource.MANUAL);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.update(item,
            new BudgetItemUpdateRequest("Nova desc", new BigDecimal("500.00"),
                LocalDate.of(2026, 6, 15), null, null));

        assertThat(updated.getDescription()).isEqualTo("Nova desc");
        assertThat(updated.getAmount()).isEqualByComparingTo("500.00");
        assertThat(updated.getExpectedDate()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    @DisplayName("link() muda status para REALIZED e preenche transaction")
    void link_transacaoExistente_mudaStatusParaRealized() {
        BudgetCycle cycle = new BudgetCycle();
        BudgetItem item = itemWith(BudgetItemSource.MANUAL);
        item.setCycle(cycle);
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());

        when(transactionRepository.findById(tx.getId())).thenReturn(Optional.of(tx));
        when(repository.findByTransactionAndCycleNot(tx, cycle)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BudgetItem result = service.link(item, tx.getId());

        assertThat(result.getStatus()).isEqualTo(BudgetItemStatus.REALIZED);
        assertThat(result.getTransaction()).isEqualTo(tx);
    }

    @Test
    @DisplayName("link() lança IllegalStateException se transação já está vinculada a outro item")
    void link_transacaoJaVinculada_lança422() {
        BudgetCycle cycle = new BudgetCycle();
        BudgetItem item = itemWith(BudgetItemSource.MANUAL);
        item.setCycle(cycle);
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());

        when(transactionRepository.findById(tx.getId())).thenReturn(Optional.of(tx));
        when(repository.findByTransactionAndCycleNot(tx, cycle))
            .thenReturn(Optional.of(new BudgetItem())); // já vinculada

        assertThatThrownBy(() -> service.link(item, tx.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("vinculada");
    }

    @Test
    @DisplayName("unlink() volta status para PENDING e limpa transaction")
    void unlink_voltaParaPending() {
        BudgetItem item = itemWith(BudgetItemSource.MANUAL);
        item.setStatus(BudgetItemStatus.REALIZED);
        item.setTransaction(new Transaction());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BudgetItem result = service.unlink(item);

        assertThat(result.getStatus()).isEqualTo(BudgetItemStatus.PENDING);
        assertThat(result.getTransaction()).isNull();
    }

    private BudgetItem itemWith(BudgetItemSource source) {
        return BudgetItem.builder()
            .id(UUID.randomUUID())
            .description("Item teste")
            .amount(BigDecimal.TEN)
            .type(TransactionType.EXPENSE)
            .expectedDate(LocalDate.now())
            .source(source)
            .status(BudgetItemStatus.PENDING)
            .build();
    }
}
```

- [ ] **Passo 2: Criar BudgetItemService.java**

```java
package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetItemCreateRequest;
import com.fintech.api.dto.budget.BudgetItemUpdateRequest;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BudgetItemService {

    private final BudgetItemRepository repository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public BudgetItem create(BudgetCycle cycle, BudgetItemCreateRequest req, Tenant tenant, User user) {
        return repository.save(BudgetItem.builder()
            .cycle(cycle)
            .tenant(tenant)
            .description(req.description())
            .amount(req.amount())
            .type(req.type())
            .expectedDate(req.expectedDate())
            .source(BudgetItemSource.MANUAL)
            .createdBy(user)
            .build());
    }

    @Transactional
    public BudgetItem update(BudgetItem item, BudgetItemUpdateRequest req) {
        if (item.getSource() == BudgetItemSource.INSTALLMENT) {
            throw new IllegalStateException("Itens de parcela não podem ser editados manualmente.");
        }
        item.setDescription(req.description());
        item.setAmount(req.amount());
        item.setExpectedDate(req.expectedDate());
        return repository.save(item);
    }

    @Transactional
    public BudgetItem link(BudgetItem item, UUID transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada."));

        if (repository.findByTransactionAndCycleNot(tx, item.getCycle()).isPresent()) {
            throw new IllegalStateException(
                "Esta transação já está vinculada a outro item do plano.");
        }

        item.setTransaction(tx);
        item.setStatus(BudgetItemStatus.REALIZED);
        return repository.save(item);
    }

    @Transactional
    public BudgetItem unlink(BudgetItem item) {
        item.setTransaction(null);
        item.setStatus(BudgetItemStatus.PENDING);
        return repository.save(item);
    }

    @Transactional
    public void delete(BudgetItem item) {
        repository.delete(item);
    }

    @Transactional(readOnly = true)
    public BudgetItem findByIdAndTenant(UUID id, Tenant tenant) {
        return repository.findById(id)
            .filter(i -> i.getTenant().getId().equals(tenant.getId()))
            .orElseThrow(() -> new EntityNotFoundException("Item de planejamento não encontrado."));
    }
}
```

- [ ] **Passo 3: Criar os DTOs de request que os testes referenciam**

```java
// backend/src/main/java/com/fintech/api/dto/budget/BudgetItemCreateRequest.java
package com.fintech.api.dto.budget;

import com.fintech.api.domain.enums.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BudgetItemCreateRequest(
    @NotBlank String description,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    @NotNull TransactionType type,
    @NotNull LocalDate expectedDate,
    UUID categoryId,
    UUID accountId
) {}
```

```java
// backend/src/main/java/com/fintech/api/dto/budget/BudgetItemUpdateRequest.java
package com.fintech.api.dto.budget;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BudgetItemUpdateRequest(
    @NotBlank String description,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    @NotNull LocalDate expectedDate,
    UUID categoryId,
    UUID accountId
) {}
```

```java
// backend/src/main/java/com/fintech/api/dto/budget/BudgetItemLinkRequest.java
package com.fintech.api.dto.budget;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record BudgetItemLinkRequest(@NotNull UUID transactionId) {}
```

- [ ] **Passo 4: Rodar os testes**

```bash
cd backend && ./mvnw test -Dtest=BudgetItemServiceTest -q 2>&1 | tail -10
```

Esperado: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Passo 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/BudgetItemService.java \
        backend/src/main/java/com/fintech/api/dto/budget/ \
        backend/src/test/java/com/fintech/api/service/BudgetItemServiceTest.java
git commit -m "feat(planning): implementa BudgetItemService com link/unlink e guard de parcelas"
```

---

## Task 6: RecurringBudgetItemService + DTOs restantes

**Files:**
- Create: `RecurringBudgetItemService.java`, DTOs restantes

- [ ] **Passo 1: Criar RecurringBudgetItemService.java**

```java
package com.fintech.api.service;

import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.RecurringBudgetItemRequest;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.RecurringBudgetItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecurringBudgetItemService {

    private final RecurringBudgetItemRepository repository;

    @Transactional(readOnly = true)
    public List<RecurringBudgetItem> listActive(Tenant tenant) {
        return repository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(tenant);
    }

    @Transactional
    public RecurringBudgetItem create(RecurringBudgetItemRequest req, Tenant tenant, User user) {
        return repository.save(RecurringBudgetItem.builder()
            .tenant(tenant)
            .description(req.description())
            .amount(req.amount())
            .type(req.type())
            .dayOfMonth(req.dayOfMonth())
            .createdBy(user)
            .build());
    }

    @Transactional
    public RecurringBudgetItem update(UUID id, RecurringBudgetItemRequest req, Tenant tenant) {
        RecurringBudgetItem item = findByIdAndTenant(id, tenant);
        item.setDescription(req.description());
        item.setAmount(req.amount());
        item.setType(req.type());
        item.setDayOfMonth(req.dayOfMonth());
        return repository.save(item);
    }

    @Transactional
    public void deactivate(UUID id, Tenant tenant) {
        RecurringBudgetItem item = findByIdAndTenant(id, tenant);
        item.setActive(false);
        repository.save(item);
    }

    @Transactional(readOnly = true)
    public RecurringBudgetItem findByIdAndTenant(UUID id, Tenant tenant) {
        return repository.findByIdAndTenant(id, tenant)
            .orElseThrow(() -> new EntityNotFoundException("Template recorrente não encontrado."));
    }
}
```

- [ ] **Passo 2: Criar RecurringBudgetItemRequest.java**

```java
package com.fintech.api.dto.budget;

import com.fintech.api.domain.enums.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record RecurringBudgetItemRequest(
    @NotBlank String description,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    @NotNull TransactionType type,
    @NotNull @Min(1) @Max(28) Integer dayOfMonth,
    UUID categoryId,
    UUID accountId
) {}
```

- [ ] **Passo 3: Criar RecurringBudgetItemResponseDTO.java**

```java
package com.fintech.api.dto.budget;

import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.enums.TransactionType;
import java.math.BigDecimal;
import java.util.UUID;

public record RecurringBudgetItemResponseDTO(
    UUID id,
    String description,
    BigDecimal amount,
    TransactionType type,
    int dayOfMonth,
    UUID categoryId,
    String categoryName,
    UUID accountId,
    String accountName,
    boolean active
) {
    public static RecurringBudgetItemResponseDTO fromEntity(RecurringBudgetItem item) {
        return new RecurringBudgetItemResponseDTO(
            item.getId(),
            item.getDescription(),
            item.getAmount(),
            item.getType(),
            item.getDayOfMonth(),
            item.getCategory() != null ? item.getCategory().getId() : null,
            item.getCategory() != null ? item.getCategory().getName() : null,
            item.getAccount() != null ? item.getAccount().getId() : null,
            item.getAccount() != null ? item.getAccount().getName() : null,
            item.isActive()
        );
    }
}
```

- [ ] **Passo 4: Criar BudgetItemResponseDTO.java**

```java
package com.fintech.api.dto.budget;

import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BudgetItemResponseDTO(
    UUID id,
    String description,
    BigDecimal amount,
    TransactionType type,
    LocalDate expectedDate,
    BudgetItemSource source,
    BudgetItemStatus status,
    UUID categoryId,
    String categoryName,
    UUID accountId,
    String accountName,
    UUID transactionId,
    UUID installmentGroupId
) {
    public static BudgetItemResponseDTO fromEntity(BudgetItem item) {
        return new BudgetItemResponseDTO(
            item.getId(),
            item.getDescription(),
            item.getAmount(),
            item.getType(),
            item.getExpectedDate(),
            item.getSource(),
            item.getStatus(),
            item.getCategory() != null ? item.getCategory().getId() : null,
            item.getCategory() != null ? item.getCategory().getName() : null,
            item.getAccount() != null ? item.getAccount().getId() : null,
            item.getAccount() != null ? item.getAccount().getName() : null,
            item.getTransaction() != null ? item.getTransaction().getId() : null,
            item.getInstallmentGroup() != null ? item.getInstallmentGroup().getId() : null
        );
    }
}
```

- [ ] **Passo 5: Criar BudgetCycleSummaryDTO.java e BudgetCycleResponseDTO.java**

```java
package com.fintech.api.dto.budget;

import java.math.BigDecimal;

public record BudgetCycleSummaryDTO(
    BigDecimal plannedIncome,
    BigDecimal plannedExpense,
    BigDecimal projectedBalance,
    BigDecimal realizedIncome,
    BigDecimal realizedExpense,
    BigDecimal currentBalance,
    long pendingCount
) {}
```

```java
package com.fintech.api.dto.budget;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BudgetCycleResponseDTO(
    UUID id,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal openingBalance,
    BudgetCycleStatus status,
    BudgetCycleSummaryDTO summary,
    List<BudgetItemResponseDTO> items
) {
    public static BudgetCycleResponseDTO fromEntity(BudgetCycle cycle, List<BudgetItem> items) {
        List<BudgetItemResponseDTO> itemDTOs = items.stream()
            .map(BudgetItemResponseDTO::fromEntity)
            .toList();

        BudgetCycleSummaryDTO summary = buildSummary(items, cycle.getOpeningBalance());

        return new BudgetCycleResponseDTO(
            cycle.getId(),
            cycle.getStartDate(),
            cycle.getEndDate(),
            cycle.getOpeningBalance(),
            cycle.getStatus(),
            summary,
            itemDTOs
        );
    }

    private static BudgetCycleSummaryDTO buildSummary(List<BudgetItem> items, BigDecimal openingBalance) {
        BigDecimal plannedIncome   = BigDecimal.ZERO;
        BigDecimal plannedExpense  = BigDecimal.ZERO;
        BigDecimal realizedIncome  = BigDecimal.ZERO;
        BigDecimal realizedExpense = BigDecimal.ZERO;
        long pendingCount = 0;

        for (BudgetItem item : items) {
            boolean isIncome = item.getType() == TransactionType.INCOME;
            boolean realized = item.getStatus() == BudgetItemStatus.REALIZED;

            if (isIncome) plannedIncome   = plannedIncome.add(item.getAmount());
            else          plannedExpense  = plannedExpense.add(item.getAmount());

            if (realized) {
                if (isIncome) realizedIncome  = realizedIncome.add(item.getAmount());
                else          realizedExpense = realizedExpense.add(item.getAmount());
            }

            if (item.getStatus() == BudgetItemStatus.PENDING) pendingCount++;
        }

        BigDecimal projectedBalance = openingBalance.add(plannedIncome).subtract(plannedExpense);
        BigDecimal currentBalance   = openingBalance.add(realizedIncome).subtract(realizedExpense);

        return new BudgetCycleSummaryDTO(
            plannedIncome, plannedExpense, projectedBalance,
            realizedIncome, realizedExpense, currentBalance, pendingCount
        );
    }
}
```

- [ ] **Passo 6: Criar BudgetCycleOpenRequest.java e TenantSettingsPatchRequest.java**

```java
package com.fintech.api.dto.budget;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record BudgetCycleOpenRequest(
    @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}", message = "Formato esperado: yyyy-MM")
    String referenceMonth
) {}
```

```java
package com.fintech.api.dto.budget;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TenantSettingsPatchRequest(
    @NotNull @Min(1) @Max(28) Integer budgetCycleStartDay
) {}
```

- [ ] **Passo 7: Compilar tudo**

```bash
cd backend && ./mvnw compile -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Passo 8: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/RecurringBudgetItemService.java \
        backend/src/main/java/com/fintech/api/dto/budget/
git commit -m "feat(planning): adiciona RecurringBudgetItemService e todos os DTOs de planejamento"
```

---

## Task 7: OpenAPI Spec — Schemas e Paths de Budget

**Files:**
- Modify: `backend/src/main/resources/static/openapi.yaml`

- [ ] **Passo 1: Adicionar schemas de budget na seção `components/schemas`**

No arquivo `openapi.yaml`, localizar a linha que contém `paths:` (linha ~618) e inserir os schemas **antes** dela (após o último schema existente):

```yaml
    # --- Budget Planning ---

    BudgetCycleStatus:
      type: string
      enum: [OPEN, CLOSED]

    BudgetItemSource:
      type: string
      enum: [MANUAL, RECURRING, INSTALLMENT]

    BudgetItemStatus:
      type: string
      enum: [PENDING, REALIZED, SKIPPED]

    BudgetCycleOpenRequest:
      type: object
      required: [referenceMonth]
      properties:
        referenceMonth:
          type: string
          pattern: '^\d{4}-\d{2}$'
          example: "2026-06"

    BudgetCycleSummary:
      type: object
      properties:
        plannedIncome:
          type: number
          format: double
        plannedExpense:
          type: number
          format: double
        projectedBalance:
          type: number
          format: double
        realizedIncome:
          type: number
          format: double
        realizedExpense:
          type: number
          format: double
        currentBalance:
          type: number
          format: double
        pendingCount:
          type: integer

    BudgetItemResponse:
      type: object
      properties:
        id:
          type: string
          format: uuid
        description:
          type: string
        amount:
          type: number
          format: double
        type:
          $ref: '#/components/schemas/TransactionType'
        expectedDate:
          type: string
          format: date
        source:
          $ref: '#/components/schemas/BudgetItemSource'
        status:
          $ref: '#/components/schemas/BudgetItemStatus'
        categoryId:
          type: string
          format: uuid
          nullable: true
        categoryName:
          type: string
          nullable: true
        accountId:
          type: string
          format: uuid
          nullable: true
        accountName:
          type: string
          nullable: true
        transactionId:
          type: string
          format: uuid
          nullable: true
        installmentGroupId:
          type: string
          format: uuid
          nullable: true

    BudgetCycleResponse:
      type: object
      properties:
        id:
          type: string
          format: uuid
        startDate:
          type: string
          format: date
        endDate:
          type: string
          format: date
        openingBalance:
          type: number
          format: double
        status:
          $ref: '#/components/schemas/BudgetCycleStatus'
        summary:
          $ref: '#/components/schemas/BudgetCycleSummary'
        items:
          type: array
          items:
            $ref: '#/components/schemas/BudgetItemResponse'

    BudgetItemCreateRequest:
      type: object
      required: [description, amount, type, expectedDate]
      properties:
        description:
          type: string
        amount:
          type: number
          format: double
        type:
          $ref: '#/components/schemas/TransactionType'
        expectedDate:
          type: string
          format: date
        categoryId:
          type: string
          format: uuid
          nullable: true
        accountId:
          type: string
          format: uuid
          nullable: true

    BudgetItemUpdateRequest:
      type: object
      required: [description, amount, expectedDate]
      properties:
        description:
          type: string
        amount:
          type: number
          format: double
        expectedDate:
          type: string
          format: date
        categoryId:
          type: string
          format: uuid
          nullable: true
        accountId:
          type: string
          format: uuid
          nullable: true

    BudgetItemLinkRequest:
      type: object
      required: [transactionId]
      properties:
        transactionId:
          type: string
          format: uuid

    RecurringBudgetItemRequest:
      type: object
      required: [description, amount, type, dayOfMonth]
      properties:
        description:
          type: string
        amount:
          type: number
          format: double
        type:
          $ref: '#/components/schemas/TransactionType'
        dayOfMonth:
          type: integer
          minimum: 1
          maximum: 28
        categoryId:
          type: string
          format: uuid
          nullable: true
        accountId:
          type: string
          format: uuid
          nullable: true

    RecurringBudgetItemResponse:
      type: object
      properties:
        id:
          type: string
          format: uuid
        description:
          type: string
        amount:
          type: number
          format: double
        type:
          $ref: '#/components/schemas/TransactionType'
        dayOfMonth:
          type: integer
        categoryId:
          type: string
          format: uuid
          nullable: true
        categoryName:
          type: string
          nullable: true
        accountId:
          type: string
          format: uuid
          nullable: true
        accountName:
          type: string
          nullable: true
        active:
          type: boolean

    TenantSettingsPatchRequest:
      type: object
      required: [budgetCycleStartDay]
      properties:
        budgetCycleStartDay:
          type: integer
          minimum: 1
          maximum: 28
```

- [ ] **Passo 2: Adicionar paths de budget ao final do arquivo `openapi.yaml`**

Append ao final do arquivo:

```yaml
  # --- Tenant Settings ---

  /api/tenant/settings:
    patch:
      tags: [tenant]
      operationId: patchTenantSettings
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/TenantSettingsPatchRequest'
      responses:
        '204':
          description: Configurações atualizadas

  # --- Budget Cycles ---

  /api/budget-cycles:
    get:
      tags: [budget]
      operationId: listBudgetCycles
      parameters:
        - name: page
          in: query
          schema:
            type: integer
            default: 0
        - name: size
          in: query
          schema:
            type: integer
            default: 12
      responses:
        '200':
          description: Histórico de ciclos paginado
          content:
            application/json:
              schema:
                type: object
                properties:
                  content:
                    type: array
                    items:
                      $ref: '#/components/schemas/BudgetCycleResponse'
                  totalElements:
                    type: integer
                  totalPages:
                    type: integer
                  number:
                    type: integer
    post:
      tags: [budget]
      operationId: openBudgetCycle
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/BudgetCycleOpenRequest'
      responses:
        '201':
          description: Ciclo aberto
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BudgetCycleResponse'
        '409':
          description: Ciclo já existe ou período conflita

  /api/budget-cycles/current:
    get:
      tags: [budget]
      operationId: getCurrentBudgetCycle
      responses:
        '200':
          description: Ciclo OPEN atual com itens
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BudgetCycleResponse'
        '404':
          description: Nenhum ciclo aberto

  /api/budget-cycles/{id}:
    get:
      tags: [budget]
      operationId: getBudgetCycle
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Ciclo com itens
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BudgetCycleResponse'

  /api/budget-cycles/{id}/close:
    post:
      tags: [budget]
      operationId: closeBudgetCycle
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Ciclo fechado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BudgetCycleResponse'

  /api/budget-cycles/{id}/sync-installments:
    post:
      tags: [budget]
      operationId: syncInstallments
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Parcelas sincronizadas
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BudgetCycleResponse'

  /api/budget-cycles/{cycleId}/items:
    get:
      tags: [budget]
      operationId: listBudgetItems
      parameters:
        - name: cycleId
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Lista de itens do ciclo
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/BudgetItemResponse'
    post:
      tags: [budget]
      operationId: createBudgetItem
      parameters:
        - name: cycleId
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
              $ref: '#/components/schemas/BudgetItemCreateRequest'
      responses:
        '201':
          description: Item criado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BudgetItemResponse'

  /api/budget-items/{id}:
    put:
      tags: [budget]
      operationId: updateBudgetItem
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
              $ref: '#/components/schemas/BudgetItemUpdateRequest'
      responses:
        '200':
          description: Item atualizado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BudgetItemResponse'
    delete:
      tags: [budget]
      operationId: deleteBudgetItem
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '204':
          description: Item removido

  /api/budget-items/{id}/link:
    post:
      tags: [budget]
      operationId: linkBudgetItem
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
              $ref: '#/components/schemas/BudgetItemLinkRequest'
      responses:
        '200':
          description: Transação vinculada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BudgetItemResponse'
    delete:
      tags: [budget]
      operationId: unlinkBudgetItem
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Transação desvinculada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BudgetItemResponse'

  # --- Recurring Budget Items ---

  /api/recurring-budget-items:
    get:
      tags: [budget]
      operationId: listRecurringBudgetItems
      responses:
        '200':
          description: Templates recorrentes ativos
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/RecurringBudgetItemResponse'
    post:
      tags: [budget]
      operationId: createRecurringBudgetItem
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/RecurringBudgetItemRequest'
      responses:
        '201':
          description: Template criado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RecurringBudgetItemResponse'

  /api/recurring-budget-items/{id}:
    put:
      tags: [budget]
      operationId: updateRecurringBudgetItem
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
              $ref: '#/components/schemas/RecurringBudgetItemRequest'
      responses:
        '200':
          description: Template atualizado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RecurringBudgetItemResponse'
    delete:
      tags: [budget]
      operationId: deleteRecurringBudgetItem
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '204':
          description: Template desativado
```

- [ ] **Passo 3: Rodar codegen para gerar as interfaces**

```bash
cd backend && ./mvnw generate-sources -q
```

Verificar que foram geradas as interfaces em `target/generated-sources/openapi/`:
```bash
find backend/target/generated-sources -name "BudgetApi.java" -o -name "*budget*" | head -5
```

- [ ] **Passo 4: Compilar**

```bash
cd backend && ./mvnw compile -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Passo 5: Commit**

```bash
git add backend/src/main/resources/static/openapi.yaml
git commit -m "feat(planning): adiciona schemas e paths de budget ao OpenAPI spec"
```

---

## Task 8: Controllers + SecurityConfigurations

**Files:**
- Create: `TenantController.java`, `BudgetCycleController.java`, `BudgetItemController.java`, `RecurringBudgetItemController.java`
- Modify: `SecurityConfigurations.java`

- [ ] **Passo 1: Criar TenantController.java**

```java
package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.TenantSettingsPatchRequest;
import com.fintech.api.repository.TenantRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantRepository tenantRepository;

    @PatchMapping("/settings")
    public ResponseEntity<Void> patchSettings(@Valid @RequestBody TenantSettingsPatchRequest req) {
        User user = getUser();
        var tenant = user.getTenant();
        tenant.setBudgetCycleStartDay(req.budgetCycleStartDay());
        tenantRepository.save(tenant);
        return ResponseEntity.noContent().build();
    }

    private User getUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Passo 2: Criar BudgetCycleController.java**

```java
package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetCycleOpenRequest;
import com.fintech.api.dto.budget.BudgetCycleResponseDTO;
import com.fintech.api.dto.budget.BudgetItemCreateRequest;
import com.fintech.api.dto.budget.BudgetItemResponseDTO;
import com.fintech.api.service.BudgetCycleService;
import com.fintech.api.service.BudgetItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/budget-cycles")
@RequiredArgsConstructor
public class BudgetCycleController {

    private final BudgetCycleService cycleService;
    private final BudgetItemService itemService;

    @GetMapping
    public ResponseEntity<Page<BudgetCycleResponseDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        User user = getUser();
        Page<BudgetCycleResponseDTO> result = cycleService
            .listByTenant(user.getTenant(), PageRequest.of(page, size))
            .map(c -> BudgetCycleResponseDTO.fromEntity(c, cycleService.listItems(c)));
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<BudgetCycleResponseDTO> open(@Valid @RequestBody BudgetCycleOpenRequest req) {
        User user = getUser();
        var cycle = cycleService.open(user.getTenant(), user, req.referenceMonth());
        return ResponseEntity.status(201)
            .body(BudgetCycleResponseDTO.fromEntity(cycle, cycleService.listItems(cycle)));
    }

    @GetMapping("/current")
    public ResponseEntity<BudgetCycleResponseDTO> current() {
        User user = getUser();
        return cycleService.findOpenByTenant(user.getTenant())
            .map(c -> ResponseEntity.ok(BudgetCycleResponseDTO.fromEntity(c, cycleService.listItems(c))))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BudgetCycleResponseDTO> get(@PathVariable UUID id) {
        User user = getUser();
        var cycle = cycleService.findByIdAndTenant(id, user.getTenant());
        return ResponseEntity.ok(BudgetCycleResponseDTO.fromEntity(cycle, cycleService.listItems(cycle)));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<BudgetCycleResponseDTO> close(@PathVariable UUID id) {
        User user = getUser();
        var cycle = cycleService.close(id, user.getTenant());
        return ResponseEntity.ok(BudgetCycleResponseDTO.fromEntity(cycle, cycleService.listItems(cycle)));
    }

    @PostMapping("/{id}/sync-installments")
    public ResponseEntity<BudgetCycleResponseDTO> syncInstallments(@PathVariable UUID id) {
        User user = getUser();
        var cycle = cycleService.syncInstallments(id, user.getTenant(), user);
        return ResponseEntity.ok(BudgetCycleResponseDTO.fromEntity(cycle, cycleService.listItems(cycle)));
    }

    @GetMapping("/{cycleId}/items")
    public ResponseEntity<List<BudgetItemResponseDTO>> listItems(@PathVariable UUID cycleId) {
        User user = getUser();
        var cycle = cycleService.findByIdAndTenant(cycleId, user.getTenant());
        return ResponseEntity.ok(cycleService.listItems(cycle).stream()
            .map(BudgetItemResponseDTO::fromEntity).toList());
    }

    @PostMapping("/{cycleId}/items")
    public ResponseEntity<BudgetItemResponseDTO> createItem(
            @PathVariable UUID cycleId,
            @Valid @RequestBody BudgetItemCreateRequest req) {
        User user = getUser();
        var cycle = cycleService.findByIdAndTenant(cycleId, user.getTenant());
        return ResponseEntity.status(201)
            .body(BudgetItemResponseDTO.fromEntity(
                itemService.create(cycle, req, user.getTenant(), user)));
    }

    private User getUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Passo 3: Criar BudgetItemController.java**

```java
package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetItemLinkRequest;
import com.fintech.api.dto.budget.BudgetItemResponseDTO;
import com.fintech.api.dto.budget.BudgetItemUpdateRequest;
import com.fintech.api.service.BudgetItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/budget-items")
@RequiredArgsConstructor
public class BudgetItemController {

    private final BudgetItemService itemService;

    @PutMapping("/{id}")
    public ResponseEntity<BudgetItemResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody BudgetItemUpdateRequest req) {
        User user = getUser();
        var item = itemService.findByIdAndTenant(id, user.getTenant());
        return ResponseEntity.ok(BudgetItemResponseDTO.fromEntity(itemService.update(item, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        User user = getUser();
        var item = itemService.findByIdAndTenant(id, user.getTenant());
        itemService.delete(item);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/link")
    public ResponseEntity<BudgetItemResponseDTO> link(
            @PathVariable UUID id,
            @Valid @RequestBody BudgetItemLinkRequest req) {
        User user = getUser();
        var item = itemService.findByIdAndTenant(id, user.getTenant());
        return ResponseEntity.ok(BudgetItemResponseDTO.fromEntity(
            itemService.link(item, req.transactionId())));
    }

    @DeleteMapping("/{id}/link")
    public ResponseEntity<BudgetItemResponseDTO> unlink(@PathVariable UUID id) {
        User user = getUser();
        var item = itemService.findByIdAndTenant(id, user.getTenant());
        return ResponseEntity.ok(BudgetItemResponseDTO.fromEntity(itemService.unlink(item)));
    }

    private User getUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Passo 4: Criar RecurringBudgetItemController.java**

```java
package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.RecurringBudgetItemRequest;
import com.fintech.api.dto.budget.RecurringBudgetItemResponseDTO;
import com.fintech.api.service.RecurringBudgetItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/recurring-budget-items")
@RequiredArgsConstructor
public class RecurringBudgetItemController {

    private final RecurringBudgetItemService service;

    @GetMapping
    public ResponseEntity<List<RecurringBudgetItemResponseDTO>> list() {
        User user = getUser();
        return ResponseEntity.ok(service.listActive(user.getTenant()).stream()
            .map(RecurringBudgetItemResponseDTO::fromEntity).toList());
    }

    @PostMapping
    public ResponseEntity<RecurringBudgetItemResponseDTO> create(
            @Valid @RequestBody RecurringBudgetItemRequest req) {
        User user = getUser();
        return ResponseEntity.status(201)
            .body(RecurringBudgetItemResponseDTO.fromEntity(
                service.create(req, user.getTenant(), user)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RecurringBudgetItemResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody RecurringBudgetItemRequest req) {
        User user = getUser();
        return ResponseEntity.ok(RecurringBudgetItemResponseDTO.fromEntity(
            service.update(id, req, user.getTenant())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        User user = getUser();
        service.deactivate(id, user.getTenant());
        return ResponseEntity.noContent().build();
    }

    private User getUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Passo 5: Adicionar PATCH ao CORS em SecurityConfigurations.java**

Localizar a linha:
```java
config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
```

Substituir por:
```java
config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
```

- [ ] **Passo 6: Compilar e rodar todos os testes**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -5
```

Esperado: `BUILD SUCCESS`, todos os testes passando.

- [ ] **Passo 7: Subir o backend e testar manualmente com a seed**

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Em outro terminal, testar:
```bash
# Login
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"carlos@costa.com","password":"costa123"}' | jq .token

# Abrir ciclo (substituir TOKEN pelo valor acima)
curl -s -X POST http://localhost:8080/api/budget-cycles \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"referenceMonth":"2026-06"}' | jq .
```

Esperado: ciclo criado com `status: "OPEN"` e `items` populados com parcelas do seed.

- [ ] **Passo 8: Commit**

```bash
git add backend/src/main/java/com/fintech/api/controller/ \
        backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java
git commit -m "feat(planning): adiciona controllers de planejamento e PATCH ao CORS"
```
