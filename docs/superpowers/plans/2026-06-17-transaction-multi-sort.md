# Multi-Sort na Lista de Transações — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir ordenação multi-coluna na lista de transações via click (troca critério primário) e Shift+click (adiciona critério secundário), funcionando em todos os modos de visualização (plano, por período, por fatura).

**Architecture:** Toda a lógica de sort é cliente-side (sem mudança no backend). Funções puras `sortTransactions`, `applySort`, `getSortInfo` em `transaction-list.utils.ts` — testáveis com Vitest sem Angular. O componente mantém `sortCriteria = signal<SortCriterion[]>([{col:'date', dir:'desc'}])` e passa para `buildDisplayRows`, que aplica sort dentro de cada bucket antes de construir as linhas.

**Tech Stack:** Angular 21 Zoneless, Signals, Vitest 4.x, `TransactionResponseDTO` do Orval-generated schema.

**Spec:** `docs/superpowers/specs/2026-06-17-transaction-multi-sort-design.md`

---

## Mapa de Arquivos

| Arquivo | Ação | Responsabilidade |
|---------|------|-----------------|
| `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts` | Modificar | Adicionar tipos, `sortTransactions`, `applySort`, `getSortInfo`; atualizar `buildDisplayRows` e `buildDisplayRowsGroupedByInvoice` |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.spec.ts` | Criar | Testes de `sortTransactions`, `applySort`, `getSortInfo` |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.ts` | Modificar | Adicionar `sortCriteria` signal, `onSortClick`, `sortInfo`; atualizar `displayRows` computed |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.html` | Modificar | 7 headers de coluna viram botões sort com indicadores visuais |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.scss` | Modificar | Estilos de `.sort-header`, `.sort-badge`, `.sort-arrow` |

---

## Task 1: Utilitários puros de sort (TDD)

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.spec.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`

- [ ] **Step 1.1: Criar arquivo de testes**

Crie `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.spec.ts` com o conteúdo abaixo. As funções testadas ainda não existem — os testes vão falhar:

```ts
import { describe, it, expect } from 'vitest';
import { sortTransactions, applySort, getSortInfo } from './transaction-list.utils';
import type { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

function tx(overrides: Partial<TransactionResponseDTO> = {}): TransactionResponseDTO {
  return {
    id: 'id-1',
    description: 'Test',
    amount: 100,
    date: '2024-01-15',
    type: 'EXPENSE',
    status: 'PENDING',
    ...overrides,
  } as TransactionResponseDTO;
}

describe('sortTransactions', () => {
  it('retorna a mesma ordem quando criteria está vazio', () => {
    const txs = [tx({ id: 'a' }), tx({ id: 'b' })];
    expect(sortTransactions(txs, []).map(t => t.id)).toEqual(['a', 'b']);
  });

  it('ordena por amount asc', () => {
    const txs = [tx({ id: 'a', amount: 300 }), tx({ id: 'b', amount: 100 }), tx({ id: 'c', amount: 200 })];
    expect(sortTransactions(txs, [{ col: 'amount', dir: 'asc' }]).map(t => t.id)).toEqual(['b', 'c', 'a']);
  });

  it('ordena por amount desc', () => {
    const txs = [tx({ id: 'a', amount: 100 }), tx({ id: 'b', amount: 300 })];
    expect(sortTransactions(txs, [{ col: 'amount', dir: 'desc' }]).map(t => t.id)).toEqual(['b', 'a']);
  });

  it('usa invoiceDueDate para parcelas de cartão no sort de data', () => {
    // effectiveSortDate(a) = '2024-02-15', effectiveSortDate(b) = '2024-01-20' → a > b em asc
    const a = tx({ id: 'a', date: '2024-01-10', installmentGroupId: 'g1', invoiceDueDate: '2024-02-15' });
    const b = tx({ id: 'b', date: '2024-01-20' });
    expect(sortTransactions([a, b], [{ col: 'date', dir: 'asc' }]).map(t => t.id)).toEqual(['b', 'a']);
  });

  it('ordena por status: PENDING < PAID < CANCELLED', () => {
    const txs = [
      tx({ id: 'a', status: 'CANCELLED' }),
      tx({ id: 'b', status: 'PAID' }),
      tx({ id: 'c', status: 'PENDING' }),
    ];
    expect(sortTransactions(txs, [{ col: 'status', dir: 'asc' }]).map(t => t.id)).toEqual(['c', 'b', 'a']);
  });

  it('ordena por type: INCOME < EXPENSE < transferência', () => {
    const txs = [
      tx({ id: 'a', type: 'EXPENSE' }),
      tx({ id: 'b', type: 'INCOME' }),
      tx({ id: 'c', type: 'EXPENSE', transferId: 'tr1' }),
    ];
    expect(sortTransactions(txs, [{ col: 'type', dir: 'asc' }]).map(t => t.id)).toEqual(['b', 'a', 'c']);
  });

  it('multi-critério: status asc depois amount desc', () => {
    const txs = [
      tx({ id: 'a', status: 'PENDING', amount: 100 }),
      tx({ id: 'b', status: 'PENDING', amount: 200 }),
      tx({ id: 'c', status: 'PAID',    amount: 50  }),
    ];
    expect(
      sortTransactions(txs, [{ col: 'status', dir: 'asc' }, { col: 'amount', dir: 'desc' }]).map(t => t.id)
    ).toEqual(['b', 'a', 'c']);
  });

  it('coloca category null ao final (asc)', () => {
    const txs = [
      tx({ id: 'a', categoryName: undefined }),
      tx({ id: 'b', categoryName: 'Alimentação' }),
    ];
    expect(sortTransactions(txs, [{ col: 'category', dir: 'asc' }]).map(t => t.id)).toEqual(['b', 'a']);
  });

  it('coloca account null ao final (asc)', () => {
    const txs = [
      tx({ id: 'a', accountName: undefined }),
      tx({ id: 'b', accountName: 'Nubank' }),
    ];
    expect(sortTransactions(txs, [{ col: 'account', dir: 'asc' }]).map(t => t.id)).toEqual(['b', 'a']);
  });

  it('não muta o array original', () => {
    const txs = [tx({ id: 'a', amount: 300 }), tx({ id: 'b', amount: 100 })];
    sortTransactions(txs, [{ col: 'amount', dir: 'asc' }]);
    expect(txs.map(t => t.id)).toEqual(['a', 'b']);
  });
});

describe('applySort', () => {
  it('click no critério primário inverte a direção', () => {
    expect(applySort([{ col: 'date', dir: 'desc' }], 'date', false))
      .toEqual([{ col: 'date', dir: 'asc' }]);
  });

  it('click em coluna não-primária substitui todos os critérios', () => {
    expect(applySort([{ col: 'date', dir: 'desc' }], 'amount', false))
      .toEqual([{ col: 'amount', dir: 'asc' }]);
  });

  it('click com múltiplos critérios existentes substitui por apenas a nova coluna', () => {
    const criteria = [{ col: 'date', dir: 'desc' }, { col: 'status', dir: 'asc' }];
    expect(applySort(criteria as any, 'amount', false))
      .toEqual([{ col: 'amount', dir: 'asc' }]);
  });

  it('shift+click em coluna existente inverte sua direção mantendo posição', () => {
    const criteria = [{ col: 'date', dir: 'desc' }, { col: 'amount', dir: 'asc' }];
    expect(applySort(criteria as any, 'amount', true))
      .toEqual([{ col: 'date', dir: 'desc' }, { col: 'amount', dir: 'desc' }]);
  });

  it('shift+click em coluna ausente acrescenta ao final com asc', () => {
    expect(applySort([{ col: 'date', dir: 'desc' }], 'status', true))
      .toEqual([{ col: 'date', dir: 'desc' }, { col: 'status', dir: 'asc' }]);
  });
});

describe('getSortInfo', () => {
  it('retorna null quando coluna não está nos critérios', () => {
    expect(getSortInfo([{ col: 'date', dir: 'desc' }], 'amount')).toBeNull();
  });

  it('retorna priority 1 e dir para a coluna primária', () => {
    expect(getSortInfo([{ col: 'date', dir: 'desc' }], 'date'))
      .toEqual({ priority: 1, dir: 'desc' });
  });

  it('retorna priority 2 para critério secundário', () => {
    const criteria = [{ col: 'date', dir: 'desc' }, { col: 'amount', dir: 'asc' }];
    expect(getSortInfo(criteria as any, 'amount'))
      .toEqual({ priority: 2, dir: 'asc' });
  });
});
```

- [ ] **Step 1.2: Rodar testes — verificar que falham**

```bash
cd frontend && npx vitest run --reporter=verbose src/app/features/transaction/transaction-list/transaction-list.utils.spec.ts
```

Expected: FAIL — `sortTransactions`, `applySort`, `getSortInfo` not found.

- [ ] **Step 1.3: Adicionar tipos e funções em `transaction-list.utils.ts`**

Acrescente o bloco abaixo **no final** do arquivo `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts` (após a última linha existente):

```ts
// --- Multi-sort ---

export type SortCol = 'description' | 'amount' | 'date' | 'type' | 'status' | 'category' | 'account';
export type SortDir = 'asc' | 'desc';
export type SortCriterion = { col: SortCol; dir: SortDir };

function effectiveSortDate(t: TransactionResponseDTO): string {
  if (t.installmentGroupId && t.invoiceDueDate) return t.invoiceDueDate;
  return t.date ?? '';
}

function compareBy(col: SortCol, a: TransactionResponseDTO, b: TransactionResponseDTO): number {
  switch (col) {
    case 'date':
      return effectiveSortDate(a).localeCompare(effectiveSortDate(b));
    case 'amount':
      return (a.amount ?? 0) - (b.amount ?? 0);
    case 'description':
      return (a.description ?? '').localeCompare(b.description ?? '', 'pt-BR', { sensitivity: 'base' });
    case 'category': {
      const ac = (a as any).categoryName as string | null | undefined;
      const bc = (b as any).categoryName as string | null | undefined;
      if (!ac && !bc) return 0;
      if (!ac) return 1;
      if (!bc) return -1;
      return ac.localeCompare(bc, 'pt-BR', { sensitivity: 'base' });
    }
    case 'account': {
      const aa = (a as any).accountName as string | null | undefined;
      const ba = (b as any).accountName as string | null | undefined;
      if (!aa && !ba) return 0;
      if (!aa) return 1;
      if (!ba) return -1;
      return aa.localeCompare(ba, 'pt-BR', { sensitivity: 'base' });
    }
    case 'type': {
      const typeOrder: Record<string, number> = { INCOME: 0, EXPENSE: 1 };
      const ao = (a as any).transferId ? 2 : (typeOrder[a.type ?? ''] ?? 1);
      const bo = (b as any).transferId ? 2 : (typeOrder[b.type ?? ''] ?? 1);
      return ao - bo;
    }
    case 'status': {
      const statusOrder: Record<string, number> = { PENDING: 0, PAID: 1, CANCELLED: 2 };
      return (statusOrder[a.status ?? ''] ?? 0) - (statusOrder[b.status ?? ''] ?? 0);
    }
  }
}

export function sortTransactions(
  transactions: TransactionResponseDTO[],
  criteria: SortCriterion[]
): TransactionResponseDTO[] {
  if (!criteria.length) return transactions;
  return [...transactions].sort((a, b) => {
    for (const { col, dir } of criteria) {
      const cmp = compareBy(col, a, b);
      if (cmp !== 0) return dir === 'asc' ? cmp : -cmp;
    }
    return 0;
  });
}

export function applySort(criteria: SortCriterion[], col: SortCol, shiftKey: boolean): SortCriterion[] {
  if (shiftKey) {
    const idx = criteria.findIndex(c => c.col === col);
    if (idx >= 0) {
      const updated = [...criteria];
      updated[idx] = { col, dir: criteria[idx].dir === 'asc' ? 'desc' : 'asc' };
      return updated;
    }
    return [...criteria, { col, dir: 'asc' }];
  }
  if (criteria.length === 1 && criteria[0].col === col) {
    return [{ col, dir: criteria[0].dir === 'asc' ? 'desc' : 'asc' }];
  }
  return [{ col, dir: 'asc' }];
}

export function getSortInfo(
  criteria: SortCriterion[],
  col: SortCol
): { priority: number; dir: SortDir } | null {
  const idx = criteria.findIndex(c => c.col === col);
  if (idx < 0) return null;
  return { priority: idx + 1, dir: criteria[idx].dir };
}
```

- [ ] **Step 1.4: Rodar testes — verificar que passam**

```bash
cd frontend && npx vitest run --reporter=verbose src/app/features/transaction/transaction-list/transaction-list.utils.spec.ts
```

Expected: todos os testes PASS (19 testes).

- [ ] **Step 1.5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts \
        frontend/src/app/features/transaction/transaction-list/transaction-list.utils.spec.ts
git commit -m "feat(transaction): adiciona utilitários de multi-sort (sortTransactions, applySort, getSortInfo)"
```

---

## Task 2: Integrar sort no `buildDisplayRows`

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`

- [ ] **Step 2.1: Atualizar `buildDisplayRowsGroupedByInvoice`**

Substitua a função `buildDisplayRowsGroupedByInvoice` inteira em `transaction-list.utils.ts`. A única mudança é o novo parâmetro `sortCriteria` e chamadas a `sortTransactions` antes de cada `buildFlatRows`:

```ts
function buildDisplayRowsGroupedByInvoice(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>,
  sortCriteria: SortCriterion[] = []
): DisplayRow[] {
  const withInvoice    = transactions.filter(t => t.invoiceId);
  const withoutInvoice = transactions.filter(t => !t.invoiceId);

  type InvoiceBucket = { dueDate: string | null; status: InvoiceStatus | null; label: string; transactions: TransactionResponseDTO[] };
  const invoiceMap = new Map<string, InvoiceBucket>();

  for (const t of withInvoice) {
    const id = t.invoiceId!;
    if (!invoiceMap.has(id)) {
      const label = t.invoiceDueDate
        ? 'Fatura ' + new Date(t.invoiceDueDate + 'T00:00:00').toLocaleDateString('pt-BR', { month: 'short', year: 'numeric' })
        : 'Fatura';
      invoiceMap.set(id, { dueDate: t.invoiceDueDate ?? null, status: t.invoiceStatus ?? null, label, transactions: [] });
    }
    invoiceMap.get(id)!.transactions.push(t);
  }

  const sorted = [...invoiceMap.entries()].sort(([, a], [, b]) => {
    if (!a.dueDate) return 1;
    if (!b.dueDate) return -1;
    return b.dueDate.localeCompare(a.dueDate);
  });

  const calcTotal = (txs: TransactionResponseDTO[]) =>
    txs.reduce((sum, t) => t.type === 'EXPENSE' ? sum + (t.amount ?? 0) : t.type === 'INCOME' ? sum - (t.amount ?? 0) : sum, 0);

  const rows: DisplayRow[] = [];

  for (const [invoiceId, bucket] of sorted) {
    rows.push({
      kind: 'invoice-header',
      invoiceId,
      label: bucket.label,
      dueDate: bucket.dueDate,
      totalAmount: calcTotal(bucket.transactions),
      status: bucket.status,
      transactionCount: bucket.transactions.length,
    });
    rows.push(...buildFlatRows(sortTransactions(bucket.transactions, sortCriteria), expandedIds));
  }

  if (withoutInvoice.length > 0) {
    rows.push({
      kind: 'invoice-header',
      invoiceId: null,
      label: 'Avulsas',
      dueDate: null,
      totalAmount: calcTotal(withoutInvoice),
      status: null,
      transactionCount: withoutInvoice.length,
    });
    rows.push(...buildFlatRows(sortTransactions(withoutInvoice, sortCriteria), expandedIds));
  }

  return rows;
}
```

- [ ] **Step 2.2: Atualizar `buildDisplayRows`**

Substitua a função `buildDisplayRows` inteira. Mudanças: novo parâmetro `sortCriteria` com default `[]` e chamadas a `sortTransactions` antes de cada `buildFlatRows`:

```ts
export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>,
  groupByPeriod = false,
  groupByInvoice = false,
  sortCriteria: SortCriterion[] = []
): DisplayRow[] {
  if (groupByInvoice) return buildDisplayRowsGroupedByInvoice(transactions, expandedIds, sortCriteria);
  if (!groupByPeriod) return buildFlatRows(sortTransactions(transactions, sortCriteria), expandedIds);
  const groups = groupByEffectiveMonth(transactions);
  return groups.flatMap(group => [
    {
      kind: 'period-header' as const,
      key: group.key,
      label: group.label,
      totalIncome: group.totalIncome,
      totalExpense: group.totalExpense,
      balance: group.balance,
    },
    ...buildFlatRows(sortTransactions(group.transactions, sortCriteria), expandedIds),
  ]);
}
```

- [ ] **Step 2.3: Rodar todos os testes do frontend para verificar que nada quebrou**

```bash
cd frontend && npm test -- --reporter=verbose
```

Expected: todos os testes existentes continuam passando (os novos 19 também).

- [ ] **Step 2.4: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts
git commit -m "feat(transaction): integra multi-sort ao buildDisplayRows"
```

---

## Task 3: Conectar sort state no componente

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`

- [ ] **Step 3.1: Atualizar import de utils**

Localize esta linha em `transaction-list.ts`:

```ts
import { buildDisplayRows, InstallmentGroupInfo, DisplayRow, InvoiceHeaderRow, resolveMonthKey, formatMonthLabel } from './transaction-list.utils';
```

Substitua por:

```ts
import { buildDisplayRows, InstallmentGroupInfo, DisplayRow, InvoiceHeaderRow, resolveMonthKey, formatMonthLabel, SortCol, SortCriterion, applySort, getSortInfo } from './transaction-list.utils';
```

- [ ] **Step 3.2: Adicionar signal `sortCriteria`**

Localize (no corpo da classe, após `showFilters = signal(false)`):

```ts
  showFilters          = signal(false);
```

Substitua por:

```ts
  showFilters          = signal(false);
  sortCriteria         = signal<SortCriterion[]>([{ col: 'date', dir: 'desc' }]);
```

- [ ] **Step 3.3: Atualizar `displayRows` computed**

Localize:

```ts
  displayRows = computed(() =>
    buildDisplayRows(
      this.filteredTransactions(),
      this.expandedTransactions(),
      this.filters().groupByPeriod,
      this.filters().groupByInvoice,
    )
  );
```

Substitua por:

```ts
  displayRows = computed(() =>
    buildDisplayRows(
      this.filteredTransactions(),
      this.expandedTransactions(),
      this.filters().groupByPeriod,
      this.filters().groupByInvoice,
      this.sortCriteria(),
    )
  );
```

- [ ] **Step 3.4: Adicionar métodos de sort**

Localize o método `toggleFilters` (primeiro método público após os signals):

```ts
  toggleFilters(): void {
```

Acrescente os dois métodos abaixo **antes** de `toggleFilters`:

```ts
  onSortClick(col: SortCol, event: MouseEvent): void {
    this.sortCriteria.update(criteria => applySort(criteria, col, event.shiftKey));
  }

  sortInfo(col: SortCol): { priority: number; dir: 'asc' | 'desc' } | null {
    return getSortInfo(this.sortCriteria(), col);
  }

  toggleFilters(): void {
```

- [ ] **Step 3.5: Verificar compilação TypeScript**

```bash
cd frontend && npx tsc --noEmit
```

Expected: sem erros.

- [ ] **Step 3.6: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.ts
git commit -m "feat(transaction): conecta sortCriteria signal ao componente"
```

---

## Task 4: Headers clicáveis + SCSS

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.html`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.scss`

- [ ] **Step 4.1: Atualizar header da coluna `description`**

Localize em `transaction-list.html`:

```html
      <ng-container matColumnDef="description">
        <th mat-header-cell *matHeaderCellDef>Descrição</th>
```

Substitua por:

```html
      <ng-container matColumnDef="description">
        <th mat-header-cell *matHeaderCellDef>
          <button class="sort-header" (click)="onSortClick('description', $event)">
            Descrição
            @if (sortInfo('description'); as info) {
              @if (sortCriteria().length > 1) {
                <span class="sort-badge">{{ info.priority }}</span>
              }
              <mat-icon class="sort-arrow">{{ info.dir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}</mat-icon>
            }
          </button>
        </th>
```

- [ ] **Step 4.2: Atualizar header da coluna `amount`**

Localize:

```html
      <ng-container matColumnDef="amount">
        <th mat-header-cell *matHeaderCellDef>Valor</th>
```

Substitua por:

```html
      <ng-container matColumnDef="amount">
        <th mat-header-cell *matHeaderCellDef>
          <button class="sort-header" (click)="onSortClick('amount', $event)">
            Valor
            @if (sortInfo('amount'); as info) {
              @if (sortCriteria().length > 1) {
                <span class="sort-badge">{{ info.priority }}</span>
              }
              <mat-icon class="sort-arrow">{{ info.dir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}</mat-icon>
            }
          </button>
        </th>
```

- [ ] **Step 4.3: Atualizar header da coluna `date`**

Localize:

```html
      <ng-container matColumnDef="date">
        <th mat-header-cell *matHeaderCellDef>Data</th>
```

Substitua por:

```html
      <ng-container matColumnDef="date">
        <th mat-header-cell *matHeaderCellDef>
          <button class="sort-header" (click)="onSortClick('date', $event)">
            Data
            @if (sortInfo('date'); as info) {
              @if (sortCriteria().length > 1) {
                <span class="sort-badge">{{ info.priority }}</span>
              }
              <mat-icon class="sort-arrow">{{ info.dir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}</mat-icon>
            }
          </button>
        </th>
```

- [ ] **Step 4.4: Atualizar header da coluna `type`**

Localize:

```html
      <ng-container matColumnDef="type">
        <th mat-header-cell *matHeaderCellDef>Tipo</th>
```

Substitua por:

```html
      <ng-container matColumnDef="type">
        <th mat-header-cell *matHeaderCellDef>
          <button class="sort-header" (click)="onSortClick('type', $event)">
            Tipo
            @if (sortInfo('type'); as info) {
              @if (sortCriteria().length > 1) {
                <span class="sort-badge">{{ info.priority }}</span>
              }
              <mat-icon class="sort-arrow">{{ info.dir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}</mat-icon>
            }
          </button>
        </th>
```

- [ ] **Step 4.5: Atualizar header da coluna `status`**

Localize:

```html
      <ng-container matColumnDef="status">
        <th mat-header-cell *matHeaderCellDef>Status</th>
```

Substitua por:

```html
      <ng-container matColumnDef="status">
        <th mat-header-cell *matHeaderCellDef>
          <button class="sort-header" (click)="onSortClick('status', $event)">
            Status
            @if (sortInfo('status'); as info) {
              @if (sortCriteria().length > 1) {
                <span class="sort-badge">{{ info.priority }}</span>
              }
              <mat-icon class="sort-arrow">{{ info.dir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}</mat-icon>
            }
          </button>
        </th>
```

- [ ] **Step 4.6: Atualizar header da coluna `category`**

Localize:

```html
      <ng-container matColumnDef="category">
        <th mat-header-cell *matHeaderCellDef>Categoria</th>
```

Substitua por:

```html
      <ng-container matColumnDef="category">
        <th mat-header-cell *matHeaderCellDef>
          <button class="sort-header" (click)="onSortClick('category', $event)">
            Categoria
            @if (sortInfo('category'); as info) {
              @if (sortCriteria().length > 1) {
                <span class="sort-badge">{{ info.priority }}</span>
              }
              <mat-icon class="sort-arrow">{{ info.dir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}</mat-icon>
            }
          </button>
        </th>
```

- [ ] **Step 4.7: Atualizar header da coluna `account`**

Localize:

```html
      <ng-container matColumnDef="account">
        <th mat-header-cell *matHeaderCellDef>Conta</th>
```

Substitua por:

```html
      <ng-container matColumnDef="account">
        <th mat-header-cell *matHeaderCellDef>
          <button class="sort-header" (click)="onSortClick('account', $event)">
            Conta
            @if (sortInfo('account'); as info) {
              @if (sortCriteria().length > 1) {
                <span class="sort-badge">{{ info.priority }}</span>
              }
              <mat-icon class="sort-arrow">{{ info.dir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}</mat-icon>
            }
          </button>
        </th>
```

- [ ] **Step 4.8: Adicionar estilos SCSS**

Acrescente ao **final** de `frontend/src/app/features/transaction/transaction-list/transaction-list.scss`:

```scss
// --- Sort headers ---

.sort-header {
  background: none;
  border: none;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font: inherit;
  font-weight: 500;
  color: inherit;
  padding: 0;
  white-space: nowrap;

  &:hover {
    color: var(--mat-sys-primary, #1976d2);
  }

  &:focus {
    outline: none;
  }
}

.sort-badge {
  font-size: 10px;
  font-weight: 700;
  color: var(--mat-sys-primary, #1976d2);
  line-height: 1;
}

.sort-arrow {
  font-size: 14px !important;
  width: 14px !important;
  height: 14px !important;
  line-height: 14px !important;
  color: var(--mat-sys-primary, #1976d2);
  vertical-align: middle;
}
```

- [ ] **Step 4.9: Verificar compilação e rodar todos os testes**

```bash
cd frontend && npx tsc --noEmit && npm test -- --reporter=verbose
```

Expected: sem erros TypeScript, todos os testes passam.

- [ ] **Step 4.10: Commit final**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.html \
        frontend/src/app/features/transaction/transaction-list/transaction-list.scss
git commit -m "feat(transaction): headers de sort clicáveis com indicadores visuais"
```

---

## Self-Review

**Cobertura da spec:**
- ✅ `SortCol`, `SortDir`, `SortCriterion` — Task 1
- ✅ `sortTransactions` com comparador multi-chave — Task 1
- ✅ `effectiveSortDate` na coluna date — Task 1, compareBy
- ✅ null vai ao final em category/account — Task 1
- ✅ Ordem fixa type (INCOME < EXPENSE < transfer) — Task 1
- ✅ Ordem fixa status (PENDING < PAID < CANCELLED) — Task 1
- ✅ `applySort` — click/shift+click — Task 1
- ✅ `getSortInfo` — Task 1
- ✅ `buildDisplayRows` + parâmetro opcional `sortCriteria` — Task 2
- ✅ Sort dentro de cada bucket (flat, por período, por fatura) — Task 2
- ✅ `buildDisplayRowsGroupedByInvoice` atualizado — Task 2
- ✅ `sortCriteria` signal no componente, default `[{col:'date', dir:'desc'}]` — Task 3
- ✅ `onSortClick` e `sortInfo` no componente — Task 3
- ✅ `displayRows` computed passa `sortCriteria()` — Task 3
- ✅ 7 headers HTML com botões sort — Task 4
- ✅ Badge numérico só com 2+ critérios — Task 4
- ✅ SCSS `.sort-header`, `.sort-badge`, `.sort-arrow` — Task 4
- ✅ `sortTransferPairsTogether` preservado (ocorre dentro de `buildFlatRows`, depois do sort externo) — comportamento correto por design

**Consistência de tipos:**
- `SortCriterion` definido em Task 1, importado no componente em Task 3
- `applySort(criteria, col, shiftKey)` — assinatura consistente em todas as referências
- `getSortInfo(criteria, col)` — assinatura consistente em todas as referências
- `buildDisplayRows(..., sortCriteria: SortCriterion[] = [])` — parâmetro opcional, backward compatible com `export { buildDisplayRows }` em `transaction-list.ts` linha 24
