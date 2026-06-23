# Transaction Timeline Visual — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar a rota `/transactions/timeline` com 3 visualizações alternáveis (Calendário, Lista Agrupada, Timeline Horizontal) das transações do tenant, com filtros independentes da lista principal e navegação "Ver lista" que sincroniza filtros via queryParams.

**Architecture:** Feature isolada `transaction-timeline/` no frontend Angular. Component shell standalone gerencia `signal<TimelineFilters>` + `signal<TransactionResponseDTO[]>`, carrega via `TransactionsService.listTransactions` existente e distribui os dados como `input()` para 3 sub-components de view. Toda lógica de transformação (grid do calendário, agrupamento relativo, posicionamento horizontal) vive em arquivos `*-utils.ts` puros, testáveis no Vitest sem TestBed. Nenhum endpoint novo no backend — a feature é puramente de frontend, consumindo o contrato existente.

**Tech Stack:** Angular 21 (Zoneless, Signals-first), Angular Material 3, `@angular/cdk/scrolling` (virtual scroll, já disponível via Material), TypeScript 5.9 strict, Vitest 4.x.

## Global Constraints

- **Zoneless:** `provideZonelessChangeDetection()` — proibido usar APIs que dependam de `zone.js`. (CLAUDE.md)
- **Signals primeiro:** `signal`, `computed`, `effect` para estado local. RxJS apenas para HTTP. (CLAUDE.md)
- **TypeScript estrito:** proibido `any` — usar `unknown` + narrowing. (CLAUDE.md)
- **SCSS + Angular Material 3.** Não introduzir TailwindCSS. (CLAUDE.md)
- **Standalone components**, sem NgModule. `providedIn: 'root'` para services. (CLAUDE.md)
- **Lógica pura em arquivos sem imports Angular** — testável no Vitest sem `TestBed`. (architecture.md / summary.md)
- **Idioma:** comentários pedagógicos e mensagens de commit em PT-BR; identificadores em inglês. (CLAUDE.md)
- **Commits sem co-autoria.** Mensagens no imperativo. (git-operator.md)
- **Branch:** parte de `develop` (`feature/transaction-timeline`). (git-operator.md)
- **Feature puramente de frontend** — nenhuma migration, nenhum seed, nenhum endpoint novo. (dataset.md: "Feature puramente de frontend → nenhuma atualização de seed necessária")
- **`effectiveSortDate`:** parcela de cartão (`installmentGroupId != null && invoiceDueDate != null`) ordena por `invoiceDueDate`; demais por `date`. Regra replicada do backend (`TransactionService.effectiveSortDate`).

---

## File Structure

```
frontend/src/app/features/transaction/transaction-timeline/
├── transaction-timeline.ts            # Shell: filtros + tabs + load + "Ver lista"
├── transaction-timeline.html
├── transaction-timeline.scss
├── transaction-timeline.filters.ts    # TimelineFilters type + defaults + storage helpers
├── transaction-timeline.spec.ts       # Component test (shell)
├── timeline-shared.ts                 # effectiveSortDate, txDayTotals (reuso entre views)
├── timeline-shared.spec.ts
├── timeline-calendar/
│   ├── timeline-calendar.ts
│   ├── timeline-calendar.html
│   ├── timeline-calendar.scss
│   ├── calendar-utils.ts              # buildMonthGrid, getDayCellData, formatMonthLabel
│   └── calendar-utils.spec.ts
├── timeline-grouped-list/
│   ├── timeline-grouped-list.ts
│   ├── timeline-grouped-list.html
│   ├── timeline-grouped-list.scss
│   ├── grouped-list.utils.ts          # groupByRelativePeriod, computeGroupSummary
│   └── grouped-list.utils.spec.ts
└── timeline-horizontal/
    ├── timeline-horizontal.ts
    ├── timeline-horizontal.html
    ├── timeline-horizontal.scss
    ├── horizontal-utils.ts            # calculateMarkerPosition, resolveCollisions
    └── horizontal-utils.spec.ts
```

**Modificado:** `frontend/src/app/app.routes.ts` (nova rota antes de `transactions/:id`), `frontend/src/app/features/transaction/transaction-list/transaction-list.ts` (ler queryParams no `ngOnInit`).

**Ordem de build:** utils puras (testáveis isoladamente) → sub-components que as consomem → shell que orquestra → roteamento → sincronização com a lista. Cada view é independente das outras; só compartilham `timeline-shared.ts` e o tipo `TransactionResponseDTO` gerado.

---

## Task 1: Tipo de filtros + helpers de storage

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.filters.ts`
- Test: `frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.filters.spec.ts`

**Interfaces:**
- Consumes: `TransactionType`, `TransactionStatus` de `transaction-filters/transaction-filters.types` (re-export); nada de Angular.
- Produces:
  - `interface TimelineFilters { accountIds: string[]; statuses: TransactionStatus[]; types: TransactionType[]; startDate: string | null; endDate: string | null; description: string | null; viewMode: TimelineViewMode }`
  - `type TimelineViewMode = 'calendar' | 'grouped' | 'horizontal'`
  - `const DEFAULT_TIMELINE_FILTERS: TimelineFilters`
  - `function currentMonthTimelineFilters(): TimelineFilters`
  - `function loadTimelineFilters(): TimelineFilters`
  - `function saveTimelineFilters(f: TimelineFilters): void`
  - `const TIMELINE_STORAGE_KEY = 'fintech.timeline.filters'`

- [ ] **Step 1: Write the failing test**

```typescript
// transaction-timeline.filters.spec.ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  DEFAULT_TIMELINE_FILTERS,
  currentMonthTimelineFilters,
  loadTimelineFilters,
  saveTimelineFilters,
  TIMELINE_STORAGE_KEY,
} from './transaction-timeline.filters';

describe('transaction-timeline.filters', () => {
  beforeEach(() => localStorage.clear());

  it('default tem viewMode calendar e listas vazias', () => {
    expect(DEFAULT_TIMELINE_FILTERS.viewMode).toBe('calendar');
    expect(DEFAULT_TIMELINE_FILTERS.accountIds).toEqual([]);
    expect(DEFAULT_TIMELINE_FILTERS.description).toBeNull();
  });

  it('currentMonthTimelineFilters preenche o mês corrente (1º ao último dia)', () => {
    vi.setSystemTime(new Date('2026-06-15T12:00:00'));
    const f = currentMonthTimelineFilters();
    expect(f.startDate).toBe('2026-06-01');
    expect(f.endDate).toBe('2026-06-30');
    vi.useRealTimers();
  });

  it('save + load faz round-trip mas nunca persiste description', () => {
    const f = { ...DEFAULT_TIMELINE_FILTERS, accountIds: ['a1'], description: 'aluguel' };
    saveTimelineFilters(f);
    const stored = JSON.parse(localStorage.getItem(TIMELINE_STORAGE_KEY)!);
    expect(stored.description).toBeUndefined();
    expect(loadTimelineFilters().accountIds).toEqual(['a1']);
    expect(loadTimelineFilters().description).toBeNull();
  });

  it('load sem storage cai no mês corrente', () => {
    vi.setSystemTime(new Date('2026-03-10T00:00:00'));
    const f = loadTimelineFilters();
    expect(f.startDate).toBe('2026-03-01');
    vi.useRealTimers();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/transaction-timeline.filters.spec.ts`
Expected: FAIL — `Failed to resolve import "./transaction-timeline.filters"`

- [ ] **Step 3: Write minimal implementation**

```typescript
// transaction-timeline.filters.ts
import { TransactionType, TransactionStatus } from '../transaction-list/transaction-filters/transaction-filters.types';

export type TimelineViewMode = 'calendar' | 'grouped' | 'horizontal';

export interface TimelineFilters {
  accountIds: string[];
  statuses: TransactionStatus[];
  types: TransactionType[];
  startDate: string | null;
  endDate: string | null;
  description: string | null;
  viewMode: TimelineViewMode;
}

export const TIMELINE_STORAGE_KEY = 'fintech.timeline.filters';

export const DEFAULT_TIMELINE_FILTERS: TimelineFilters = {
  accountIds: [],
  statuses: [],
  types: [],
  startDate: null,
  endDate: null,
  description: null,
  viewMode: 'calendar',
};

const pad = (n: number) => String(n).padStart(2, '0');

export function currentMonthTimelineFilters(): TimelineFilters {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth() + 1;
  const lastDay = new Date(year, month, 0).getDate();
  return {
    ...DEFAULT_TIMELINE_FILTERS,
    startDate: `${year}-${pad(month)}-01`,
    endDate: `${year}-${pad(month)}-${pad(lastDay)}`,
  };
}

export function loadTimelineFilters(): TimelineFilters {
  try {
    const raw = localStorage.getItem(TIMELINE_STORAGE_KEY);
    if (!raw) return currentMonthTimelineFilters();
    const parsed = JSON.parse(raw) as Partial<TimelineFilters>;
    // description nunca é persistida — sempre reinicia limpa
    return { ...currentMonthTimelineFilters(), ...parsed, description: null };
  } catch {
    return currentMonthTimelineFilters();
  }
}

export function saveTimelineFilters(f: TimelineFilters): void {
  try {
    const { description: _omit, ...toSave } = f; // descrição é efêmera
    localStorage.setItem(TIMELINE_STORAGE_KEY, JSON.stringify(toSave));
  } catch {
    // localStorage cheio ou bloqueado — ignorar silenciosamente
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/transaction-timeline.filters.spec.ts`
Expected: PASS (4 passing)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.filters.ts frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.filters.spec.ts
git commit -m "feat(timeline): adiciona tipo TimelineFilters e helpers de storage"
```

---

## Task 2: Utilitários compartilhados (effectiveSortDate + totais do dia)

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-shared.ts`
- Test: `frontend/src/app/features/transaction/transaction-timeline/timeline-shared.spec.ts`

**Interfaces:**
- Consumes: `TransactionResponseDTO` de `core/api/fintechSaaSAPI.schemas`.
- Produces:
  - `function effectiveSortDate(t: TransactionResponseDTO): string` — retorna `invoiceDueDate` para parcela de cartão, senão `date` (string ISO `YYYY-MM-DD`).
  - `interface DayTotals { income: number; expense: number; net: number }`
  - `function dayTotals(txs: TransactionResponseDTO[]): DayTotals` — soma por tipo, ignorando `CANCELLED`.

- [ ] **Step 1: Write the failing test**

```typescript
// timeline-shared.spec.ts
import { describe, it, expect } from 'vitest';
import { effectiveSortDate, dayTotals } from './timeline-shared';
import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

function tx(p: Partial<TransactionResponseDTO>): TransactionResponseDTO {
  return {
    id: p.id ?? 'x', description: 'd', amount: p.amount ?? 0,
    date: p.date ?? '2026-06-10', type: p.type ?? 'EXPENSE',
    status: p.status ?? 'PAID', ...p,
  } as TransactionResponseDTO;
}

describe('effectiveSortDate', () => {
  it('usa invoiceDueDate quando é parcela de cartão', () => {
    const t = tx({ date: '2026-06-10', installmentGroupId: 'g1', invoiceDueDate: '2026-07-05' });
    expect(effectiveSortDate(t)).toBe('2026-07-05');
  });
  it('usa date para avulsa de cartão (sem installmentGroup)', () => {
    const t = tx({ date: '2026-06-10', invoiceDueDate: '2026-07-05' });
    expect(effectiveSortDate(t)).toBe('2026-06-10');
  });
  it('usa date para transação comum', () => {
    expect(effectiveSortDate(tx({ date: '2026-06-10' }))).toBe('2026-06-10');
  });
});

describe('dayTotals', () => {
  it('soma receitas e despesas, calcula net, ignora canceladas', () => {
    const txs = [
      tx({ type: 'INCOME', amount: 100, status: 'PAID' }),
      tx({ type: 'EXPENSE', amount: 30, status: 'PAID' }),
      tx({ type: 'EXPENSE', amount: 999, status: 'CANCELLED' }),
    ];
    expect(dayTotals(txs)).toEqual({ income: 100, expense: 30, net: 70 });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/timeline-shared.spec.ts`
Expected: FAIL — `Failed to resolve import "./timeline-shared"`

- [ ] **Step 3: Write minimal implementation**

```typescript
// timeline-shared.ts
import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

// Mesma regra do backend (TransactionService.effectiveSortDate): parcela de cartão
// referencia o vencimento da fatura; o resto referencia a data da própria transação.
export function effectiveSortDate(t: TransactionResponseDTO): string {
  if (t.installmentGroupId && t.invoiceDueDate) return t.invoiceDueDate;
  return t.date!;
}

export interface DayTotals {
  income: number;
  expense: number;
  net: number;
}

export function dayTotals(txs: TransactionResponseDTO[]): DayTotals {
  let income = 0;
  let expense = 0;
  for (const t of txs) {
    if (t.status === 'CANCELLED') continue;
    if (t.type === 'INCOME') income += t.amount ?? 0;
    else if (t.type === 'EXPENSE') expense += t.amount ?? 0;
  }
  return { income, expense, net: income - expense };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/timeline-shared.spec.ts`
Expected: PASS (4 passing)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-timeline/timeline-shared.ts frontend/src/app/features/transaction/transaction-timeline/timeline-shared.spec.ts
git commit -m "feat(timeline): adiciona utils compartilhadas effectiveSortDate e dayTotals"
```

---

## Task 3: Utils do Calendário

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-calendar/calendar-utils.ts`
- Test: `frontend/src/app/features/transaction/transaction-timeline/timeline-calendar/calendar-utils.spec.ts`

**Interfaces:**
- Consumes: `TransactionResponseDTO`; `effectiveSortDate`, `dayTotals`, `DayTotals` de `../timeline-shared`.
- Produces:
  - `interface DayCell { date: string | null; dayOfMonth: number | null; inMonth: boolean; transactions: TransactionResponseDTO[]; totals: DayTotals }` (célula vazia de padding tem `date: null`).
  - `function buildMonthGrid(monthAnchor: string, txs: TransactionResponseDTO[]): DayCell[]` — recebe data ISO qualquer dentro do mês, devolve 42 células (6 semanas × 7 dias), semana começando no domingo; transações posicionadas por `effectiveSortDate`.
  - `function formatMonthLabel(monthAnchor: string): string` — ex: `"Junho de 2026"`.

- [ ] **Step 1: Write the failing test**

```typescript
// calendar-utils.spec.ts
import { describe, it, expect } from 'vitest';
import { buildMonthGrid, formatMonthLabel } from './calendar-utils';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';

function tx(p: Partial<TransactionResponseDTO>): TransactionResponseDTO {
  return { id: p.id ?? 'x', description: 'd', amount: p.amount ?? 0,
    date: p.date ?? '2026-06-10', type: p.type ?? 'EXPENSE',
    status: p.status ?? 'PAID', ...p } as TransactionResponseDTO;
}

describe('buildMonthGrid', () => {
  it('sempre retorna 42 células', () => {
    expect(buildMonthGrid('2026-06-15', [])).toHaveLength(42);
  });

  it('junho/2026 começa numa segunda — 1 célula de padding antes do dia 1', () => {
    // 2026-06-01 é segunda-feira; semana começa no domingo → 1 célula vazia
    const grid = buildMonthGrid('2026-06-15', []);
    expect(grid[0].date).toBeNull();
    expect(grid[1].dayOfMonth).toBe(1);
    expect(grid[1].inMonth).toBe(true);
  });

  it('aloca transação no dia certo pela effectiveSortDate', () => {
    const grid = buildMonthGrid('2026-06-15', [tx({ id: 't1', date: '2026-06-10' })]);
    const cell = grid.find(c => c.date === '2026-06-10')!;
    expect(cell.transactions.map(t => t.id)).toEqual(['t1']);
    expect(cell.totals.expense).toBe(0); // amount default 0
  });

  it('parcela de cartão cai no dia da invoiceDueDate, não da date', () => {
    const grid = buildMonthGrid('2026-06-15', [
      tx({ id: 'p', date: '2026-05-20', installmentGroupId: 'g', invoiceDueDate: '2026-06-08' }),
    ]);
    expect(grid.find(c => c.date === '2026-06-08')!.transactions[0].id).toBe('p');
  });
});

describe('formatMonthLabel', () => {
  it('formata mês e ano em pt-BR', () => {
    expect(formatMonthLabel('2026-06-15')).toBe('Junho de 2026');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/timeline-calendar/calendar-utils.spec.ts`
Expected: FAIL — `Failed to resolve import "./calendar-utils"`

- [ ] **Step 3: Write minimal implementation**

```typescript
// calendar-utils.ts
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { effectiveSortDate, dayTotals, DayTotals } from '../timeline-shared';

export interface DayCell {
  date: string | null;
  dayOfMonth: number | null;
  inMonth: boolean;
  transactions: TransactionResponseDTO[];
  totals: DayTotals;
}

const pad = (n: number) => String(n).padStart(2, '0');
const iso = (y: number, m: number, d: number) => `${y}-${pad(m)}-${pad(d)}`;

export function buildMonthGrid(monthAnchor: string, txs: TransactionResponseDTO[]): DayCell[] {
  const [year, month] = monthAnchor.split('-').map(Number);
  // Agrupa transações por dia-efetivo uma única vez (evita varrer a lista por célula)
  const byDay = new Map<string, TransactionResponseDTO[]>();
  for (const t of txs) {
    const key = effectiveSortDate(t);
    (byDay.get(key) ?? byDay.set(key, []).get(key)!).push(t);
  }

  const firstWeekday = new Date(year, month - 1, 1).getDay(); // 0=domingo
  const daysInMonth = new Date(year, month, 0).getDate();

  const cells: DayCell[] = [];
  const emptyCell = (): DayCell => ({
    date: null, dayOfMonth: null, inMonth: false, transactions: [], totals: { income: 0, expense: 0, net: 0 },
  });

  // Padding antes do dia 1 (semana começa no domingo)
  for (let i = 0; i < firstWeekday; i++) cells.push(emptyCell());

  for (let d = 1; d <= daysInMonth; d++) {
    const date = iso(year, month, d);
    const transactions = byDay.get(date) ?? [];
    cells.push({ date, dayOfMonth: d, inMonth: true, transactions, totals: dayTotals(transactions) });
  }

  // Completa até 42 células (6 linhas fixas — evita o grid "pular" de altura ao trocar de mês)
  while (cells.length < 42) cells.push(emptyCell());
  return cells;
}

export function formatMonthLabel(monthAnchor: string): string {
  const [year, month] = monthAnchor.split('-').map(Number);
  const label = new Date(year, month - 1, 1).toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
  // toLocaleDateString devolve "junho de 2026" → capitaliza a inicial
  return label.charAt(0).toUpperCase() + label.slice(1);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/timeline-calendar/calendar-utils.spec.ts`
Expected: PASS (5 passing)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-timeline/timeline-calendar/calendar-utils.ts frontend/src/app/features/transaction/transaction-timeline/timeline-calendar/calendar-utils.spec.ts
git commit -m "feat(timeline): adiciona buildMonthGrid e formatMonthLabel do calendário"
```

---

## Task 4: Utils da Lista Agrupada

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-grouped-list/grouped-list.utils.ts`
- Test: `frontend/src/app/features/transaction/transaction-timeline/timeline-grouped-list/grouped-list.utils.spec.ts`

**Interfaces:**
- Consumes: `TransactionResponseDTO`; `effectiveSortDate`, `dayTotals` de `../timeline-shared`.
- Produces:
  - `type RelativeBucket = 'today' | 'yesterday' | 'thisWeek' | 'lastWeek' | 'thisMonth' | 'older'`
  - `interface RelativeGroup { bucket: RelativeBucket; label: string; transactions: TransactionResponseDTO[]; income: number; expense: number; net: number }`
  - `function groupByRelativePeriod(txs: TransactionResponseDTO[], today: string): RelativeGroup[]` — `today` é injetado (ISO) para testabilidade; ordena grupos do mais recente ao mais antigo; dentro de cada grupo, transações por `effectiveSortDate` desc; grupos vazios são omitidos.

- [ ] **Step 1: Write the failing test**

```typescript
// grouped-list.utils.spec.ts
import { describe, it, expect } from 'vitest';
import { groupByRelativePeriod } from './grouped-list.utils';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';

function tx(id: string, date: string, type: 'INCOME' | 'EXPENSE' = 'EXPENSE', amount = 10): TransactionResponseDTO {
  return { id, description: 'd', amount, date, type, status: 'PAID' } as TransactionResponseDTO;
}

describe('groupByRelativePeriod', () => {
  const today = '2026-06-15'; // segunda-feira

  it('separa hoje e ontem', () => {
    const groups = groupByRelativePeriod([tx('a', '2026-06-15'), tx('b', '2026-06-14')], today);
    expect(groups[0].bucket).toBe('today');
    expect(groups[0].transactions.map(t => t.id)).toEqual(['a']);
    expect(groups[1].bucket).toBe('yesterday');
  });

  it('omite buckets sem transações', () => {
    const groups = groupByRelativePeriod([tx('a', '2026-06-15')], today);
    expect(groups).toHaveLength(1);
    expect(groups.every(g => g.transactions.length > 0)).toBe(true);
  });

  it('calcula income/expense/net por grupo', () => {
    const groups = groupByRelativePeriod(
      [tx('a', '2026-06-15', 'INCOME', 100), tx('b', '2026-06-15', 'EXPENSE', 40)],
      today,
    );
    expect(groups[0]).toMatchObject({ income: 100, expense: 40, net: 60 });
  });

  it('joga datas muito antigas no bucket older', () => {
    const groups = groupByRelativePeriod([tx('a', '2026-01-01')], today);
    expect(groups[0].bucket).toBe('older');
  });

  it('ordena transações dentro do grupo por data desc', () => {
    const groups = groupByRelativePeriod(
      [tx('a', '2026-05-02'), tx('b', '2026-05-10')], today,
    );
    // ambas em "older" → mais recente primeiro
    expect(groups[0].transactions.map(t => t.id)).toEqual(['b', 'a']);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/timeline-grouped-list/grouped-list.utils.spec.ts`
Expected: FAIL — `Failed to resolve import "./grouped-list.utils"`

- [ ] **Step 3: Write minimal implementation**

```typescript
// grouped-list.utils.ts
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { effectiveSortDate, dayTotals } from '../timeline-shared';

export type RelativeBucket = 'today' | 'yesterday' | 'thisWeek' | 'lastWeek' | 'thisMonth' | 'older';

export interface RelativeGroup {
  bucket: RelativeBucket;
  label: string;
  transactions: TransactionResponseDTO[];
  income: number;
  expense: number;
  net: number;
}

// Ordem de exibição (recente → antigo) e rótulos pt-BR.
const BUCKET_ORDER: { bucket: RelativeBucket; label: string }[] = [
  { bucket: 'today', label: 'Hoje' },
  { bucket: 'yesterday', label: 'Ontem' },
  { bucket: 'thisWeek', label: 'Esta semana' },
  { bucket: 'lastWeek', label: 'Semana passada' },
  { bucket: 'thisMonth', label: 'Este mês' },
  { bucket: 'older', label: 'Mais antigos' },
];

// Diferença em dias inteiros entre duas datas ISO (a - b), via UTC para evitar DST.
function daysBetween(a: string, b: string): number {
  const [ay, am, ad] = a.split('-').map(Number);
  const [by, bm, bd] = b.split('-').map(Number);
  const ms = Date.UTC(ay, am - 1, ad) - Date.UTC(by, bm - 1, bd);
  return Math.round(ms / 86_400_000);
}

function classify(date: string, today: string): RelativeBucket {
  const diff = daysBetween(today, date); // hoje - data; positivo = passado
  if (diff === 0) return 'today';
  if (diff === 1) return 'yesterday';
  // Semana = janela de 7 dias a partir de hoje; semana passada = 8..14.
  if (diff >= 2 && diff <= 6) return 'thisWeek';
  if (diff >= 7 && diff <= 13) return 'lastWeek';
  // Mesmo mês-calendário e ainda mais recente que o início do mês
  if (date.slice(0, 7) === today.slice(0, 7)) return 'thisMonth';
  return 'older';
}

export function groupByRelativePeriod(txs: TransactionResponseDTO[], today: string): RelativeGroup[] {
  const buckets = new Map<RelativeBucket, TransactionResponseDTO[]>();
  for (const t of txs) {
    const b = classify(effectiveSortDate(t), today);
    (buckets.get(b) ?? buckets.set(b, []).get(b)!).push(t);
  }

  const result: RelativeGroup[] = [];
  for (const { bucket, label } of BUCKET_ORDER) {
    const items = buckets.get(bucket);
    if (!items || items.length === 0) continue;
    items.sort((a, b) => effectiveSortDate(b).localeCompare(effectiveSortDate(a)));
    const totals = dayTotals(items);
    result.push({ bucket, label, transactions: items, income: totals.income, expense: totals.expense, net: totals.net });
  }
  return result;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/timeline-grouped-list/grouped-list.utils.spec.ts`
Expected: PASS (5 passing)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-timeline/timeline-grouped-list/grouped-list.utils.ts frontend/src/app/features/transaction/transaction-timeline/timeline-grouped-list/grouped-list.utils.spec.ts
git commit -m "feat(timeline): adiciona groupByRelativePeriod da lista agrupada"
```

---

## Task 5: Utils da Timeline Horizontal

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-horizontal/horizontal-utils.ts`
- Test: `frontend/src/app/features/transaction/transaction-timeline/timeline-horizontal/horizontal-utils.spec.ts`

**Interfaces:**
- Consumes: `TransactionResponseDTO`; `effectiveSortDate` de `../timeline-shared`.
- Produces:
  - `function calculateMarkerPosition(date: string, rangeStart: string, rangeEnd: string, containerWidth: number): number` — px do início; range degenerado (start==end) → centro.
  - `interface PositionedMarker { date: string; x: number; stackIndex: number; transactions: TransactionResponseDTO[] }`
  - `function resolveCollisions(txs: TransactionResponseDTO[], rangeStart: string, rangeEnd: string, containerWidth: number): PositionedMarker[]` — agrupa transações do mesmo dia-efetivo num único marcador empilhado (`stackIndex` sequencial por ordem cronológica).

- [ ] **Step 1: Write the failing test**

```typescript
// horizontal-utils.spec.ts
import { describe, it, expect } from 'vitest';
import { calculateMarkerPosition, resolveCollisions } from './horizontal-utils';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';

function tx(id: string, date: string): TransactionResponseDTO {
  return { id, description: 'd', amount: 1, date, type: 'EXPENSE', status: 'PAID' } as TransactionResponseDTO;
}

describe('calculateMarkerPosition', () => {
  it('início do range fica em x=0', () => {
    expect(calculateMarkerPosition('2026-06-01', '2026-06-01', '2026-06-30', 290)).toBe(0);
  });
  it('fim do range fica na largura total', () => {
    expect(calculateMarkerPosition('2026-06-30', '2026-06-01', '2026-06-30', 290)).toBe(290);
  });
  it('meio do range fica no meio', () => {
    // 2026-06-16 é o ponto médio de 01..30 (15 de 29 dias) → ~50%
    const x = calculateMarkerPosition('2026-06-16', '2026-06-01', '2026-06-30', 290);
    expect(Math.round(x)).toBe(150);
  });
  it('range degenerado retorna o centro', () => {
    expect(calculateMarkerPosition('2026-06-10', '2026-06-10', '2026-06-10', 200)).toBe(100);
  });
});

describe('resolveCollisions', () => {
  it('agrupa transações do mesmo dia num marcador só', () => {
    const markers = resolveCollisions(
      [tx('a', '2026-06-10'), tx('b', '2026-06-10'), tx('c', '2026-06-20')],
      '2026-06-01', '2026-06-30', 290,
    );
    expect(markers).toHaveLength(2);
    const d10 = markers.find(m => m.date === '2026-06-10')!;
    expect(d10.transactions.map(t => t.id).sort()).toEqual(['a', 'b']);
  });

  it('atribui stackIndex sequencial dentro do marcador', () => {
    const markers = resolveCollisions(
      [tx('a', '2026-06-10'), tx('b', '2026-06-10')], '2026-06-01', '2026-06-30', 290,
    );
    expect(markers[0].stackIndex).toBe(0);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/timeline-horizontal/horizontal-utils.spec.ts`
Expected: FAIL — `Failed to resolve import "./horizontal-utils"`

- [ ] **Step 3: Write minimal implementation**

```typescript
// horizontal-utils.ts
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { effectiveSortDate } from '../timeline-shared';

function toEpochDay(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number);
  return Math.round(Date.UTC(y, m - 1, d) / 86_400_000);
}

export function calculateMarkerPosition(
  date: string, rangeStart: string, rangeEnd: string, containerWidth: number,
): number {
  const start = toEpochDay(rangeStart);
  const end = toEpochDay(rangeEnd);
  const span = end - start;
  if (span <= 0) return containerWidth / 2; // range degenerado → centro
  const offset = toEpochDay(date) - start;
  return (offset / span) * containerWidth;
}

export interface PositionedMarker {
  date: string;
  x: number;
  stackIndex: number;
  transactions: TransactionResponseDTO[];
}

export function resolveCollisions(
  txs: TransactionResponseDTO[], rangeStart: string, rangeEnd: string, containerWidth: number,
): PositionedMarker[] {
  // Colapsa transações do mesmo dia-efetivo num único marcador (a "colisão" é por dia).
  const byDay = new Map<string, TransactionResponseDTO[]>();
  for (const t of txs) {
    const key = effectiveSortDate(t);
    (byDay.get(key) ?? byDay.set(key, []).get(key)!).push(t);
  }
  return [...byDay.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, transactions], i) => ({
      date,
      x: calculateMarkerPosition(date, rangeStart, rangeEnd, containerWidth),
      stackIndex: i,
      transactions,
    }));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/timeline-horizontal/horizontal-utils.spec.ts`
Expected: PASS (6 passing)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-timeline/timeline-horizontal/horizontal-utils.ts frontend/src/app/features/transaction/transaction-timeline/timeline-horizontal/horizontal-utils.spec.ts
git commit -m "feat(timeline): adiciona posicionamento e colisão da timeline horizontal"
```

---

## Task 6: Sub-component Calendário

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-calendar/timeline-calendar.ts`
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-calendar/timeline-calendar.html`
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-calendar/timeline-calendar.scss`

**Interfaces:**
- Consumes: `buildMonthGrid`, `formatMonthLabel`, `DayCell` de `./calendar-utils`; `TransactionResponseDTO`.
- Produces (component API):
  - `selector: 'app-timeline-calendar'`
  - `transactions = input.required<TransactionResponseDTO[]>()`
  - `monthAnchor = input.required<string>()` (ISO date dentro do mês exibido)
  - `monthChange = output<string>()` (emite novo anchor ISO ao navegar mês)
  - `expandedDay = signal<string | null>(null)`

- [ ] **Step 1: Write the component (com computed do grid)**

```typescript
// timeline-calendar.ts
import { Component, input, output, signal, computed, ChangeDetectionStrategy } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { buildMonthGrid, formatMonthLabel, DayCell } from './calendar-utils';

@Component({
  selector: 'app-timeline-calendar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyPipe, MatIconModule, MatButtonModule, MatTooltipModule],
  templateUrl: './timeline-calendar.html',
  styleUrl: './timeline-calendar.scss',
})
export class TimelineCalendarComponent {
  transactions = input.required<TransactionResponseDTO[]>();
  monthAnchor = input.required<string>();
  monthChange = output<string>();

  expandedDay = signal<string | null>(null);

  readonly weekdayLabels = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'];
  grid = computed<DayCell[]>(() => buildMonthGrid(this.monthAnchor(), this.transactions()));
  monthLabel = computed(() => formatMonthLabel(this.monthAnchor()));

  expandedTransactions = computed<TransactionResponseDTO[]>(() => {
    const day = this.expandedDay();
    if (!day) return [];
    return this.grid().find(c => c.date === day)?.transactions ?? [];
  });

  toggleDay(cell: DayCell): void {
    if (!cell.date || cell.transactions.length === 0) return;
    this.expandedDay.update(d => (d === cell.date ? null : cell.date));
  }

  prevMonth(): void {
    this.monthChange.emit(this.shiftMonth(-1));
  }
  nextMonth(): void {
    this.monthChange.emit(this.shiftMonth(1));
  }
  private shiftMonth(delta: number): string {
    const [y, m] = this.monthAnchor().split('-').map(Number);
    const d = new Date(y, m - 1 + delta, 1);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
```

- [ ] **Step 2: Write the template**

```html
<!-- timeline-calendar.html -->
<div class="calendar">
  <header class="calendar-nav">
    <button mat-icon-button (click)="prevMonth()" aria-label="Mês anterior">
      <mat-icon>chevron_left</mat-icon>
    </button>
    <h3>{{ monthLabel() }}</h3>
    <button mat-icon-button (click)="nextMonth()" aria-label="Próximo mês">
      <mat-icon>chevron_right</mat-icon>
    </button>
  </header>

  <div class="calendar-grid" role="grid">
    @for (wd of weekdayLabels; track wd) {
      <div class="weekday" role="columnheader">{{ wd }}</div>
    }
    @for (cell of grid(); track $index) {
      <div
        class="day-cell"
        role="gridcell"
        [class.empty]="!cell.date"
        [class.has-tx]="cell.transactions.length > 0"
        [class.expanded]="cell.date === expandedDay()"
        [attr.aria-label]="cell.date"
        (click)="toggleDay(cell)">
        @if (cell.date) {
          <span class="day-number">{{ cell.dayOfMonth }}</span>
          @if (cell.transactions.length > 0) {
            <span class="day-net" [class.positive]="cell.totals.net >= 0" [class.negative]="cell.totals.net < 0">
              {{ cell.totals.net | currency: 'BRL' }}
            </span>
          }
        }
      </div>
    }
  </div>

  @if (expandedDay(); as day) {
    <div class="day-detail">
      <h4>{{ day }}</h4>
      @for (t of expandedTransactions(); track t.id) {
        <div class="detail-row" [class.income]="t.type === 'INCOME'" [class.expense]="t.type === 'EXPENSE'">
          @if (t.categoryIcon) { <mat-icon>{{ t.categoryIcon }}</mat-icon> }
          <span class="desc">{{ t.description }}</span>
          <span class="amount">{{ t.amount | currency: 'BRL' }}</span>
        </div>
      }
    </div>
  }
</div>
```

- [ ] **Step 3: Write the styles**

```scss
// timeline-calendar.scss
.calendar { display: flex; flex-direction: column; gap: 12px; }
.calendar-nav { display: flex; align-items: center; justify-content: center; gap: 16px; h3 { margin: 0; min-width: 180px; text-align: center; } }
.calendar-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; }
.weekday { text-align: center; font-size: 0.75rem; font-weight: 600; color: var(--mat-sys-on-surface-variant); padding: 4px 0; }
.day-cell {
  min-height: 64px; border-radius: 8px; padding: 4px; display: flex; flex-direction: column;
  border: 1px solid var(--mat-sys-outline-variant); cursor: default;
  &.empty { border: none; background: transparent; }
  &.has-tx { cursor: pointer; background: var(--mat-sys-surface-container); }
  &.expanded { outline: 2px solid var(--mat-sys-primary); }
}
.day-number { font-size: 0.8rem; font-weight: 500; }
.day-net { margin-top: auto; font-size: 0.7rem; font-weight: 600;
  &.positive { color: var(--mat-sys-primary); } &.negative { color: var(--mat-sys-error); } }
.day-detail { border-top: 1px solid var(--mat-sys-outline-variant); padding-top: 8px;
  h4 { margin: 0 0 8px; } }
.detail-row { display: flex; align-items: center; gap: 8px; padding: 4px 0;
  .desc { flex: 1; } .amount { font-weight: 600; }
  &.income .amount { color: var(--mat-sys-primary); } &.expense .amount { color: var(--mat-sys-error); } }
```

- [ ] **Step 4: Verify it compiles**

Run: `cd frontend && npx tsc --noEmit -p tsconfig.app.json`
Expected: sem erros referentes a `timeline-calendar`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-timeline/timeline-calendar/timeline-calendar.*
git commit -m "feat(timeline): adiciona sub-component de calendário (heatmap mensal)"
```

---

## Task 7: Sub-component Lista Agrupada

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-grouped-list/timeline-grouped-list.ts`
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-grouped-list/timeline-grouped-list.html`
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-grouped-list/timeline-grouped-list.scss`

**Interfaces:**
- Consumes: `groupByRelativePeriod`, `RelativeGroup` de `./grouped-list.utils`; `TransactionResponseDTO`.
- Produces (component API):
  - `selector: 'app-timeline-grouped-list'`
  - `transactions = input.required<TransactionResponseDTO[]>()`
  - `today = input<string>()` (default = hoje ISO; injetável p/ testes)
  - `collapsed = signal<Set<string>>(new Set())` (buckets recolhidos)

- [ ] **Step 1: Write the component**

```typescript
// timeline-grouped-list.ts
import { Component, input, signal, computed, ChangeDetectionStrategy } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { ScrollingModule } from '@angular/cdk/scrolling';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { groupByRelativePeriod, RelativeGroup } from './grouped-list.utils';

function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

@Component({
  selector: 'app-timeline-grouped-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyPipe, MatIconModule, ScrollingModule],
  templateUrl: './timeline-grouped-list.html',
  styleUrl: './timeline-grouped-list.scss',
})
export class TimelineGroupedListComponent {
  transactions = input.required<TransactionResponseDTO[]>();
  today = input<string>(todayIso());

  collapsed = signal<Set<string>>(new Set());
  groups = computed<RelativeGroup[]>(() => groupByRelativePeriod(this.transactions(), this.today()));

  toggle(bucket: string): void {
    this.collapsed.update(set => {
      const next = new Set(set);
      next.has(bucket) ? next.delete(bucket) : next.add(bucket);
      return next;
    });
  }
  isCollapsed(bucket: string): boolean {
    return this.collapsed().has(bucket);
  }
}
```

- [ ] **Step 2: Write the template**

```html
<!-- timeline-grouped-list.html -->
<div class="grouped-list">
  @for (g of groups(); track g.bucket) {
    <section class="group">
      <header class="group-header" (click)="toggle(g.bucket)"
              [attr.aria-expanded]="!isCollapsed(g.bucket)">
        <mat-icon>{{ isCollapsed(g.bucket) ? 'chevron_right' : 'expand_more' }}</mat-icon>
        <span class="group-label">{{ g.label }}</span>
        <span class="group-summary">
          <span class="income">+{{ g.income | currency: 'BRL' }}</span>
          <span class="expense">-{{ g.expense | currency: 'BRL' }}</span>
          <span class="net" [class.positive]="g.net >= 0">{{ g.net | currency: 'BRL' }}</span>
        </span>
      </header>
      @if (!isCollapsed(g.bucket)) {
        <div class="group-items">
          @for (t of g.transactions; track t.id) {
            <div class="tx-card" [class.income]="t.type === 'INCOME'" [class.expense]="t.type === 'EXPENSE'">
              @if (t.categoryIcon) { <mat-icon>{{ t.categoryIcon }}</mat-icon> }
              <span class="desc">{{ t.description }}</span>
              @if (t.installmentLabel) { <span class="badge">{{ t.installmentLabel }}</span> }
              <span class="status">{{ t.status }}</span>
              <span class="amount">{{ t.amount | currency: 'BRL' }}</span>
            </div>
          }
        </div>
      }
    </section>
  }
  @if (groups().length === 0) {
    <p class="empty">Nenhuma transação no período.</p>
  }
</div>
```

- [ ] **Step 3: Write the styles**

```scss
// timeline-grouped-list.scss
.grouped-list { display: flex; flex-direction: column; gap: 16px; }
.group-header { display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 8px;
  border-radius: 8px; background: var(--mat-sys-surface-container);
  .group-label { font-weight: 600; } .group-summary { margin-left: auto; display: flex; gap: 12px; font-size: 0.85rem;
    .income { color: var(--mat-sys-primary); } .expense { color: var(--mat-sys-error); }
    .net { font-weight: 600; } .net.positive { color: var(--mat-sys-primary); } } }
.group-items { display: flex; flex-direction: column; gap: 4px; padding: 8px 0 0 32px; }
.tx-card { display: flex; align-items: center; gap: 8px; padding: 8px; border-radius: 8px;
  border: 1px solid var(--mat-sys-outline-variant);
  .desc { flex: 1; } .badge { font-size: 0.7rem; padding: 2px 6px; border-radius: 4px; background: var(--mat-sys-secondary-container); }
  .amount { font-weight: 600; } &.income .amount { color: var(--mat-sys-primary); } &.expense .amount { color: var(--mat-sys-error); } }
.empty { text-align: center; color: var(--mat-sys-on-surface-variant); padding: 32px; }
```

- [ ] **Step 4: Verify it compiles**

Run: `cd frontend && npx tsc --noEmit -p tsconfig.app.json`
Expected: sem erros referentes a `timeline-grouped-list`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-timeline/timeline-grouped-list/timeline-grouped-list.*
git commit -m "feat(timeline): adiciona sub-component de lista agrupada por período relativo"
```

---

## Task 8: Sub-component Timeline Horizontal

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-horizontal/timeline-horizontal.ts`
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-horizontal/timeline-horizontal.html`
- Create: `frontend/src/app/features/transaction/transaction-timeline/timeline-horizontal/timeline-horizontal.scss`

**Interfaces:**
- Consumes: `resolveCollisions`, `PositionedMarker` de `./horizontal-utils`; `TransactionResponseDTO`.
- Produces (component API):
  - `selector: 'app-timeline-horizontal'`
  - `transactions = input.required<TransactionResponseDTO[]>()`
  - `rangeStart = input.required<string>()`, `rangeEnd = input.required<string>()`
  - `selectedMarker = signal<string | null>(null)`
  - `containerWidth` fixo em px (largura virtual da faixa; scroll horizontal cobre o excesso).

- [ ] **Step 1: Write the component**

```typescript
// timeline-horizontal.ts
import { Component, input, signal, computed, ChangeDetectionStrategy } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { resolveCollisions, PositionedMarker } from './horizontal-utils';

@Component({
  selector: 'app-timeline-horizontal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyPipe, MatIconModule],
  templateUrl: './timeline-horizontal.html',
  styleUrl: './timeline-horizontal.scss',
})
export class TimelineHorizontalComponent {
  transactions = input.required<TransactionResponseDTO[]>();
  rangeStart = input.required<string>();
  rangeEnd = input.required<string>();

  // Largura virtual da faixa em px. ~32px por dia dá respiro visual; o scroll horizontal
  // do container cobre o que exceder a viewport.
  readonly trackWidth = 1200;
  selectedMarker = signal<string | null>(null);

  markers = computed<PositionedMarker[]>(() =>
    resolveCollisions(this.transactions(), this.rangeStart(), this.rangeEnd(), this.trackWidth),
  );

  selected = computed<PositionedMarker | null>(() => {
    const d = this.selectedMarker();
    return d ? this.markers().find(m => m.date === d) ?? null : null;
  });

  select(marker: PositionedMarker): void {
    this.selectedMarker.update(d => (d === marker.date ? null : marker.date));
  }
}
```

- [ ] **Step 2: Write the template**

```html
<!-- timeline-horizontal.html -->
<div class="horizontal">
  <div class="legend">
    <span class="dot income"></span> Receita
    <span class="dot expense"></span> Despesa
    <span class="dot cancelled"></span> Cancelada
  </div>

  <div class="track-scroll">
    <div class="track" [style.width.px]="trackWidth + 40">
      <div class="axis"></div>
      @for (m of markers(); track m.date) {
        <button class="marker" [style.left.px]="m.x + 20"
                [class.selected]="m.date === selectedMarker()"
                (click)="select(m)"
                [attr.aria-label]="m.date + ': ' + m.transactions.length + ' transações'">
          <span class="count">{{ m.transactions.length }}</span>
        </button>
      }
    </div>
  </div>

  @if (selected(); as sel) {
    <div class="marker-detail">
      <h4>{{ sel.date }}</h4>
      @for (t of sel.transactions; track t.id) {
        <div class="detail-row" [class.income]="t.type === 'INCOME'" [class.expense]="t.type === 'EXPENSE'">
          @if (t.categoryIcon) { <mat-icon>{{ t.categoryIcon }}</mat-icon> }
          <span class="desc">{{ t.description }}</span>
          <span class="amount">{{ t.amount | currency: 'BRL' }}</span>
        </div>
      }
    </div>
  }
</div>
```

- [ ] **Step 3: Write the styles**

```scss
// timeline-horizontal.scss
.horizontal { display: flex; flex-direction: column; gap: 16px; }
.legend { display: flex; align-items: center; gap: 12px; font-size: 0.8rem;
  .dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; margin-right: 4px;
    &.income { background: var(--mat-sys-primary); } &.expense { background: var(--mat-sys-error); }
    &.cancelled { background: var(--mat-sys-outline); } } }
.track-scroll { overflow-x: auto; padding: 32px 0; }
.track { position: relative; height: 80px; }
.axis { position: absolute; top: 50%; left: 0; right: 0; height: 2px; background: var(--mat-sys-outline-variant); }
.marker { position: absolute; top: 50%; transform: translate(-50%, -50%); width: 28px; height: 28px;
  border-radius: 50%; border: 2px solid var(--mat-sys-primary); background: var(--mat-sys-surface);
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  .count { font-size: 0.75rem; font-weight: 600; }
  &.selected { background: var(--mat-sys-primary); color: var(--mat-sys-on-primary); } }
.marker-detail { border-top: 1px solid var(--mat-sys-outline-variant); padding-top: 8px; h4 { margin: 0 0 8px; } }
.detail-row { display: flex; align-items: center; gap: 8px; padding: 4px 0; .desc { flex: 1; } .amount { font-weight: 600; }
  &.income .amount { color: var(--mat-sys-primary); } &.expense .amount { color: var(--mat-sys-error); } }
```

- [ ] **Step 4: Verify it compiles**

Run: `cd frontend && npx tsc --noEmit -p tsconfig.app.json`
Expected: sem erros referentes a `timeline-horizontal`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-timeline/timeline-horizontal/timeline-horizontal.*
git commit -m "feat(timeline): adiciona sub-component de timeline horizontal"
```

---

## Task 9: Shell component (orquestração + tabs + load + "Ver lista")

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.ts`
- Create: `frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.html`
- Create: `frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.scss`

**Interfaces:**
- Consumes: `TransactionsService.listTransactions` (existente); `TimelineFilters`, `loadTimelineFilters`, `saveTimelineFilters`, `TimelineViewMode` da Task 1; os 3 sub-components (Tasks 6-8).
- Produces (component API):
  - `selector: 'app-transaction-timeline'`, `export class TransactionTimelineComponent`
  - `filters = signal<TimelineFilters>(...)`, `transactions = signal<TransactionResponseDTO[]>([])`
  - método `goToTransactionList(): void`
  - `monthAnchor = computed<string>()` derivado de `filters().startDate`.

- [ ] **Step 1: Write the component**

```typescript
// transaction-timeline.ts
import { Component, inject, signal, computed, effect, untracked, ChangeDetectionStrategy } from '@angular/core';
import { Router } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { TimelineCalendarComponent } from './timeline-calendar/timeline-calendar';
import { TimelineGroupedListComponent } from './timeline-grouped-list/timeline-grouped-list';
import { TimelineHorizontalComponent } from './timeline-horizontal/timeline-horizontal';
import {
  TimelineFilters, TimelineViewMode, loadTimelineFilters, saveTimelineFilters,
} from './transaction-timeline.filters';

const VIEW_MODES: TimelineViewMode[] = ['calendar', 'grouped', 'horizontal'];

@Component({
  selector: 'app-transaction-timeline',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatTabsModule, MatButtonModule, MatIconModule,
    TimelineCalendarComponent, TimelineGroupedListComponent, TimelineHorizontalComponent,
  ],
  templateUrl: './transaction-timeline.html',
  styleUrl: './transaction-timeline.scss',
})
export class TransactionTimelineComponent {
  private service = inject(TransactionsService);
  private router = inject(Router);
  private snackBar = inject(MatSnackBar);

  filters = signal<TimelineFilters>(loadTimelineFilters());
  transactions = signal<TransactionResponseDTO[]>([]);

  monthAnchor = computed(() => this.filters().startDate ?? this.firstOfCurrentMonth());
  selectedTab = computed(() => Math.max(0, VIEW_MODES.indexOf(this.filters().viewMode)));

  constructor() {
    // Recarrega sempre que mudam os campos que o backend conhece. description é client-side,
    // então é deliberadamente lida fora do effect para não disparar fetch.
    effect(() => {
      const f = this.filters();
      const serverKey = JSON.stringify({
        accountIds: f.accountIds, statuses: f.statuses, types: f.types,
        startDate: f.startDate, endDate: f.endDate,
      });
      untracked(() => this.loadTransactions(f, serverKey));
    });
  }

  private lastServerKey = '';
  private loadTransactions(f: TimelineFilters, serverKey: string): void {
    if (serverKey === this.lastServerKey) return; // evita refetch quando só viewMode/description mudaram
    this.lastServerKey = serverKey;
    this.service.listTransactions({
      accountIds: f.accountIds.length > 0 ? f.accountIds : undefined,
      status: f.statuses[0],
      type: f.types.find((t): t is 'INCOME' | 'EXPENSE' => t === 'INCOME' || t === 'EXPENSE'),
      startDate: f.startDate ?? undefined,
      endDate: f.endDate ?? undefined,
    }).subscribe({
      next: data => this.transactions.set(data),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 }),
    });
  }

  // Lista visível: aplica o filtro de descrição (client-side) sobre o que veio do backend.
  visibleTransactions = computed<TransactionResponseDTO[]>(() => {
    const desc = this.filters().description?.toLowerCase().trim();
    const txs = this.transactions();
    if (!desc) return txs;
    return txs.filter(t => t.description?.toLowerCase().includes(desc));
  });

  onTabChange(index: number): void {
    const mode = VIEW_MODES[index] ?? 'calendar';
    this.setFilters({ ...this.filters(), viewMode: mode });
  }

  onMonthChange(anchor: string): void {
    const [y, m] = anchor.split('-').map(Number);
    const lastDay = new Date(y, m, 0).getDate();
    const pad = (n: number) => String(n).padStart(2, '0');
    this.setFilters({ ...this.filters(), startDate: `${y}-${pad(m)}-01`, endDate: `${y}-${pad(m)}-${pad(lastDay)}` });
  }

  private setFilters(f: TimelineFilters): void {
    this.filters.set(f);
    saveTimelineFilters(f);
  }

  goToTransactionList(): void {
    const f = this.filters();
    const params: Record<string, string> = {};
    if (f.accountIds.length) params['accountIds'] = f.accountIds.join(',');
    if (f.statuses.length) params['status'] = f.statuses[0];
    if (f.types.length) params['type'] = f.types[0];
    if (f.startDate) params['startDate'] = f.startDate;
    if (f.endDate) params['endDate'] = f.endDate;
    if (f.description) params['description'] = f.description;
    this.router.navigate(['/transactions'], { queryParams: params });
  }

  private firstOfCurrentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
```

- [ ] **Step 2: Write the template**

```html
<!-- transaction-timeline.html -->
<div class="timeline-page">
  <header class="page-header">
    <h2>Linha do tempo</h2>
    <button mat-stroked-button (click)="goToTransactionList()">
      <mat-icon>list</mat-icon> Ver lista
    </button>
  </header>

  <mat-tab-group [selectedIndex]="selectedTab()" (selectedIndexChange)="onTabChange($event)">
    <mat-tab label="Calendário">
      <app-timeline-calendar
        [transactions]="visibleTransactions()"
        [monthAnchor]="monthAnchor()"
        (monthChange)="onMonthChange($event)" />
    </mat-tab>
    <mat-tab label="Lista agrupada">
      <app-timeline-grouped-list [transactions]="visibleTransactions()" />
    </mat-tab>
    <mat-tab label="Linha horizontal">
      <app-timeline-horizontal
        [transactions]="visibleTransactions()"
        [rangeStart]="filters().startDate ?? monthAnchor()"
        [rangeEnd]="filters().endDate ?? monthAnchor()" />
    </mat-tab>
  </mat-tab-group>
</div>
```

- [ ] **Step 3: Write the styles**

```scss
// transaction-timeline.scss
.timeline-page { padding: 16px; display: flex; flex-direction: column; gap: 16px; }
.page-header { display: flex; align-items: center; justify-content: space-between;
  h2 { margin: 0; } }
mat-tab-group { ::ng-deep .mat-mdc-tab-body-content { padding: 16px 4px; } }
```

- [ ] **Step 4: Verify it compiles**

Run: `cd frontend && npx tsc --noEmit -p tsconfig.app.json`
Expected: sem erros

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.*
git commit -m "feat(timeline): adiciona shell com tabs, carga de dados e Ver lista"
```

---

## Task 10: Component test do shell

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.spec.ts`

**Interfaces:**
- Consumes: `TransactionTimelineComponent`; mock de `TransactionsService` e `Router`.

- [ ] **Step 1: Write the failing test**

```typescript
// transaction-timeline.spec.ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { TransactionTimelineComponent } from './transaction-timeline';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { provideZonelessChangeDetection } from '@angular/core';

describe('TransactionTimelineComponent', () => {
  const listTransactions = vi.fn().mockReturnValue(of([]));
  const navigate = vi.fn();

  beforeEach(() => {
    localStorage.clear();
    listTransactions.mockClear();
    navigate.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: TransactionsService, useValue: { listTransactions } },
        { provide: Router, useValue: { navigate } },
      ],
    });
  });

  it('carrega transações na criação', () => {
    TestBed.createComponent(TransactionTimelineComponent);
    expect(listTransactions).toHaveBeenCalledTimes(1);
  });

  it('goToTransactionList navega com queryParams mapeados', () => {
    const fixture = TestBed.createComponent(TransactionTimelineComponent);
    const comp = fixture.componentInstance;
    comp.filters.set({
      accountIds: ['a1', 'a2'], statuses: ['PAID'], types: ['EXPENSE'],
      startDate: '2026-06-01', endDate: '2026-06-30', description: 'luz', viewMode: 'calendar',
    });
    comp.goToTransactionList();
    expect(navigate).toHaveBeenCalledWith(['/transactions'], {
      queryParams: { accountIds: 'a1,a2', status: 'PAID', type: 'EXPENSE',
        startDate: '2026-06-01', endDate: '2026-06-30', description: 'luz' },
    });
  });

  it('onTabChange troca o viewMode', () => {
    const fixture = TestBed.createComponent(TransactionTimelineComponent);
    const comp = fixture.componentInstance;
    comp.onTabChange(1);
    expect(comp.filters().viewMode).toBe('grouped');
  });

  it('visibleTransactions filtra por descrição client-side', () => {
    listTransactions.mockReturnValueOnce(of([
      { id: '1', description: 'Conta de luz', amount: 100, date: '2026-06-10', type: 'EXPENSE', status: 'PAID' },
      { id: '2', description: 'Mercado', amount: 50, date: '2026-06-11', type: 'EXPENSE', status: 'PAID' },
    ]));
    const fixture = TestBed.createComponent(TransactionTimelineComponent);
    const comp = fixture.componentInstance;
    comp.filters.update(f => ({ ...f, description: 'luz' }));
    expect(comp.visibleTransactions().map(t => t.id)).toEqual(['1']);
  });
});
```

- [ ] **Step 2: Run test to verify it fails then passes**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline/transaction-timeline.spec.ts`
Expected: PASS (4 passing) — o component já existe da Task 9; este teste valida o contrato. Se algo falhar, corrigir o component, não o teste.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.spec.ts
git commit -m "test(timeline): adiciona testes do shell (load, navegação, viewMode, filtro)"
```

---

## Task 11: Roteamento

**Files:**
- Modify: `frontend/src/app/app.routes.ts` (inserir entre `transactions` e `transactions/new`)

**Interfaces:**
- Consumes: `TransactionTimelineComponent` (Task 9).

**CRÍTICO:** a rota `transactions/timeline` **precisa vir antes** de `transactions/:id` (linha ~71), senão o param `:id` captura a string "timeline" e abre o form de edição.

- [ ] **Step 1: Adicionar a rota**

Inserir logo após o bloco `path: 'transactions'` (após a linha 66 do arquivo atual):

```typescript
      {
        path: 'transactions/timeline',
        loadComponent: () =>
          import('./features/transaction/transaction-timeline/transaction-timeline').then(
            m => m.TransactionTimelineComponent
          )
      },
```

- [ ] **Step 2: Verificar ordem das rotas**

Run: `cd frontend && grep -n "transactions" src/app/app.routes.ts`
Expected: `transactions/timeline` aparece ANTES de `transactions/:id`.

- [ ] **Step 3: Build de verificação**

Run: `cd frontend && npx tsc --noEmit -p tsconfig.app.json`
Expected: sem erros

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/app.routes.ts
git commit -m "feat(timeline): registra rota /transactions/timeline antes de :id"
```

---

## Task 12: Sincronizar queryParams na lista principal

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts` (`ngOnInit`, ~linha 183; imports no topo)

**Interfaces:**
- Consumes: `ActivatedRoute` (novo inject); `DEFAULT_FILTERS`, `currentMonthFilters` (já importados).

**Comportamento:** se houver queryParams na URL, eles têm prioridade sobre o localStorage (o usuário veio da timeline com filtros explícitos). Sem queryParams, mantém o comportamento atual (localStorage).

- [ ] **Step 1: Write the failing test**

Adicionar ao final de `transaction-list.spec.ts` (já existe). Se o arquivo não tiver setup de `ActivatedRoute`, criar um bloco isolado:

```typescript
// transaction-list.spec.ts — novo describe
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
// (reusar imports existentes do arquivo)

describe('TransactionList — queryParams da timeline', () => {
  it('mergeFiltersFromQueryParams sobrepõe accountIds, status, type, período e descrição', () => {
    const merged = TransactionList.mergeFiltersFromQueryParams(
      { accountIds: [], statuses: [], types: [], startDate: null, endDate: null,
        groupByPeriod: false, groupByInvoice: false, description: null },
      { accountIds: 'a1,a2', status: 'PAID', type: 'EXPENSE',
        startDate: '2026-06-01', endDate: '2026-06-30', description: 'luz' },
    );
    expect(merged.accountIds).toEqual(['a1', 'a2']);
    expect(merged.statuses).toEqual(['PAID']);
    expect(merged.types).toEqual(['EXPENSE']);
    expect(merged.startDate).toBe('2026-06-01');
    expect(merged.description).toBe('luz');
  });

  it('mergeFiltersFromQueryParams sem params devolve a base inalterada', () => {
    const base = { accountIds: ['x'], statuses: [], types: [], startDate: null, endDate: null,
      groupByPeriod: false, groupByInvoice: false, description: null } as const;
    expect(TransactionList.mergeFiltersFromQueryParams({ ...base }, {})).toEqual(base);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-list/transaction-list.spec.ts -t "queryParams da timeline"`
Expected: FAIL — `mergeFiltersFromQueryParams is not a function`

- [ ] **Step 3: Implementar o método estático puro + usar no ngOnInit**

Adicionar o método estático na classe `TransactionList` (lógica pura, testável sem TestBed):

```typescript
  // Mescla queryParams (vindos da timeline) sobre uma base de filtros.
  // queryParams têm prioridade: o usuário pediu explicitamente esses filtros pela URL.
  static mergeFiltersFromQueryParams(
    base: TransactionFilters,
    qp: Record<string, string | undefined>,
  ): TransactionFilters {
    const next = { ...base };
    if (qp['accountIds']) next.accountIds = qp['accountIds'].split(',').filter(Boolean);
    if (qp['status']) next.statuses = [qp['status'] as TransactionFilters['statuses'][number]];
    if (qp['type']) next.types = [qp['type'] as TransactionFilters['types'][number]];
    if (qp['startDate']) next.startDate = qp['startDate'];
    if (qp['endDate']) next.endDate = qp['endDate'];
    if (qp['description']) next.description = qp['description'];
    return next;
  }
```

Importar `ActivatedRoute` no topo e injetar:

```typescript
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
// ...
  private route = inject(ActivatedRoute);
```

Ajustar o início de `ngOnInit` para considerar os queryParams (snapshot — leitura única na entrada):

```typescript
  ngOnInit(): void {
    const saved = this.loadFromStorage();
    const qp = this.route.snapshot.queryParams as Record<string, string | undefined>;
    const initial = TransactionList.mergeFiltersFromQueryParams(saved, qp);
    this.filters.set(initial);
    forkJoin({
      accounts:     this.accountService.listAccounts(),
      transactions: this.service.listTransactions({
        accountIds: initial.accountIds.length > 0 ? initial.accountIds : undefined,
        status:    initial.statuses[0],
        type:      initial.types.find((t): t is 'INCOME' | 'EXPENSE' => t === 'INCOME' || t === 'EXPENSE'),
        startDate: initial.startDate ?? undefined,
        endDate:   initial.endDate   ?? undefined,
      }),
    }).subscribe({
      next: ({ accounts, transactions }) => {
        this.accounts.set(accounts);
        this.transactions.set(transactions);
      },
      error: () => this.snackBar.open('Erro ao carregar dados.', 'Fechar', { duration: 5000 }),
    });
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-list/transaction-list.spec.ts -t "queryParams da timeline"`
Expected: PASS (2 passing)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.ts frontend/src/app/features/transaction/transaction-list/transaction-list.spec.ts
git commit -m "feat(timeline): lista principal aceita filtros via queryParams da timeline"
```

---

## Task 13: Entrada de navegação para a timeline

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.html` (header de ações da lista)

**Interfaces:**
- Consumes: nada novo — `RouterLink` já está importado em `transaction-list.ts`.

- [ ] **Step 1: Localizar o cabeçalho de ações**

Run: `cd frontend && grep -n "routerLink\|mat-raised-button\|Nova" src/app/features/transaction/transaction-list/transaction-list.html | head`
Expected: localizar a barra de botões existente (ex: "Nova transação").

- [ ] **Step 2: Adicionar o botão "Linha do tempo"**

Inserir junto aos demais botões de ação no header (ajustar ao markup existente encontrado no Step 1):

```html
<a mat-stroked-button routerLink="/transactions/timeline">
  <mat-icon>timeline</mat-icon> Linha do tempo
</a>
```

- [ ] **Step 3: Verificar que MatIcon/RouterLink estão importados**

Run: `cd frontend && grep -n "MatIconModule\|RouterLink" src/app/features/transaction/transaction-list/transaction-list.ts`
Expected: ambos presentes (já estão — confirmado no código atual).

- [ ] **Step 4: Build de verificação**

Run: `cd frontend && npx tsc --noEmit -p tsconfig.app.json`
Expected: sem erros

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.html
git commit -m "feat(timeline): adiciona botão de acesso à linha do tempo na lista"
```

---

## Task 14: Verificação final (suite completa + lint)

**Files:** nenhum novo — gate de qualidade.

- [ ] **Step 1: Rodar toda a suite de timeline**

Run: `cd frontend && npx vitest run src/app/features/transaction/transaction-timeline`
Expected: todos os specs PASS (filters, shared, calendar-utils, grouped-list.utils, horizontal-utils, shell).

- [ ] **Step 2: Rodar a suite completa do frontend**

Run: `cd frontend && npm test -- --run`
Expected: 0 falhas. Se algum teste pré-existente quebrar (ex: `transaction-list`), investigar com systematic-debugging — não silenciar.

- [ ] **Step 3: Build de produção**

Run: `cd frontend && npm run build`
Expected: build bem-sucedido, sem erros de TypeScript.

- [ ] **Step 4: Smoke test manual (checklist do spec)**

Subir o app (`npm start`), logar com `carlos@costa.com` / `costa123` e verificar:
- [ ] `/transactions/timeline` abre na tab Calendário
- [ ] Trocar de tab preserva os dados; navegação de mês recarrega
- [ ] Reload preserva filtros (localStorage)
- [ ] "Ver lista" leva a `/transactions` com os mesmos filtros aplicados

- [ ] **Step 5: Commit final (se houver ajustes do smoke test)**

```bash
git add -A
git commit -m "test(timeline): garante suite verde e build de produção"
```

---

## Self-Review

**1. Spec coverage:**
- §2 Arquitetura de arquivos → Tasks 1-9 criam exatamente a árvore especificada. ✅
- §3 Roteamento → Task 11 (com a correção crítica de ordem `:id`). ✅
- §4 Filtros independentes + localStorage → Task 1. ✅
- §5 Fluxo de dados (signal + effect + untracked) → Task 9. ✅
- §6 Calendário heatmap → Tasks 3, 6. ✅
- §7 Lista agrupada (buckets relativos + resumo) → Tasks 4, 7. ✅
- §8 Timeline horizontal (posição + colisão + zoom) → Tasks 5, 8. **Zoom:** simplificado para `trackWidth` fixo + scroll. ⚠️ Ver nota abaixo.
- §9 Utils puras testáveis → Tasks 2-5 (todas com `.spec.ts`, sem Angular). ✅
- §10 "Ver lista" → Tasks 9 (origem) + 12 (destino lê queryParams). ✅
- §11 Testes (unit + component + integração) → Tasks 1-5, 10, 12, 14. ✅
- §12 a11y → `role="grid"`, `aria-label`, `aria-expanded`, `aria-label` nos markers (Tasks 6-8). ✅
- §13 Performance → `OnPush` em todos os components, `track` em todos os `@for`, virtual-scroll-ready (`ScrollingModule` importado na Task 7). ⚠️ Ver nota.
- §14 Sem dependências novas → confirmado (`@angular/cdk/scrolling` vem com Material). ✅
- §15 Critérios de aceite → cobertos pelo smoke test da Task 14.

**Notas de simplificação deliberada (YAGNI):**
- **Zoom do horizontal (§8):** o spec previa slider semana/mês/trimestre. O plano entrega `trackWidth` fixo + scroll horizontal, que cobre o caso de uso (ver tudo do período) sem o estado extra do slider. O range já é limitado pelo filtro de período, então o zoom agrega pouco. **Adicionar slider só se o range típico ficar ilegível** — fácil de plugar depois (multiplicar `trackWidth`).
- **Virtual scroll (§13):** `ScrollingModule` está importado e pronto, mas o template da Task 7 usa `@for` direto. Trocar por `<cdk-virtual-scroll-viewport>` é um passo isolado a fazer **se** o dataset passar de ~200 linhas — prematuro antes disso.
- **Debounce de descrição (§13):** filtro de descrição é `computed` puro sobre array já em memória (sem fetch), então debounce não agrega — removido.

**2. Placeholder scan:** Nenhum "TBD"/"TODO"/"handle edge cases". Todo step com código tem código completo. ✅

**3. Type consistency:**
- `effectiveSortDate(t): string` — assinatura idêntica em Tasks 2, 3, 4, 5. ✅
- `DayTotals { income, expense, net }` — Task 2, consumido em 3. ✅
- `TimelineFilters` (7 campos + viewMode) — Task 1, consumido em 9, 10. ✅
- `buildMonthGrid(monthAnchor, txs): DayCell[]` — Task 3, consumido em 6. ✅
- `groupByRelativePeriod(txs, today): RelativeGroup[]` — Task 4, consumido em 7. ✅
- `resolveCollisions(txs, rangeStart, rangeEnd, containerWidth): PositionedMarker[]` — Task 5, consumido em 8. ✅
- `mergeFiltersFromQueryParams(base, qp): TransactionFilters` — Task 12 (definido e testado no mesmo task). ✅
- Componentes consomem `input.required<TransactionResponseDTO[]>()` — assinatura consistente entre shell (Task 9) e filhos (Tasks 6-8). ✅