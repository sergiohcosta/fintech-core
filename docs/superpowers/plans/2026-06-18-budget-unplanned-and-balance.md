# Budget: Transações Não Planejadas e Saldo Disponível

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exibir transações do ciclo sem budget_item vinculado e corrigir o cálculo de `currentBalance` e `availableToSpend` para refletir a realidade financeira, não apenas o que foi manualmente vinculado.

**Architecture:** A query `findUnplannedByCycle` busca transações do período que não têm `budget_item.transaction_id` apontando para elas. O backend monta o `BudgetCycleResponseDTO` com os novos campos; o frontend passa a consumir o `summary` da resposta (em vez de recalcular localmente) e renderiza a seção "Não planejados" com botão de link inverso (transação → budget_item).

**Tech Stack:** Java 21 / Spring Boot 4 / JPA + JPQL / Angular 21 Zoneless / Signals / Orval / Angular Material 3 / Vitest

## Global Constraints

- Mensagens de commit em português, imperativo, **sem Co-Authored-By**
- Nunca usar `any` no TypeScript — usar `unknown` e narrowing
- Projeto Angular é Zoneless — apenas Signals/computed/toSignal, nunca depender de Zone.js
- Spec-first: mudanças de contrato sempre em `api-spec/openapi.yaml` antes do frontend consumir
- Tenant isolation: toda query backend deve filtrar por `tenant`
- Testes de repositório/service com JUnit 5 + Mockito (padrão do projeto); JPQL complexo pode ser verificado manualmente rodando o app

---

## Mapa de arquivos

| Arquivo | Ação |
|---------|------|
| `backend/.../repository/TransactionRepository.java` | Modificar — nova query `findUnplannedByCycle` |
| `backend/.../dto/budget/BudgetCycleSummaryDTO.java` | Modificar — adicionar `unplannedIncome`, `unplannedExpense`, `availableToSpend` |
| `backend/.../dto/budget/BudgetCycleResponseDTO.java` | Modificar — adicionar `unplannedTransactions`, corrigir `buildSummary` |
| `backend/.../service/BudgetCycleService.java` | Modificar — novo método `toResponseDTO` que busca não planejados |
| `backend/.../controller/BudgetCycleController.java` | Modificar — trocar `fromEntity(c, listItems(c))` por `toResponseDTO(c)` |
| `backend/.../service/BudgetCycleServiceTest.java` | Modificar — testes para `toResponseDTO` |
| `api-spec/openapi.yaml` | Modificar — novos campos em `BudgetCycleSummary` e `BudgetCycleResponse` |
| `frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.ts` | Modificar — remover `buildSummary`, exportar `DEFAULT_SUMMARY` |
| `frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.spec.ts` | Modificar — atualizar testes |
| `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.ts` | Modificar — usar summary do backend, add `unplannedItems`, reload após link/unlink |
| `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html` | Modificar — seção "Não planejados" |
| `frontend/src/app/features/planning/link-budget-item-dialog/link-budget-item-dialog.ts` | Criar — dialog de link inverso (transação → budget_item) |
| `frontend/src/app/features/planning/link-budget-item-dialog/link-budget-item-dialog.html` | Criar — template do dialog |
| `frontend/src/app/features/planning/link-transaction-dialog/link-transaction-dialog.ts` | Modificar — adicionar `startDate`/`endDate` no filtro |

---

## Task 1 — Backend: query `findUnplannedByCycle` no TransactionRepository

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`
- Modify: `backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java`

**Interfaces:**
- Produces: `List<Transaction> findUnplannedByCycle(Tenant, BudgetCycle, LocalDate, LocalDate, TransactionType, TransactionStatus)`

- [ ] **Step 1: Escrever o teste que verifica que o service chama a query com os parâmetros corretos**

Em `BudgetCycleServiceTest.java`, adicione ao final da classe (antes do `}`):

```java
// ---- toResponseDTO ----

@Test
@DisplayName("toResponseDTO chama findUnplannedByCycle com os parâmetros do ciclo")
void toResponseDTO_chamaQueryUnplannedComParametrosCorretos() {
    Tenant tenant = new Tenant();
    BudgetCycle cycle = BudgetCycle.builder()
        .id(UUID.randomUUID())
        .tenant(tenant)
        .startDate(LocalDate.of(2026, 6, 1))
        .endDate(LocalDate.of(2026, 6, 30))
        .openingBalance(BigDecimal.valueOf(5000))
        .status(BudgetCycleStatus.OPEN)
        .build();

    when(itemRepository.findAllByCycleWithDetails(cycle)).thenReturn(List.of());
    when(transactionRepository.findUnplannedByCycle(
        eq(tenant), eq(cycle),
        eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)),
        eq(TransactionType.TRANSFER), eq(TransactionStatus.CANCELLED)
    )).thenReturn(List.of());

    service.toResponseDTO(cycle);

    verify(transactionRepository).findUnplannedByCycle(
        tenant, cycle,
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
        TransactionType.TRANSFER, TransactionStatus.CANCELLED
    );
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha (método não existe ainda)**

```bash
cd backend && ./mvnw test -pl . -Dtest=BudgetCycleServiceTest#toResponseDTO_chamaQueryUnplannedComParametrosCorretos -q 2>&1 | tail -15
```
Esperado: FAIL — `toResponseDTO` não existe.

- [ ] **Step 3: Adicionar a query ao TransactionRepository**

Em `TransactionRepository.java`, adicione após o último método:

```java
// Retorna transações do período do ciclo que não têm nenhum BudgetItem
// apontando para elas naquele ciclo. Aplica a mesma regra de data dos filtros:
// parcelas com fatura → invoice.dueDate; demais → t.date.
// TRANSFER excluído (movimento interno); CANCELLED excluído (não impacta saldo).
@Query("""
    SELECT t FROM Transaction t
    LEFT JOIN FETCH t.category
    LEFT JOIN FETCH t.account
    LEFT JOIN FETCH t.invoice inv
    WHERE t.tenant = :tenant
      AND t.type <> :transferType
      AND t.status <> :cancelledStatus
      AND (
        (t.installmentGroup IS NOT NULL AND inv IS NOT NULL
          AND inv.dueDate BETWEEN :start AND :end)
        OR
        ((t.installmentGroup IS NULL OR inv IS NULL)
          AND t.date BETWEEN :start AND :end)
      )
      AND NOT EXISTS (
        SELECT 1 FROM BudgetItem bi
        WHERE bi.transaction = t AND bi.cycle = :cycle
      )
    ORDER BY t.date DESC
    """)
List<Transaction> findUnplannedByCycle(
    @Param("tenant")          Tenant tenant,
    @Param("cycle")           BudgetCycle cycle,
    @Param("start")           LocalDate start,
    @Param("end")             LocalDate end,
    @Param("transferType")    TransactionType transferType,
    @Param("cancelledStatus") TransactionStatus cancelledStatus
);
```

- [ ] **Step 4: Rodar o teste e confirmar que passa**

```bash
cd backend && ./mvnw test -pl . -Dtest=BudgetCycleServiceTest#toResponseDTO_chamaQueryUnplannedComParametrosCorretos -q 2>&1 | tail -10
```
Esperado: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/repository/TransactionRepository.java \
        backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java
git commit -m "adiciona query findUnplannedByCycle no TransactionRepository"
```

---

## Task 2 — Backend: novos campos nos DTOs e correção do buildSummary

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/dto/budget/BudgetCycleSummaryDTO.java`
- Modify: `backend/src/main/java/com/fintech/api/dto/budget/BudgetCycleResponseDTO.java`

**Interfaces:**
- Consumes: `findUnplannedByCycle` (Task 1)
- Produces:
  - `BudgetCycleSummaryDTO(plannedIncome, plannedExpense, projectedBalance, realizedIncome, realizedExpense, currentBalance, pendingCount, unplannedIncome, unplannedExpense, availableToSpend)`
  - `BudgetCycleResponseDTO.fromEntity(BudgetCycle, List<BudgetItem>, List<Transaction>)`

**Fórmulas:**

```
currentBalance   = openingBalance + realizedIncome + unplannedIncome
                                  - realizedExpense - unplannedExpense

availableToSpend = currentBalance - (plannedExpense - realizedExpense)
```

- [ ] **Step 1: Atualizar BudgetCycleSummaryDTO**

Substituir o conteúdo completo de `BudgetCycleSummaryDTO.java`:

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
    long       pendingCount,
    BigDecimal unplannedIncome,
    BigDecimal unplannedExpense,
    BigDecimal availableToSpend
) {}
```

- [ ] **Step 2: Atualizar BudgetCycleResponseDTO**

Substituir o conteúdo completo de `BudgetCycleResponseDTO.java`:

```java
package com.fintech.api.dto.budget;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
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
    List<BudgetItemResponseDTO> items,
    List<TransactionResponseDTO> unplannedTransactions
) {
    public static BudgetCycleResponseDTO fromEntity(
            BudgetCycle cycle,
            List<BudgetItem> items,
            List<Transaction> unplanned) {

        List<BudgetItemResponseDTO> itemDTOs = items.stream()
            .map(BudgetItemResponseDTO::fromEntity)
            .toList();
        List<TransactionResponseDTO> unplannedDTOs = unplanned.stream()
            .map(TransactionResponseDTO::fromEntity)
            .toList();

        return new BudgetCycleResponseDTO(
            cycle.getId(),
            cycle.getStartDate(),
            cycle.getEndDate(),
            cycle.getOpeningBalance(),
            cycle.getStatus(),
            buildSummary(items, unplanned, cycle.getOpeningBalance()),
            itemDTOs,
            unplannedDTOs
        );
    }

    private static BudgetCycleSummaryDTO buildSummary(
            List<BudgetItem> items,
            List<Transaction> unplanned,
            BigDecimal openingBalance) {

        BigDecimal plannedIncome   = BigDecimal.ZERO;
        BigDecimal plannedExpense  = BigDecimal.ZERO;
        BigDecimal realizedIncome  = BigDecimal.ZERO;
        BigDecimal realizedExpense = BigDecimal.ZERO;
        long pendingCount = 0;

        for (BudgetItem item : items) {
            boolean isIncome = item.getType() == TransactionType.INCOME;
            if (isIncome) plannedIncome  = plannedIncome.add(item.getAmount());
            else          plannedExpense = plannedExpense.add(item.getAmount());

            if (item.getStatus() == BudgetItemStatus.REALIZED) {
                if (isIncome) realizedIncome  = realizedIncome.add(item.getAmount());
                else          realizedExpense = realizedExpense.add(item.getAmount());
            }
            if (item.getStatus() == BudgetItemStatus.PENDING) pendingCount++;
        }

        BigDecimal unplannedIncome  = BigDecimal.ZERO;
        BigDecimal unplannedExpense = BigDecimal.ZERO;
        for (Transaction t : unplanned) {
            if (t.getType() == TransactionType.INCOME)
                unplannedIncome  = unplannedIncome.add(t.getAmount());
            else
                unplannedExpense = unplannedExpense.add(t.getAmount());
        }

        BigDecimal currentBalance = openingBalance
            .add(realizedIncome).add(unplannedIncome)
            .subtract(realizedExpense).subtract(unplannedExpense);

        BigDecimal pendingPlannedExpense = plannedExpense.subtract(realizedExpense);
        BigDecimal availableToSpend = currentBalance.subtract(pendingPlannedExpense);

        return new BudgetCycleSummaryDTO(
            plannedIncome,
            plannedExpense,
            openingBalance.add(plannedIncome).subtract(plannedExpense),
            realizedIncome,
            realizedExpense,
            currentBalance,
            pendingCount,
            unplannedIncome,
            unplannedExpense,
            availableToSpend
        );
    }
}
```

> **Atenção:** `TransactionResponseDTO.fromEntity(Transaction)` — confirme que esse método estático existe. Se não existir, use o construtor/mapper já presente no projeto. Não crie um novo se já houver.

- [ ] **Step 3: Verificar que o projeto compila**

```bash
cd backend && ./mvnw compile -q 2>&1 | tail -20
```
Esperado: BUILD SUCCESS. Se houver erro em algum caller de `fromEntity`, corrija na próxima task.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/dto/budget/BudgetCycleSummaryDTO.java \
        backend/src/main/java/com/fintech/api/dto/budget/BudgetCycleResponseDTO.java
git commit -m "corrige cálculo de currentBalance e adiciona unplannedTransactions no BudgetCycleResponseDTO"
```

---

## Task 3 — Backend: BudgetCycleService.toResponseDTO + wiring no controller

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/BudgetCycleService.java`
- Modify: `backend/src/main/java/com/fintech/api/controller/BudgetCycleController.java`
- Modify: `backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java`

**Interfaces:**
- Consumes: `findUnplannedByCycle` (Task 1), `BudgetCycleResponseDTO.fromEntity(cycle, items, unplanned)` (Task 2)
- Produces: `BudgetCycleService.toResponseDTO(BudgetCycle): BudgetCycleResponseDTO`

- [ ] **Step 1: Adicionar `toResponseDTO` no BudgetCycleService**

Após o método `listItems(BudgetCycle cycle)` no `BudgetCycleService.java`, adicione:

```java
/**
 * Monta o DTO completo do ciclo, incluindo transações não vinculadas a nenhum budget_item.
 * Centraliza a chamada de fromEntity para evitar duplicação nos callers do controller.
 */
@Transactional(readOnly = true)
public BudgetCycleResponseDTO toResponseDTO(BudgetCycle cycle) {
    List<BudgetItem> items = itemRepository.findAllByCycleWithDetails(cycle);
    List<Transaction> unplanned = transactionRepository.findUnplannedByCycle(
        cycle.getTenant(), cycle,
        cycle.getStartDate(), cycle.getEndDate(),
        TransactionType.TRANSFER, TransactionStatus.CANCELLED
    );
    return BudgetCycleResponseDTO.fromEntity(cycle, items, unplanned);
}
```

- [ ] **Step 2: Atualizar o controller para usar `toResponseDTO`**

Em `BudgetCycleController.java`, substituir **todas** as ocorrências de:
```java
BudgetCycleResponseDTO.fromEntity(c, cycleService.listItems(c))
```
por:
```java
cycleService.toResponseDTO(c)
```

São 5 ocorrências: nos métodos `list` (dentro do `.map`), `open`, `current`, `get`, `close`, `syncInstallments`.

No método `list`, o `.map` fica:
```java
.map(c -> cycleService.toResponseDTO(c))
```

- [ ] **Step 3: Verificar que o projeto compila e os testes existentes passam**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -20
```
Esperado: BUILD SUCCESS. Se algum teste existente quebrar por mudança de assinatura, corrija-o.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/BudgetCycleService.java \
        backend/src/main/java/com/fintech/api/controller/BudgetCycleController.java \
        backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java
git commit -m "extrai toResponseDTO no BudgetCycleService e atualiza controller"
```

---

## Task 4 — OpenAPI spec + Orval

**Files:**
- Modify: `api-spec/openapi.yaml`
- Run: `cd frontend && npm run generate` (Orval)
- Commit: arquivos gerados em `frontend/src/app/core/api/`

**Interfaces:**
- Produces: tipos TypeScript `BudgetCycleSummary` e `BudgetCycleResponse` atualizados com novos campos

- [ ] **Step 1: Atualizar BudgetCycleSummary no openapi.yaml**

Localizar `BudgetCycleSummary:` (linha ~650) e substituir o bloco pelo seguinte (mantém campos existentes, adiciona 3 novos ao final):

```yaml
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
        unplannedIncome:
          type: number
          format: double
        unplannedExpense:
          type: number
          format: double
        availableToSpend:
          type: number
          format: double
```

- [ ] **Step 2: Adicionar `unplannedTransactions` ao BudgetCycleResponse no openapi.yaml**

Localizar `BudgetCycleResponse:` (linha ~717) e adicionar após o campo `items:` existente:

```yaml
        unplannedTransactions:
          type: array
          items:
            $ref: '#/components/schemas/TransactionResponseDTO'
```

> Confirme o nome exato do schema de transação no spec (procure por `TransactionResponseDTO:` no arquivo). Use o mesmo nome que está definido lá.

- [ ] **Step 3: Regenerar tipos com Orval**

```bash
cd /home/sergio/fintech-core/.worktrees/feature-budget/frontend && npm run generate 2>&1 | tail -20
```
Esperado: arquivos gerados em `src/app/core/api/` sem erros.

- [ ] **Step 4: Confirmar novos campos no schema gerado**

```bash
grep -n "unplannedIncome\|unplannedExpense\|availableToSpend\|unplannedTransactions" \
  /home/sergio/fintech-core/.worktrees/feature-budget/frontend/src/app/core/api/fintechSaaSAPI.schemas.ts
```
Esperado: 4 linhas encontradas.

- [ ] **Step 5: Commit**

```bash
git add api-spec/openapi.yaml \
        frontend/src/app/core/api/
git commit -m "atualiza OpenAPI spec com unplannedTransactions e availableToSpend; regenera Orval"
```

---

## Task 5 — Frontend: simplificar utils e usar summary do backend

**Files:**
- Modify: `frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.ts`
- Modify: `frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.spec.ts`
- Modify: `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.ts`

**Interfaces:**
- Consumes: `BudgetCycleSummary` gerado pelo Orval (Task 4)
- Produces: `DEFAULT_SUMMARY: BudgetCycleSummary` exportado de `budget-cycle.utils.ts`

**Motivação:** O backend agora calcula o `summary` corretamente (incluindo não planejados). O frontend não precisa mais recalcular localmente — só consome o que vem da API. Isso elimina duplicação e garante que link/unlink sempre reflitam o estado real (pois disparam `loadCurrentCycle()`).

- [ ] **Step 1: Reescrever budget-cycle.utils.ts**

Substituir o conteúdo completo:

```typescript
import { BudgetCycleSummary } from '../../../core/api/fintechSaaSAPI.schemas';

export const DEFAULT_SUMMARY: BudgetCycleSummary = {
  plannedIncome:    0,
  plannedExpense:   0,
  projectedBalance: 0,
  realizedIncome:   0,
  realizedExpense:  0,
  currentBalance:   0,
  pendingCount:     0,
  unplannedIncome:  0,
  unplannedExpense: 0,
  availableToSpend: 0,
};
```

- [ ] **Step 2: Atualizar budget-cycle.utils.spec.ts**

Substituir o conteúdo completo:

```typescript
import { describe, it, expect } from 'vitest';
import { DEFAULT_SUMMARY } from './budget-cycle.utils';

describe('DEFAULT_SUMMARY', () => {
  it('tem todos os campos zerados', () => {
    expect(DEFAULT_SUMMARY.plannedIncome).toBe(0);
    expect(DEFAULT_SUMMARY.plannedExpense).toBe(0);
    expect(DEFAULT_SUMMARY.projectedBalance).toBe(0);
    expect(DEFAULT_SUMMARY.currentBalance).toBe(0);
    expect(DEFAULT_SUMMARY.unplannedIncome).toBe(0);
    expect(DEFAULT_SUMMARY.unplannedExpense).toBe(0);
    expect(DEFAULT_SUMMARY.availableToSpend).toBe(0);
    expect(DEFAULT_SUMMARY.pendingCount).toBe(0);
  });
});
```

- [ ] **Step 3: Atualizar BudgetCycleCurrentComponent**

Substituir as linhas relevantes em `budget-cycle-current.ts`:

**Importações — remover `buildSummary`, adicionar `DEFAULT_SUMMARY`:**
```typescript
import { DEFAULT_SUMMARY } from './budget-cycle.utils';
```

**Remover o import de `BudgetItemResponse` se não for mais usado após as mudanças (verifique).**

**Substituir o computed `summary` e adicionar `unplannedItems`:**
```typescript
readonly summary = computed(() => this.cycle()?.summary ?? DEFAULT_SUMMARY);
readonly unplannedItems = computed(() => this.cycle()?.unplannedTransactions ?? []);
```

**Atualizar `replaceItem` e os métodos `linkTransaction` / `unlinkTransaction` para recarregar o ciclo completo** (pois uma transação sai da lista de não planejados quando vinculada):

Remova o método `replaceItem` e substitua as chamadas `.subscribe({ next: updated => this.replaceItem(updated) })` nos métodos `linkTransaction` e `unlinkTransaction` por:

```typescript
linkTransaction(item: BudgetItemResponse): void {
  const cycleId = this.cycle()?.id;
  if (!cycleId) return;
  const ref = this.dialog.open(LinkTransactionDialogComponent, {
    width: '600px',
    data: {
      item,
      cycleId,
      startDate: this.cycle()!.startDate!,
      endDate: this.cycle()!.endDate!,
    } satisfies LinkTransactionDialogData,
  });
  ref.afterClosed().subscribe((transactionId?: string) => {
    if (!transactionId) return;
    this.planningService.linkItem(item.id!, { transactionId }).subscribe({
      next: () => this.loadCurrentCycle(),
      error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
    });
  });
}

unlinkTransaction(item: BudgetItemResponse): void {
  this.planningService.unlinkItem(item.id!).subscribe({
    next: () => this.loadCurrentCycle(),
    error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
  });
}
```

**Adicionar método `loadCurrentCycle` como `public` (era `private`) para ser chamável em deleteItem também:**

No método `deleteItem`, substituir `this.items.update(...)` por `this.loadCurrentCycle()` — opcional, mas mantém consistência.

- [ ] **Step 4: Rodar os testes e confirmar que passam**

```bash
cd /home/sergio/fintech-core/.worktrees/feature-budget/frontend && npm test -- --reporter=verbose 2>&1 | grep -E "PASS|FAIL|✓|✗" | head -30
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/planning/budget-cycle-current/
git commit -m "simplifica utils de budget: usa summary do backend, remove recálculo local"
```

---

## Task 6 — Frontend: seção "Não planejados" no template

**Files:**
- Modify: `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html`
- Modify: `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.ts`

**Interfaces:**
- Consumes: `unplannedItems` computed (Task 5), `LinkBudgetItemDialogComponent` (Task 7 — adicione o import após criar o componente)

- [ ] **Step 1: Adicionar método `linkFromUnplanned` no componente**

Em `budget-cycle-current.ts`, adicionar o import do `TransactionResponseDTO` se não existir, e adicionar o método:

```typescript
linkFromUnplanned(tx: TransactionResponseDTO): void {
  const pendingItems = this.items().filter(
    i => i.type === tx.type && i.status === 'PENDING'
  );
  if (pendingItems.length === 0) {
    this.snackBar.open(
      'Nenhum item planejado pendente do mesmo tipo para vincular.',
      'OK',
      { duration: 3000 }
    );
    return;
  }
  const ref = this.dialog.open(LinkBudgetItemDialogComponent, {
    width: '600px',
    data: { transaction: tx, pendingItems } satisfies LinkBudgetItemDialogData,
  });
  ref.afterClosed().subscribe((itemId?: string) => {
    if (!itemId) return;
    this.planningService.linkItem(itemId, { transactionId: tx.id! }).subscribe({
      next: () => {
        this.loadCurrentCycle();
        this.snackBar.open('Vinculado com sucesso.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
    });
  });
}
```

> Adicione `LinkBudgetItemDialogComponent` e `LinkBudgetItemDialogData` aos imports do componente **após criar o arquivo na Task 7**. Por ora, deixe o import comentado `// TODO: import após Task 7`.

- [ ] **Step 2: Adicionar seção no template HTML**

No arquivo `budget-cycle-current.html`, após a seção de parcelas (ou no local semanticamente adequado), adicione:

```html
@if (unplannedItems().length > 0) {
  <mat-card class="budget-section unplanned-section">
    <mat-card-header>
      <mat-card-title>
        <mat-icon color="warn">warning_amber</mat-icon>
        Não planejados ({{ unplannedItems().length }})
      </mat-card-title>
      <mat-card-subtitle>
        Transações do período sem item de planejamento vinculado
      </mat-card-subtitle>
    </mat-card-header>
    <mat-card-content>
      <mat-table [dataSource]="unplannedItems()">

        <ng-container matColumnDef="date">
          <mat-header-cell *matHeaderCellDef>Data</mat-header-cell>
          <mat-cell *matCellDef="let tx">{{ tx.date | date:'dd/MM' }}</mat-cell>
        </ng-container>

        <ng-container matColumnDef="description">
          <mat-header-cell *matHeaderCellDef>Descrição</mat-header-cell>
          <mat-cell *matCellDef="let tx">{{ tx.description }}</mat-cell>
        </ng-container>

        <ng-container matColumnDef="account">
          <mat-header-cell *matHeaderCellDef>Conta</mat-header-cell>
          <mat-cell *matCellDef="let tx">{{ tx.accountName }}</mat-cell>
        </ng-container>

        <ng-container matColumnDef="amount">
          <mat-header-cell *matHeaderCellDef>Valor</mat-header-cell>
          <mat-cell *matCellDef="let tx"
                    [class.income]="tx.type === 'INCOME'"
                    [class.expense]="tx.type === 'EXPENSE'">
            {{ tx.amount | currency:'BRL':'symbol':'1.2-2' }}
          </mat-cell>
        </ng-container>

        <ng-container matColumnDef="actions">
          <mat-header-cell *matHeaderCellDef></mat-header-cell>
          <mat-cell *matCellDef="let tx">
            <button mat-icon-button
                    matTooltip="Vincular a item planejado"
                    (click)="linkFromUnplanned(tx)">
              <mat-icon>link</mat-icon>
            </button>
          </mat-cell>
        </ng-container>

        <mat-header-row *matHeaderRowDef="['date','description','account','amount','actions']"></mat-header-row>
        <mat-row *matRowDef="let row; columns: ['date','description','account','amount','actions']"></mat-row>
      </mat-table>

      <div class="unplanned-totals">
        @if (summary().unplannedExpense > 0) {
          <span class="expense">
            Despesas não planejadas:
            {{ summary().unplannedExpense | currency:'BRL':'symbol':'1.2-2' }}
          </span>
        }
        @if (summary().unplannedIncome > 0) {
          <span class="income">
            Receitas não planejadas:
            {{ summary().unplannedIncome | currency:'BRL':'symbol':'1.2-2' }}
          </span>
        }
      </div>
    </mat-card-content>
  </mat-card>
}
```

- [ ] **Step 3: Verificar que o projeto compila (TypeScript)**

```bash
cd /home/sergio/fintech-core/.worktrees/feature-budget/frontend && npx tsc --noEmit 2>&1 | head -20
```
Esperado: sem erros (exceto possivelmente o import comentado do Task 7).

- [ ] **Step 4: Commit (parcial — link inverso será completado na Task 7)**

```bash
git add frontend/src/app/features/planning/budget-cycle-current/
git commit -m "adiciona seção de transações não planejadas no ciclo de budget"
```

---

## Task 7 — Frontend: LinkBudgetItemDialogComponent

**Files:**
- Create: `frontend/src/app/features/planning/link-budget-item-dialog/link-budget-item-dialog.ts`
- Create: `frontend/src/app/features/planning/link-budget-item-dialog/link-budget-item-dialog.html`

**Interfaces:**
- Consumes: `BudgetItemResponse`, `TransactionResponseDTO` (tipos Orval)
- Produces: dialog que retorna `string` (itemId) ou `undefined`

- [ ] **Step 1: Criar link-budget-item-dialog.ts**

```typescript
import { Component, inject } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { BudgetItemResponse, TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export interface LinkBudgetItemDialogData {
  transaction: TransactionResponseDTO;
  pendingItems: BudgetItemResponse[];
}

@Component({
  selector: 'app-link-budget-item-dialog',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatButtonModule, MatDialogModule, MatIconModule,
    MatTableModule, MatTooltipModule,
  ],
  templateUrl: './link-budget-item-dialog.html',
})
export class LinkBudgetItemDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<LinkBudgetItemDialogComponent>);
  readonly data: LinkBudgetItemDialogData = inject(MAT_DIALOG_DATA);

  readonly displayedColumns = ['description', 'expectedDate', 'amount', 'select'];

  select(item: BudgetItemResponse): void {
    this.dialogRef.close(item.id);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
```

- [ ] **Step 2: Criar link-budget-item-dialog.html**

```html
<h2 mat-dialog-title>Vincular ao planejamento</h2>

<mat-dialog-content>
  <p class="dialog-subtitle">
    Transação: <strong>{{ data.transaction.description }}</strong>
    — {{ data.transaction.amount | currency:'BRL':'symbol':'1.2-2' }}
  </p>

  <p>Selecione o item planejado para vincular:</p>

  <mat-table [dataSource]="data.pendingItems">

    <ng-container matColumnDef="description">
      <mat-header-cell *matHeaderCellDef>Descrição</mat-header-cell>
      <mat-cell *matCellDef="let item">{{ item.description }}</mat-cell>
    </ng-container>

    <ng-container matColumnDef="expectedDate">
      <mat-header-cell *matHeaderCellDef>Data prevista</mat-header-cell>
      <mat-cell *matCellDef="let item">{{ item.expectedDate | date:'dd/MM/yyyy' }}</mat-cell>
    </ng-container>

    <ng-container matColumnDef="amount">
      <mat-header-cell *matHeaderCellDef>Valor</mat-header-cell>
      <mat-cell *matCellDef="let item">
        {{ item.amount | currency:'BRL':'symbol':'1.2-2' }}
      </mat-cell>
    </ng-container>

    <ng-container matColumnDef="select">
      <mat-header-cell *matHeaderCellDef></mat-header-cell>
      <mat-cell *matCellDef="let item">
        <button mat-stroked-button color="primary" (click)="select(item)">
          Vincular
        </button>
      </mat-cell>
    </ng-container>

    <mat-header-row *matHeaderRowDef="displayedColumns"></mat-header-row>
    <mat-row *matRowDef="let row; columns: displayedColumns"></mat-row>
  </mat-table>
</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button (click)="cancel()">Cancelar</button>
</mat-dialog-actions>
```

- [ ] **Step 3: Descomentar o import no BudgetCycleCurrentComponent**

Em `budget-cycle-current.ts`, substituir o comentário `// TODO: import após Task 7` pela importação real:

```typescript
import { LinkBudgetItemDialogComponent, LinkBudgetItemDialogData } from '../link-budget-item-dialog/link-budget-item-dialog';
```

Adicionar `LinkBudgetItemDialogComponent` ao array `imports` do componente.

- [ ] **Step 4: Verificar TypeScript sem erros**

```bash
cd /home/sergio/fintech-core/.worktrees/feature-budget/frontend && npx tsc --noEmit 2>&1 | head -20
```
Esperado: sem erros.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/planning/link-budget-item-dialog/ \
        frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.ts
git commit -m "adiciona LinkBudgetItemDialogComponent para vincular transação não planejada a item"
```

---

## Task 8 — Frontend: filtro de data no LinkTransactionDialog

**Files:**
- Modify: `frontend/src/app/features/planning/link-transaction-dialog/link-transaction-dialog.ts`

**Problema:** Hoje o dialog busca `listTransactions({ type: itemType })` sem filtro de data, retornando todas as transações do tenant. O correto é filtrar pelo período do ciclo para evitar vincular transações de outros meses.

- [ ] **Step 1: Atualizar LinkTransactionDialogData para receber as datas**

Em `link-transaction-dialog.ts`, atualizar a interface:

```typescript
export interface LinkTransactionDialogData {
  item: BudgetItemResponse;
  cycleId: string;
  startDate: string;  // ISO date: 'YYYY-MM-DD'
  endDate: string;    // ISO date: 'YYYY-MM-DD'
}
```

- [ ] **Step 2: Usar as datas na chamada listTransactions**

Atualizar o `ngOnInit`:

```typescript
ngOnInit(): void {
  const { type: itemType } = this.data.item;
  this.txService.listTransactions({
    type: itemType,
    startDate: this.data.startDate,
    endDate: this.data.endDate,
  }).subscribe({
    next: (result: TransactionResponseDTO[]) => {
      this.transactions.set(result);
      this.loading.set(false);
    },
    error: () => this.loading.set(false),
  });
}
```

- [ ] **Step 3: Confirmar que o caller já passa startDate/endDate**

Verificar que em `budget-cycle-current.ts`, o `dialog.open(LinkTransactionDialogComponent, ...)` já passa `startDate` e `endDate` (adicionados na Task 5). Isso foi feito no `linkTransaction()` da Task 5.

Se não estiver: adicionar `startDate: this.cycle()!.startDate!` e `endDate: this.cycle()!.endDate!` ao objeto `data`.

- [ ] **Step 4: Verificar TypeScript e rodar testes**

```bash
cd /home/sergio/fintech-core/.worktrees/feature-budget/frontend && npx tsc --noEmit 2>&1 | head -10
cd /home/sergio/fintech-core/.worktrees/feature-budget/frontend && npm test -- --reporter=verbose 2>&1 | grep -E "PASS|FAIL|✓|✗" | head -20
```
Esperado: sem erros de tipo; todos os testes passando.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/planning/link-transaction-dialog/link-transaction-dialog.ts
git commit -m "filtra transações pelo período do ciclo no LinkTransactionDialog"
```

---

## Self-Review

### Spec coverage

| Requisito | Task que implementa |
|-----------|-------------------|
| Transações não planejadas (sem budget_item no ciclo) | Task 1 (query), Task 2 (DTO), Task 3 (service), Task 4 (spec), Task 5 (signal), Task 6 (UI) |
| `currentBalance` corrigido para incluir transações reais | Task 2 (fórmula no buildSummary) |
| `availableToSpend` no summary | Task 2 (DTO), Task 4 (spec) |
| Botão de link inverso (transação não planejada → item planejado) | Task 6 (método), Task 7 (dialog) |
| Filtro de data no LinkTransactionDialog | Task 8 |

### Dependências entre tasks

```
Task 1 → Task 3 (service usa a query)
Task 2 → Task 3 (controller usa o novo fromEntity)
Task 4 → Task 5 (Orval gera tipos usados no frontend)
Task 5 → Task 6 (unplannedItems computed)
Task 6 → Task 7 (LinkBudgetItemDialogComponent importado no componente)
Tasks 1-3 podem rodar em paralelo com Tasks 4-8 (backend independente do frontend)
```
