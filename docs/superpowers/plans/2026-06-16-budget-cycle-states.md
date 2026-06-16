# Budget Cycle States (OPEN / ENDED / CLOSED) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduzir o estado `ENDED` no ciclo de planejamento — ciclos cujo período terminou mas ainda não foram fechados pelo usuário — corrigindo o bug de `remainingDays` e adicionando um fluxo de preview antes da abertura de novo ciclo.

**Architecture:** Backend-first: migration → enum → serviços → controller → spec OpenAPI → regenerar Orval → frontend. A transição `OPEN→ENDED` é lazy (aciona na primeira requisição após `endDate`). O endpoint `GET /preview` computa o ciclo que cobriria hoje sem persistir nada.

**Tech Stack:** Java 21 / Spring Boot 4 / Flyway / JUnit 5 + Mockito + AssertJ (backend) · Angular 21 Zoneless / Signals / Angular Material 3 / Orval (frontend)

---

## Mapa de Arquivos

| Arquivo | Operação |
|---------|----------|
| `backend/src/main/resources/db/migration/V15__budget_cycle_ended_status.sql` | Criar |
| `backend/src/main/java/.../domain/enums/BudgetCycleStatus.java` | Modificar — adicionar `ENDED` |
| `backend/src/main/java/.../dto/budget/BudgetCycleOpenRequest.java` | Modificar — adicionar `openingBalance` nullable |
| `backend/src/main/java/.../dto/budget/BudgetCycleSummaryDTO.java` | Modificar — `remainingDays: int → Integer` |
| `backend/src/main/java/.../dto/budget/BudgetCyclePreviewDTO.java` | Criar |
| `backend/src/main/java/.../dto/budget/RecurringItemPreviewDTO.java` | Criar |
| `backend/src/main/java/.../dto/budget/InstallmentItemPreviewDTO.java` | Criar |
| `backend/src/main/java/.../service/BudgetSummaryService.java` | Modificar — null para não-OPEN |
| `backend/src/main/java/.../service/BudgetCycleService.java` | Modificar — lazy transition, open() fix, close() fix, preview() |
| `backend/src/main/java/.../controller/BudgetCycleController.java` | Modificar — /current fix, /preview endpoint |
| `backend/src/test/java/.../service/BudgetCycleServiceTest.java` | Modificar — testes novos |
| `backend/src/test/java/.../service/BudgetSummaryServiceTest.java` | Modificar — testes novos |
| `api-spec/openapi.yaml` | Modificar — ENDED, preview, openingBalance |
| `frontend/src/app/core/api/` | Regenerar via Orval |
| `frontend/src/app/features/planning/planning.service.ts` | Modificar — previewCycle() |
| `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.ts` | Modificar — lida com ENDED |
| `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html` | Modificar — banner ENDED, ocultar campos, botão abrir |
| `frontend/src/app/features/planning/budget-cycle-open-dialog/budget-cycle-open-dialog.ts` | Criar |
| `frontend/src/app/features/planning/budget-cycle-open-dialog/budget-cycle-open-dialog.html` | Criar |
| `frontend/src/app/features/planning/budget-cycle-open-dialog/budget-cycle-open-dialog.scss` | Criar |

---

## Task 1: Migration V15 — adiciona ENDED ao CHECK constraint

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__budget_cycle_ended_status.sql`

- [ ] **Step 1: Criar migration**

```sql
-- V15__budget_cycle_ended_status.sql
-- Adiciona o status ENDED ao ciclo de planejamento.
-- ENDED = período encerrado mas ainda não fechado pelo usuário (ajustes ainda permitidos).

ALTER TABLE budget_cycles
    DROP CONSTRAINT chk_cycle_status,
    ADD CONSTRAINT chk_cycle_status
        CHECK (status IN ('OPEN', 'ENDED', 'CLOSED'));
```

- [ ] **Step 2: Verificar que a migration é reconhecida pelo Flyway**

```bash
cd backend && ./mvnw flyway:info -pl . 2>&1 | grep -E "V15|Pending|Success"
```

Esperado: linha com `V15` e status `Pending`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V15__budget_cycle_ended_status.sql
git commit -m "feat(planning): migration V15 — adiciona status ENDED ao ciclo de orçamento"
```

---

## Task 2: Enum + DTOs de base

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/domain/enums/BudgetCycleStatus.java`
- Modify: `backend/src/main/java/com/fintech/api/dto/budget/BudgetCycleOpenRequest.java`
- Modify: `backend/src/main/java/com/fintech/api/dto/budget/BudgetCycleSummaryDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/budget/BudgetCyclePreviewDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/budget/RecurringItemPreviewDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/budget/InstallmentItemPreviewDTO.java`

- [ ] **Step 1: Adicionar ENDED ao enum**

```java
// BudgetCycleStatus.java
package com.fintech.api.domain.enums;

public enum BudgetCycleStatus { OPEN, ENDED, CLOSED }
```

- [ ] **Step 2: Adicionar openingBalance nullable ao request**

```java
// BudgetCycleOpenRequest.java
package com.fintech.api.dto.budget;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record BudgetCycleOpenRequest(
    @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}", message = "Formato esperado: yyyy-MM")
    String referenceMonth,

    @NotNull @Min(1) @Max(28)
    Integer startDay,

    BigDecimal openingBalance  // nullable — se null, usa saldo líquido atual das contas
) {}
```

- [ ] **Step 3: Tornar remainingDays Integer (nullable) no summary**

```java
// BudgetCycleSummaryDTO.java
package com.fintech.api.dto.budget;

import java.math.BigDecimal;

public record BudgetCycleSummaryDTO(
    BigDecimal openingBalance,
    BigDecimal plannedIncome,
    BigDecimal plannedExpense,
    BigDecimal projectedBalance,
    BigDecimal realizedIncome,
    BigDecimal realizedExpense,
    BigDecimal unplannedExpenses,
    BigDecimal availableToSpend,
    BigDecimal dailyAllowance,   // null quando status != OPEN
    Integer remainingDays,       // null quando status != OPEN (era int)
    long pendingCount
) {}
```

- [ ] **Step 4: Criar DTOs de preview**

```java
// RecurringItemPreviewDTO.java
package com.fintech.api.dto.budget;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringItemPreviewDTO(
    String description,
    BigDecimal amount,
    String type,           // "INCOME" | "EXPENSE"
    LocalDate expectedDate
) {}
```

```java
// InstallmentItemPreviewDTO.java
package com.fintech.api.dto.budget;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InstallmentItemPreviewDTO(
    String description,
    BigDecimal amount,
    LocalDate expectedDate
) {}
```

```java
// BudgetCyclePreviewDTO.java
package com.fintech.api.dto.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BudgetCyclePreviewDTO(
    String referenceMonth,                      // repassar ao POST /open
    int startDay,                               // repassar ao POST /open
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal suggestedOpeningBalance,
    List<RecurringItemPreviewDTO> recurringItems,
    List<InstallmentItemPreviewDTO> installmentItems,
    BigDecimal projectedIncome,
    BigDecimal projectedExpense
) {}
```

- [ ] **Step 5: Verificar compilação**

```bash
cd backend && ./mvnw compile -q 2>&1 | grep -E "ERROR|error"
```

Esperado: sem output (zero erros).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/domain/enums/BudgetCycleStatus.java \
        backend/src/main/java/com/fintech/api/dto/budget/
git commit -m "feat(planning): adiciona ENDED ao enum e DTOs de preview e abertura de ciclo"
```

---

## Task 3: BudgetSummaryService — nulls para não-OPEN

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/BudgetSummaryService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/BudgetSummaryServiceTest.java`

- [ ] **Step 1: Escrever testes primeiro**

Adicionar ao final da classe `BudgetSummaryServiceTest`, dentro de um novo `@Nested`:

```java
@Nested
@DisplayName("remainingDays e dailyAllowance — nulos fora de OPEN")
class NullFieldsForNonOpenTests {

    @Test
    @DisplayName("ciclo ENDED → remainingDays null e dailyAllowance null")
    void calculateSummary_ended_remainingDaysNull() {
        Tenant tenant = createTenant();
        LocalDate start = LocalDate.of(2026, 5, 1);
        LocalDate end = LocalDate.of(2026, 5, 31);
        BudgetCycle cycle = BudgetCycle.builder()
            .id(UUID.randomUUID()).tenant(tenant)
            .startDate(start).endDate(end)
            .openingBalance(BigDecimal.ZERO)
            .status(BudgetCycleStatus.ENDED)
            .build();

        when(transactionRepository.sumUnplannedExpenses(any(), any(), any(), any()))
            .thenReturn(BigDecimal.ZERO);

        BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, List.of(), LocalDate.of(2026, 6, 10));

        assertThat(summary.remainingDays()).isNull();
        assertThat(summary.dailyAllowance()).isNull();
    }

    @Test
    @DisplayName("ciclo OPEN → remainingDays = dias entre hoje e endDate")
    void calculateSummary_open_remainingDaysCorreto() {
        Tenant tenant = createTenant();
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 30);
        LocalDate today = LocalDate.of(2026, 6, 10);
        BudgetCycle cycle = createCycle(tenant, BigDecimal.ZERO, start, end); // status OPEN

        when(transactionRepository.sumUnplannedExpenses(any(), any(), any(), any()))
            .thenReturn(BigDecimal.ZERO);

        BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, List.of(), today);

        assertThat(summary.remainingDays()).isEqualTo(20); // entre 10/jun e 30/jun
    }
}
```

- [ ] **Step 2: Rodar testes — confirmar que falham**

```bash
cd backend && ./mvnw test -pl . -Dtest=BudgetSummaryServiceTest -q 2>&1 | tail -10
```

Esperado: 2 falhas (compilação ou assertion).

- [ ] **Step 3: Implementar no BudgetSummaryService**

Substituir as linhas 49-50 do `BudgetSummaryService.calculateSummary()`:

```java
// Antes:
// int remainingDays = (int) ChronoUnit.DAYS.between(today, cycle.getEndDate());
// BigDecimal dailyAllowance = calculateDailyAllowance(availableToSpend, cycle.getEndDate(), today);

// Depois:
Integer remainingDays = cycle.getStatus() == BudgetCycleStatus.OPEN
    ? (int) ChronoUnit.DAYS.between(today, cycle.getEndDate())
    : null;
BigDecimal dailyAllowance = cycle.getStatus() == BudgetCycleStatus.OPEN
    ? calculateDailyAllowance(availableToSpend, cycle.getEndDate(), today)
    : null;
```

Adicionar import no topo:
```java
import com.fintech.api.domain.enums.BudgetCycleStatus;
```

- [ ] **Step 4: Rodar testes — confirmar que passam**

```bash
cd backend && ./mvnw test -pl . -Dtest=BudgetSummaryServiceTest -q 2>&1 | tail -5
```

Esperado: `BUILD SUCCESS`, zero falhas.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/BudgetSummaryService.java \
        backend/src/test/java/com/fintech/api/service/BudgetSummaryServiceTest.java
git commit -m "fix(planning): remainingDays e dailyAllowance retornam null para ciclos não-OPEN"
```

---

## Task 4: BudgetCycleService — lazy transition + open() + close() + preview()

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/BudgetCycleService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java`

- [ ] **Step 1: Escrever testes**

Adicionar ao `BudgetCycleServiceTest`:

```java
// ---- findCurrentByTenant() — lazy ENDED transition ----

@Test
@DisplayName("findCurrentByTenant() transita OPEN→ENDED quando endDate < today")
void findCurrentByTenant_openComDataPassada_transitaParaEnded() {
    Tenant tenant = tenantWith(1);
    BudgetCycle cycle = BudgetCycle.builder()
        .id(UUID.randomUUID()).tenant(tenant)
        .startDate(LocalDate.of(2026, 5, 1))
        .endDate(LocalDate.of(2026, 5, 31))
        .openingBalance(BigDecimal.ZERO)
        .status(BudgetCycleStatus.OPEN)
        .build();

    when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
    when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
        .thenReturn(Optional.of(cycle));
    when(cycleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Optional<BudgetCycle> result = service.findCurrentByTenant(tenant);

    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(BudgetCycleStatus.ENDED);
    verify(cycleRepository).save(cycle);
}

@Test
@DisplayName("findCurrentByTenant() retorna ENDED quando não há OPEN")
void findCurrentByTenant_semOpen_retornaEnded() {
    Tenant tenant = tenantWith(1);
    BudgetCycle ended = BudgetCycle.builder()
        .id(UUID.randomUUID()).tenant(tenant)
        .startDate(LocalDate.of(2026, 5, 1))
        .endDate(LocalDate.of(2026, 5, 31))
        .openingBalance(BigDecimal.ZERO)
        .status(BudgetCycleStatus.ENDED)
        .build();

    when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
        .thenReturn(Optional.empty());
    when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.ENDED))
        .thenReturn(Optional.of(ended));

    Optional<BudgetCycle> result = service.findCurrentByTenant(tenant);

    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(BudgetCycleStatus.ENDED);
}

// ---- open() — validação de data ----

@Test
@DisplayName("open() lança IllegalStateException quando datas não incluem hoje")
void open_datasForaDeHoje_lancaException() {
    Tenant tenant = tenantWith(1);
    User user = new User();

    when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
    when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
        .thenReturn(Optional.empty());
    when(cycleRepository.existsOverlap(any(), any(), any())).thenReturn(false);

    // referenceMonth futuro → startDate no futuro (hoje = 2026-06-16, ciclo agosto)
    assertThatThrownBy(() -> service.open(tenant, user, new BudgetCycleOpenRequest("2026-08", 1, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("O período do ciclo deve compreender a data atual.");
}

@Test
@DisplayName("open() usa openingBalance do request quando informado")
void open_openingBalanceOverride_usaValorInformado() {
    Tenant tenant = tenantWith(1);
    User user = new User();

    when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
    when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
        .thenReturn(Optional.empty());
    when(cycleRepository.existsOverlap(any(), any(), any())).thenReturn(false);
    when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(recurringRepository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(any()))
        .thenReturn(List.of());
    when(transactionRepository.findInstallmentsInPeriodByTenant(any(), any(), any(), any()))
        .thenReturn(List.of());

    var captor = ArgumentCaptor.forClass(BudgetCycle.class);
    when(cycleRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

    // Usa o mês atual para que as datas incluam hoje
    String currentMonth = java.time.YearMonth.now().toString();
    service.open(tenant, user, new BudgetCycleOpenRequest(currentMonth, 1, new BigDecimal("5000.00")));

    assertThat(captor.getValue().getOpeningBalance()).isEqualByComparingTo("5000.00");
    verify(accountRepository, never()).sumLiquidBalanceByTenant(any(), any(), any());
}

// ---- close() — requer ENDED ----

@Test
@DisplayName("close() em ciclo OPEN lança IllegalStateException")
void close_cicloOpen_lancaException() {
    Tenant tenant = tenantWith(1);
    BudgetCycle cycle = BudgetCycle.builder()
        .id(UUID.randomUUID()).tenant(tenant)
        .startDate(LocalDate.now().minusDays(5))
        .endDate(LocalDate.now().plusDays(25))
        .openingBalance(BigDecimal.ZERO)
        .status(BudgetCycleStatus.OPEN)
        .build();

    when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));

    assertThatThrownBy(() -> service.close(cycle.getId(), tenant))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("O ciclo ainda está em andamento.");
}

@Test
@DisplayName("close() em ciclo ENDED → persiste CLOSED com snapshot")
void close_cicloEnded_persisteFechado() {
    Tenant tenant = tenantWith(1);
    BudgetCycle cycle = BudgetCycle.builder()
        .id(UUID.randomUUID()).tenant(tenant)
        .startDate(LocalDate.of(2026, 5, 1))
        .endDate(LocalDate.of(2026, 5, 31))
        .openingBalance(BigDecimal.ZERO)
        .status(BudgetCycleStatus.ENDED)
        .build();

    when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));
    when(itemRepository.findAllByCycleWithDetails(cycle)).thenReturn(List.of());
    when(summaryService.calculateSummary(eq(cycle), any(), any())).thenReturn(
        new BudgetCycleSummaryDTO(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, null, 0L
        )
    );
    when(cycleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    BudgetCycle result = service.close(cycle.getId(), tenant);

    assertThat(result.getStatus()).isEqualTo(BudgetCycleStatus.CLOSED);
}
```

- [ ] **Step 2: Rodar testes — confirmar falhas**

```bash
cd backend && ./mvnw test -pl . -Dtest=BudgetCycleServiceTest -q 2>&1 | tail -10
```

Esperado: múltiplas falhas de compilação ou assertion.

- [ ] **Step 3: Implementar no BudgetCycleService**

Substituir o conteúdo completo do arquivo:

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
import com.fintech.api.dto.budget.*;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.*;
import org.springframework.security.access.AccessDeniedException;
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
    private final TenantRepository tenantRepository;
    private final BudgetSummaryService summaryService;

    LocalDate[] calculateCycleDates(YearMonth referenceMonth, int startDay) {
        if (startDay == 1) {
            return new LocalDate[]{referenceMonth.atDay(1), referenceMonth.atEndOfMonth()};
        }
        return new LocalDate[]{
            referenceMonth.minusMonths(1).atDay(startDay),
            referenceMonth.atDay(startDay - 1)
        };
    }

    LocalDate calculateExpectedDate(LocalDate cycleStartDate, int startDay, int dayOfMonth) {
        if (dayOfMonth >= startDay) {
            return cycleStartDate.withDayOfMonth(dayOfMonth);
        }
        return cycleStartDate.plusMonths(1).withDayOfMonth(dayOfMonth);
    }

    /**
     * Transita OPEN→ENDED se endDate < today. Operação idempotente e lazy.
     */
    @Transactional
    public BudgetCycle checkAndTransitionToEnded(BudgetCycle cycle) {
        if (cycle.getStatus() == BudgetCycleStatus.OPEN
                && cycle.getEndDate().isBefore(LocalDate.now())) {
            cycle.setStatus(BudgetCycleStatus.ENDED);
            cycleRepository.save(cycle);
            log.info("Ciclo transitado para ENDED [cycleId={}]", cycle.getId());
        }
        return cycle;
    }

    /**
     * Retorna o ciclo "atual" do tenant: OPEN (verificando lazy transition) ou ENDED.
     */
    @Transactional
    public Optional<BudgetCycle> findCurrentByTenant(Tenant tenant) {
        Optional<BudgetCycle> open = cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN);
        if (open.isPresent()) {
            return Optional.of(checkAndTransitionToEnded(open.get()));
        }
        return cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.ENDED);
    }

    @Transactional
    public BudgetCycle open(Tenant tenant, User user, BudgetCycleOpenRequest req) {
        Tenant managed = tenantRepository.findById(tenant.getId())
            .orElseThrow(() -> new EntityNotFoundException("Tenant não encontrado."));

        if (cycleRepository.findByTenantAndStatus(managed, BudgetCycleStatus.OPEN).isPresent()) {
            throw new IllegalStateException("Já existe um ciclo aberto para este tenant.");
        }

        int startDay = req.startDay();
        LocalDate[] dates = calculateCycleDates(YearMonth.parse(req.referenceMonth()), startDay);
        LocalDate startDate = dates[0];
        LocalDate endDate   = dates[1];
        LocalDate today     = LocalDate.now();

        if (today.isBefore(startDate) || today.isAfter(endDate)) {
            throw new IllegalStateException("O período do ciclo deve compreender a data atual.");
        }

        if (cycleRepository.existsOverlap(managed, startDate, endDate)) {
            throw new IllegalStateException("O período solicitado conflita com um ciclo já existente.");
        }

        managed.setBudgetCycleStartDay(startDay);
        tenantRepository.save(managed);

        BigDecimal opening = req.openingBalance() != null
            ? req.openingBalance()
            : accountRepository.sumLiquidBalanceByTenant(
                managed.getId(), TransactionType.INCOME, TransactionStatus.PAID);

        BudgetCycle cycle = cycleRepository.save(BudgetCycle.builder()
            .tenant(managed)
            .startDate(startDate)
            .endDate(endDate)
            .openingBalance(opening)
            .referenceMonth(req.referenceMonth())
            .status(BudgetCycleStatus.OPEN)
            .createdBy(user)
            .build());

        populateRecurringItems(cycle, managed, user, startDate, startDay);
        populateInstallmentItems(cycle, managed, startDate, endDate);

        log.info("Ciclo de planejamento aberto [cycleId={} tenantId={} periodo={}/{} startDay={}]",
            cycle.getId(), managed.getId(), startDate, endDate, startDay);
        return cycle;
    }

    /**
     * Retorna preview do ciclo que seria criado sem persistir nada.
     * Auto-determina referenceMonth a partir de today + startDay.
     */
    @Transactional(readOnly = true)
    public BudgetCyclePreviewDTO preview(Tenant tenant, int startDay) {
        Tenant managed = tenantRepository.findById(tenant.getId())
            .orElseThrow(() -> new EntityNotFoundException("Tenant não encontrado."));

        LocalDate today = LocalDate.now();
        YearMonth thisMonth = YearMonth.from(today);

        // Tenta este mês, depois o próximo, até encontrar o período que contém today
        LocalDate[] dates = null;
        YearMonth refMonth = null;
        for (int offset = 0; offset <= 1; offset++) {
            YearMonth candidate = thisMonth.plusMonths(offset);
            LocalDate[] d = calculateCycleDates(candidate, startDay);
            if (!today.isBefore(d[0]) && !today.isAfter(d[1])) {
                dates = d;
                refMonth = candidate;
                break;
            }
        }
        if (dates == null) {
            throw new IllegalStateException("Não foi possível determinar o ciclo atual para o dia de início informado.");
        }

        final LocalDate startDate = dates[0];
        final LocalDate endDate   = dates[1];
        final int sd = startDay;

        BigDecimal suggestedBalance = accountRepository.sumLiquidBalanceByTenant(
            managed.getId(), TransactionType.INCOME, TransactionStatus.PAID);

        List<RecurringBudgetItem> templates =
            recurringRepository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(managed);

        List<RecurringItemPreviewDTO> recurringPreviews = templates.stream()
            .map(t -> new RecurringItemPreviewDTO(
                t.getDescription(),
                t.getAmount(),
                t.getType().name(),
                calculateExpectedDate(startDate, sd, t.getDayOfMonth())))
            .toList();

        List<Transaction> installments = transactionRepository.findInstallmentsInPeriodByTenant(
            managed.getId(), startDate, endDate, TransactionStatus.CANCELLED);

        Map<InstallmentGroup, List<Transaction>> byGroup = installments.stream()
            .collect(Collectors.groupingBy(Transaction::getInstallmentGroup));

        List<InstallmentItemPreviewDTO> installmentPreviews = byGroup.entrySet().stream()
            .map(entry -> {
                InstallmentGroup group = entry.getKey();
                BigDecimal total = entry.getValue().stream()
                    .map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                LocalDate dueDate = entry.getValue().get(0).getInvoice().getDueDate();
                return new InstallmentItemPreviewDTO(group.getDescription(), total, dueDate);
            })
            .toList();

        BigDecimal projectedIncome = recurringPreviews.stream()
            .filter(r -> "INCOME".equals(r.type()))
            .map(RecurringItemPreviewDTO::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal projectedExpense = recurringPreviews.stream()
            .filter(r -> "EXPENSE".equals(r.type()))
            .map(RecurringItemPreviewDTO::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .add(installmentPreviews.stream()
                .map(InstallmentItemPreviewDTO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        return new BudgetCyclePreviewDTO(
            refMonth.toString(), startDay, startDate, endDate, suggestedBalance,
            recurringPreviews, installmentPreviews, projectedIncome, projectedExpense
        );
    }

    @Transactional
    public BudgetCycle close(UUID cycleId, Tenant tenant) {
        BudgetCycle cycle = findByIdAndTenant(cycleId, tenant);

        if (cycle.getStatus() == BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo ainda está em andamento.");
        }
        if (cycle.getStatus() == BudgetCycleStatus.CLOSED) {
            throw new IllegalStateException("O ciclo já está fechado.");
        }

        List<BudgetItem> items = itemRepository.findAllByCycleWithDetails(cycle);
        BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, items, LocalDate.now());

        cycle.setSnapshotProjectedBalance(summary.projectedBalance());
        cycle.setSnapshotAvailableToSpend(summary.availableToSpend());
        cycle.setSnapshotRealizedIncome(summary.realizedIncome());
        cycle.setSnapshotRealizedExpense(summary.realizedExpense());
        cycle.setSnapshotUnplannedExpenses(summary.unplannedExpenses());
        cycle.setStatus(BudgetCycleStatus.CLOSED);

        log.info("Ciclo fechado [cycleId={} tenantId={}]", cycleId, tenant.getId());
        return cycleRepository.save(cycle);
    }

    @Transactional
    public BudgetCycle syncInstallments(UUID cycleId, Tenant tenant, User user) {
        BudgetCycle cycle = findByIdAndTenant(cycleId, tenant);
        List<BudgetItem> existing = itemRepository.findAllByCycleWithDetails(cycle);
        itemRepository.deleteAll(existing.stream()
            .filter(i -> i.getSource() == BudgetItemSource.INSTALLMENT)
            .toList());
        populateInstallmentItems(cycle, tenant, cycle.getStartDate(), cycle.getEndDate());
        return cycle;
    }

    @Transactional
    public BudgetCycle findByIdAndTenant(UUID id, Tenant tenant) {
        BudgetCycle cycle = cycleRepository.findById(id)
            .filter(c -> c.getTenant().getId().equals(tenant.getId()))
            .orElseThrow(() -> new AccessDeniedException("Acesso negado."));
        return checkAndTransitionToEnded(cycle);
    }

    @Transactional(readOnly = true)
    public Page<BudgetCycle> listByTenant(Tenant tenant, Pageable pageable) {
        return cycleRepository.findAllByTenantOrderByStartDateDesc(tenant, pageable);
    }

    @Transactional(readOnly = true)
    public List<BudgetItem> listItems(BudgetCycle cycle) {
        return itemRepository.findAllByCycleWithDetails(cycle);
    }

    // ---- private helpers ----

    private void populateRecurringItems(BudgetCycle cycle, Tenant tenant, User user,
                                        LocalDate startDate, int startDay) {
        List<RecurringBudgetItem> templates =
            recurringRepository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(tenant);
        itemRepository.saveAll(templates.stream()
            .map(t -> BudgetItem.builder()
                .cycle(cycle).tenant(tenant)
                .description(t.getDescription()).amount(t.getAmount()).type(t.getType())
                .category(t.getCategory()).account(t.getAccount())
                .expectedDate(calculateExpectedDate(startDate, startDay, t.getDayOfMonth()))
                .source(BudgetItemSource.RECURRING).recurringItem(t).createdBy(user)
                .build())
            .toList());
    }

    private void populateInstallmentItems(BudgetCycle cycle, Tenant tenant,
                                          LocalDate startDate, LocalDate endDate) {
        List<Transaction> installments = transactionRepository.findInstallmentsInPeriodByTenant(
            tenant.getId(), startDate, endDate, TransactionStatus.CANCELLED);
        Map<InstallmentGroup, List<Transaction>> byGroup = installments.stream()
            .collect(Collectors.groupingBy(Transaction::getInstallmentGroup));
        itemRepository.saveAll(byGroup.entrySet().stream()
            .map(entry -> {
                InstallmentGroup group = entry.getKey();
                List<Transaction> txs = entry.getValue();
                BigDecimal total = txs.stream().map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                return BudgetItem.builder()
                    .cycle(cycle).tenant(tenant)
                    .description(group.getDescription()).amount(total)
                    .type(TransactionType.EXPENSE)
                    .account(group.getAccount()).category(group.getCategory())
                    .expectedDate(txs.get(0).getInvoice().getDueDate())
                    .source(BudgetItemSource.INSTALLMENT).installmentGroup(group)
                    .build();
            })
            .toList());
    }
}
```

- [ ] **Step 4: Rodar testes — confirmar que passam**

```bash
cd backend && ./mvnw test -pl . -Dtest=BudgetCycleServiceTest -q 2>&1 | tail -5
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/BudgetCycleService.java \
        backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java
git commit -m "feat(planning): lazy transition OPEN→ENDED, validação de data em open(), preview()"
```

---

## Task 5: BudgetCycleController — /current e /preview

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/controller/BudgetCycleController.java`

- [ ] **Step 1: Atualizar controller**

```java
package com.fintech.api.controller;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.*;
import com.fintech.api.service.BudgetCycleService;
import com.fintech.api.service.BudgetItemService;
import com.fintech.api.service.BudgetSummaryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/budget-cycles")
@RequiredArgsConstructor
public class BudgetCycleController {

    private final BudgetCycleService cycleService;
    private final BudgetItemService itemService;
    private final BudgetSummaryService summaryService;

    @GetMapping
    public ResponseEntity<Page<BudgetCycleResponseDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        User user = getUser();
        return ResponseEntity.ok(cycleService
            .listByTenant(user.getTenant(), PageRequest.of(page, size))
            .map(this::buildResponse));
    }

    @PostMapping
    public ResponseEntity<BudgetCycleResponseDTO> open(@Valid @RequestBody BudgetCycleOpenRequest req) {
        User user = getUser();
        return ResponseEntity.status(201).body(buildResponse(cycleService.open(user.getTenant(), user, req)));
    }

    @GetMapping("/current")
    public ResponseEntity<BudgetCycleResponseDTO> current() {
        User user = getUser();
        return cycleService.findCurrentByTenant(user.getTenant())
            .map(c -> ResponseEntity.ok(buildResponse(c)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/preview")
    public ResponseEntity<BudgetCyclePreviewDTO> preview(
            @RequestParam(required = false) Integer startDay) {
        User user = getUser();
        int sd = startDay != null ? startDay : user.getTenant().getBudgetCycleStartDay();
        return ResponseEntity.ok(cycleService.preview(user.getTenant(), sd));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BudgetCycleResponseDTO> get(@PathVariable UUID id) {
        User user = getUser();
        return ResponseEntity.ok(buildResponse(cycleService.findByIdAndTenant(id, user.getTenant())));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<BudgetCycleResponseDTO> close(@PathVariable UUID id) {
        User user = getUser();
        return ResponseEntity.ok(buildResponse(cycleService.close(id, user.getTenant())));
    }

    @PostMapping("/{id}/sync-installments")
    public ResponseEntity<BudgetCycleResponseDTO> syncInstallments(@PathVariable UUID id) {
        User user = getUser();
        return ResponseEntity.ok(buildResponse(cycleService.syncInstallments(id, user.getTenant(), user)));
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
        return ResponseEntity.status(201).body(BudgetItemResponseDTO.fromEntity(
            itemService.create(cycle, req, user.getTenant(), user)));
    }

    private BudgetCycleResponseDTO buildResponse(BudgetCycle cycle) {
        var items = cycleService.listItems(cycle);
        var summary = summaryService.calculateSummary(cycle, items, LocalDate.now());
        return BudgetCycleResponseDTO.fromEntity(cycle, items, summary);
    }

    private User getUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 2: Rodar testes do backend completo**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -10
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/fintech/api/controller/BudgetCycleController.java
git commit -m "feat(planning): GET /current retorna ENDED, novo GET /preview sem persistir"
```

---

## Task 6: OpenAPI spec + regenerar Orval

**Files:**
- Modify: `api-spec/openapi.yaml`
- Modify: `frontend/src/app/core/api/` (regenerar)

- [ ] **Step 1: Adicionar `ENDED` ao enum de status na spec**

Localizar `BudgetCycleStatus` em `api-spec/openapi.yaml` e adicionar `ENDED`:

```yaml
    BudgetCycleStatus:
      type: string
      enum: [OPEN, ENDED, CLOSED]
```

- [ ] **Step 2: Adicionar `openingBalance` ao `BudgetCycleOpenRequest`**

```yaml
    BudgetCycleOpenRequest:
      type: object
      required: [referenceMonth, startDay]
      properties:
        referenceMonth:
          type: string
          pattern: '^\d{4}-\d{2}$'
        startDay:
          type: integer
          minimum: 1
          maximum: 28
        openingBalance:
          type: number
          format: double
          nullable: true
```

- [ ] **Step 3: Tornar `remainingDays` e `dailyAllowance` nullable no `BudgetCycleSummary`**

```yaml
    BudgetCycleSummary:
      type: object
      properties:
        # ... campos existentes ...
        dailyAllowance:
          type: number
          format: double
          nullable: true
        remainingDays:
          type: integer
          nullable: true
```

- [ ] **Step 4: Adicionar endpoint `GET /preview` e schemas de preview**

Adicionar em `paths`, após `GET /api/budget-cycles`:

```yaml
  /api/budget-cycles/preview:
    get:
      tags: [budget]
      operationId: previewBudgetCycle
      parameters:
        - name: startDay
          in: query
          required: false
          schema:
            type: integer
            minimum: 1
            maximum: 28
      responses:
        '200':
          description: Preview do ciclo que seria criado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/BudgetCyclePreview'
        '422':
          description: Não há período válido para hoje com o startDay informado
```

Adicionar em `components/schemas`:

```yaml
    RecurringItemPreview:
      type: object
      properties:
        description:
          type: string
        amount:
          type: number
          format: double
        type:
          type: string
          enum: [INCOME, EXPENSE]
        expectedDate:
          type: string
          format: date

    InstallmentItemPreview:
      type: object
      properties:
        description:
          type: string
        amount:
          type: number
          format: double
        expectedDate:
          type: string
          format: date

    BudgetCyclePreview:
      type: object
      properties:
        referenceMonth:
          type: string
        startDay:
          type: integer
        startDate:
          type: string
          format: date
        endDate:
          type: string
          format: date
        suggestedOpeningBalance:
          type: number
          format: double
        recurringItems:
          type: array
          items:
            $ref: '#/components/schemas/RecurringItemPreview'
        installmentItems:
          type: array
          items:
            $ref: '#/components/schemas/InstallmentItemPreview'
        projectedIncome:
          type: number
          format: double
        projectedExpense:
          type: number
          format: double
```

- [ ] **Step 5: Copiar spec para o backend**

```bash
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
```

- [ ] **Step 6: Regenerar cliente Orval**

```bash
cd frontend && npm run api:generate 2>&1 | tail -5
```

Esperado: `🎉 fintechApi - Your OpenAPI spec has been converted into ready to use orval!`

- [ ] **Step 7: Verificar TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Esperado: zero erros.

- [ ] **Step 8: Commit**

```bash
cd ..
git add api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml frontend/src/app/core/api/
git commit -m "feat(planning): atualiza spec OpenAPI com ENDED, preview e openingBalance editável"
```

---

## Task 7: Frontend — PlanningService + estado ENDED no budget-cycle-current

**Files:**
- Modify: `frontend/src/app/features/planning/planning.service.ts`
- Modify: `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.ts`
- Modify: `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html`
- Modify: `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.scss`

- [ ] **Step 1: Adicionar `previewCycle()` ao PlanningService**

No `planning.service.ts`, adicionar após `syncInstallments()`:

```ts
import { BudgetCyclePreview } from '../../../core/api/fintechSaaSAPI.schemas';

previewCycle(startDay?: number): Observable<BudgetCyclePreview> {
  return this.budget.previewBudgetCycle(startDay !== undefined ? { startDay } : undefined);
}
```

- [ ] **Step 2: Adicionar método `openCycle()` ao BudgetCycleCurrentComponent**

No `budget-cycle-current.ts`, ler o arquivo atual e adicionar:

```ts
// import adicional
import { BudgetCycleOpenDialogComponent } from '../budget-cycle-open-dialog/budget-cycle-open-dialog';
```

E o método:

```ts
openCycle(): void {
  const ref = this.dialog.open(BudgetCycleOpenDialogComponent, { width: '600px' });
  ref.afterClosed().subscribe((opened: boolean) => {
    if (opened) this.loadCurrentCycle();
  });
}
```

- [ ] **Step 3: Atualizar o HTML para lidar com ENDED e sem ciclo**

No `budget-cycle-current.html`, as seguintes mudanças no bloco principal:

**3a. Banner ENDED** — adicionar após o bloco `<div class="cycle-header">`:

```html
@if (cycle()?.status === 'ENDED') {
  <mat-card class="ended-banner">
    <mat-card-content>
      <mat-icon>event_busy</mat-icon>
      <span>Período encerrado — revise os lançamentos e feche o ciclo quando estiver pronto.</span>
      <button mat-flat-button color="warn" (click)="closeCycle()">
        Fechar ciclo definitivamente
      </button>
    </mat-card-content>
  </mat-card>
}
```

**3b. Ocultar card "Disponível" em ENDED** — já está condicional por `status === 'OPEN'`, sem mudança.

**3c. Botão "Abrir ciclo"** — adicionar antes do bloco principal `@if (cycle())`:

```html
@if (!cycle() && !loading()) {
  <div class="no-cycle">
    <p>Nenhum ciclo de planejamento em andamento.</p>
    <button mat-flat-button color="primary" (click)="openCycle()">
      <mat-icon>add_circle_outline</mat-icon>
      Abrir novo ciclo
    </button>
  </div>
}
```

- [ ] **Step 4: Estilo do banner ENDED**

No `budget-cycle-current.scss`, adicionar:

```scss
.ended-banner {
  margin-bottom: 16px;
  background-color: var(--mat-sys-error-container);

  mat-card-content {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;

    mat-icon { color: var(--mat-sys-error); }
    span { flex: 1; }
  }
}

.no-cycle {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 48px;
  text-align: center;
}
```

- [ ] **Step 5: Verificar TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Esperado: zero erros.

- [ ] **Step 6: Commit**

```bash
cd ..
git add frontend/src/app/features/planning/planning.service.ts \
        frontend/src/app/features/planning/budget-cycle-current/
git commit -m "feat(planning): banner ENDED, botão abrir ciclo e previewCycle() no PlanningService"
```

---

## Task 8: Frontend — BudgetCycleOpenDialog (preview → confirmar)

**Files:**
- Create: `frontend/src/app/features/planning/budget-cycle-open-dialog/budget-cycle-open-dialog.ts`
- Create: `frontend/src/app/features/planning/budget-cycle-open-dialog/budget-cycle-open-dialog.html`
- Create: `frontend/src/app/features/planning/budget-cycle-open-dialog/budget-cycle-open-dialog.scss`

- [ ] **Step 1: Criar o componente TypeScript**

```ts
// budget-cycle-open-dialog.ts
import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { BudgetCyclePreview } from '../../../core/api/fintechSaaSAPI.schemas';
import { PlanningService } from '../planning.service';

@Component({
  selector: 'app-budget-cycle-open-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatButtonModule, MatFormFieldModule,
    MatInputModule, MatIconModule, MatProgressSpinnerModule, CurrencyPipe,
  ],
  templateUrl: './budget-cycle-open-dialog.html',
  styleUrl: './budget-cycle-open-dialog.scss',
})
export class BudgetCycleOpenDialogComponent implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly dialogRef = inject(MatDialogRef<BudgetCycleOpenDialogComponent>);
  private readonly snackBar = inject(MatSnackBar);

  readonly preview = signal<BudgetCyclePreview | null>(null);
  readonly loadingPreview = signal(true);
  readonly saving = signal(false);
  readonly previewError = signal<string | null>(null);

  readonly form = new FormGroup({
    openingBalance: new FormControl<number | null>(null, [Validators.required, Validators.min(0)]),
  });

  private readonly statusSignal = toSignal(this.form.statusChanges, { initialValue: this.form.status });
  readonly isInvalid = computed(() => this.statusSignal() === 'INVALID');

  ngOnInit(): void {
    this.planningService.previewCycle().subscribe({
      next: p => {
        this.preview.set(p);
        this.form.patchValue({ openingBalance: p.suggestedOpeningBalance ?? 0 });
        this.loadingPreview.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.previewError.set(err.error?.message ?? 'Erro ao carregar preview do ciclo.');
        this.loadingPreview.set(false);
      },
    });
  }

  confirm(): void {
    const p = this.preview();
    if (!p || this.form.invalid) return;
    this.saving.set(true);

    this.planningService.openCycle({
      referenceMonth: p.referenceMonth!,
      startDay: p.startDay!,
      openingBalance: this.form.value.openingBalance ?? undefined,
    }).subscribe({
      next: () => {
        this.snackBar.open('Ciclo aberto com sucesso.', 'OK', { duration: 2000 });
        this.dialogRef.close(true);
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.snackBar.open(err.error?.message ?? 'Erro ao abrir ciclo.', 'OK', { duration: 3000 });
      },
    });
  }

  cancel(): void {
    this.dialogRef.close(false);
  }
}
```


- [ ] **Step 2: Criar o HTML**

```html
<!-- budget-cycle-open-dialog.html -->
<h2 mat-dialog-title>Abrir novo ciclo de planejamento</h2>

<mat-dialog-content>
  @if (loadingPreview()) {
    <div class="loading-center">
      <mat-spinner diameter="40"></mat-spinner>
    </div>
  } @else if (previewError()) {
    <p class="error-msg">{{ previewError() }}</p>
  } @else if (preview(); as p) {
    <div class="period-row">
      <mat-icon>date_range</mat-icon>
      <span>{{ p.startDate | date:'dd/MM/yyyy' }} — {{ p.endDate | date:'dd/MM/yyyy' }}</span>
    </div>

    <form [formGroup]="form">
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Saldo de abertura (R$)</mat-label>
        <input matInput type="number" formControlName="openingBalance" step="0.01">
        <mat-hint>Sugerido: {{ p.suggestedOpeningBalance | currency:'BRL' }}</mat-hint>
      </mat-form-field>
    </form>

    @if ((p.recurringItems?.length ?? 0) > 0) {
      <h4 class="section-title">Itens recorrentes ({{ p.recurringItems!.length }})</h4>
      <div class="item-list">
        @for (item of p.recurringItems; track item.description) {
          <div class="preview-item">
            <span class="desc">{{ item.description }}</span>
            <span [class.income]="item.type === 'INCOME'" [class.expense]="item.type === 'EXPENSE'">
              {{ item.amount | currency:'BRL' }}
            </span>
          </div>
        }
      </div>
    }

    @if ((p.installmentItems?.length ?? 0) > 0) {
      <h4 class="section-title">Parcelas do cartão ({{ p.installmentItems!.length }})</h4>
      <div class="item-list">
        @for (item of p.installmentItems; track item.description) {
          <div class="preview-item">
            <span class="desc">{{ item.description }}</span>
            <span class="expense">{{ item.amount | currency:'BRL' }}</span>
          </div>
        }
      </div>
    }

    <div class="totals-row">
      <span>Receita planejada: <strong class="income">{{ p.projectedIncome | currency:'BRL' }}</strong></span>
      <span>Despesa planejada: <strong class="expense">{{ p.projectedExpense | currency:'BRL' }}</strong></span>
    </div>
  }
</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button (click)="cancel()" [disabled]="saving()">Cancelar</button>
  <button mat-flat-button color="primary"
          [disabled]="isInvalid() || saving() || loadingPreview() || !!previewError()"
          (click)="confirm()">
    {{ saving() ? 'Abrindo...' : 'Abrir ciclo' }}
  </button>
</mat-dialog-actions>
```

- [ ] **Step 3: Criar o SCSS**

```scss
// budget-cycle-open-dialog.scss
.loading-center {
  display: flex;
  justify-content: center;
  padding: 32px;
}

.error-msg {
  color: var(--mat-sys-error);
}

.period-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-size: 1rem;
  font-weight: 500;
}

.full-width { width: 100%; }

.section-title {
  margin: 16px 0 8px;
  font-size: 0.875rem;
  color: var(--mat-sys-on-surface-variant);
}

.item-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 8px;
}

.preview-item {
  display: flex;
  justify-content: space-between;
  font-size: 0.875rem;
  padding: 4px 0;
  border-bottom: 1px solid var(--mat-sys-outline-variant);

  .desc { color: var(--mat-sys-on-surface); }
}

.totals-row {
  display: flex;
  justify-content: space-between;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 2px solid var(--mat-sys-outline-variant);
  font-size: 0.9rem;
}

.income { color: var(--mat-sys-primary); }
.expense { color: var(--mat-sys-error); }
```

- [ ] **Step 4: Adicionar `openCycle()` wrapper ao PlanningService**

No `planning.service.ts`, adicionar:

```ts
import { BudgetCycleOpenRequest, BudgetCycleResponse } from '../../../core/api/fintechSaaSAPI.schemas';

openCycle(req: BudgetCycleOpenRequest): Observable<BudgetCycleResponse> {
  return this.budget.openBudgetCycle(req);
}
```

- [ ] **Step 5: Verificar TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -30
```

Esperado: zero erros.

- [ ] **Step 6: Commit**

```bash
cd ..
git add frontend/src/app/features/planning/budget-cycle-open-dialog/ \
        frontend/src/app/features/planning/planning.service.ts
git commit -m "feat(planning): diálogo de abertura de ciclo com preview antes de confirmar"
```

---

## Task 9: Verificação final

- [ ] **Step 1: Rodar todos os testes backend**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -10
```

Esperado: `BUILD SUCCESS`, zero falhas.

- [ ] **Step 2: Rodar todos os testes frontend**

```bash
cd frontend && npm test 2>&1 | tail -10
```

Esperado: todos passando.

- [ ] **Step 3: Build Angular**

```bash
cd frontend && npm run build 2>&1 | grep -E "ERROR|error|complete"
```

Esperado: `Application bundle generation complete.`

- [ ] **Step 4: Checklist funcional**

Subir backend + frontend e verificar:

- [ ] Ciclo OPEN com datas cobrindo hoje → `remainingDays` correto (≤ duração do ciclo), nunca 76+ dias
- [ ] Ciclo OPEN cujo `endDate` passou → próxima requisição transita para ENDED automaticamente
- [ ] Ciclo ENDED → banner visível, card "Disponível" oculto, botão "Fechar ciclo definitivamente" presente
- [ ] Botão "Fechar ciclo" em ENDED → funciona; ciclo fica CLOSED
- [ ] Sem ciclo ativo → bloco "nenhum ciclo" + botão "Abrir novo ciclo" visível
- [ ] Botão "Abrir novo ciclo" → abre dialog com preview (datas, itens, saldo sugerido)
- [ ] Editar saldo de abertura no dialog → POST usa o valor editado
- [ ] `POST /api/budget-cycles` com `referenceMonth` que não inclui hoje → 422

- [ ] **Step 5: Commit final se houver ajustes**

```bash
git add -p  # apenas arquivos modificados nos ajustes
git commit -m "fix(planning): ajustes pós-verificação do modelo de estados de ciclo"
```
