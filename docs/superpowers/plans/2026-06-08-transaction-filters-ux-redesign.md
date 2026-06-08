# Transaction Filters UX Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesenhar a barra de filtros de transações (painel colapsável + chips de mês + chips coloridos para tipo/status) e renomear o app de "Fintech Core" para "Nosso Dinheirinho" na toolbar.

**Architecture:** Alterações exclusivamente no frontend Angular. A lógica de filtros e os sinais internos do `TransactionFiltersComponent` são preservados; o que muda é a apresentação (template + scss) e a adição de uma função utilitária pura (`computeMonthChipStates`) que substitui a navegação `◀ Mês ▶`. O `TransactionList` ganha cor semântica nos chips de filtros ativos.

**Tech Stack:** Angular 21 Zoneless, Angular Material 3, SCSS, Vitest.

---

## Estrutura de arquivos

| Arquivo | O que muda |
|---|---|
| `frontend/src/app/components/shell/shell.html` | "Fintech Core" → "Nosso Dinheirinho" |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts` | Nova função `computeMonthChipStates` + tipo `MonthChipState` |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.spec.ts` | Testes para `computeMonthChipStates` |
| `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts` | Novos métodos/signals; remoção de código morto (navegador de mês) |
| `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html` | Reescrita completa do template |
| `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.scss` | Reescrita completa dos estilos |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.ts` | `activeFilterChips` ganha `colorClass` |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.html` | Chips ativos usam `colorClass` |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.scss` | Classes de cor para chips ativos |

---

## Task 1: Renomear "Fintech Core" → "Nosso Dinheirinho" na toolbar

**Files:**
- Modify: `frontend/src/app/components/shell/shell.html:5`

- [ ] **Step 1: Alterar o texto na toolbar**

No arquivo `frontend/src/app/components/shell/shell.html`, linha 5, substituir:
```html
  <span class="toolbar-title">Fintech Core</span>
```
por:
```html
  <span class="toolbar-title">Nosso Dinheirinho</span>
```

- [ ] **Step 2: Verificar visualmente**

Com o frontend rodando (`cd frontend && npm start`), confirmar que a toolbar exibe "Nosso Dinheirinho".

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/components/shell/shell.html
git commit -m "feat(shell): renomeia app para Nosso Dinheirinho na toolbar"
```

---

## Task 2: `computeMonthChipStates` — função pura e testes (TDD)

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.spec.ts`

### Contexto

A navegação `◀ Mês ▶` será substituída por chips de mês (Jan–Dez). A lógica de quais chips estão ativos/desabilitados precisa ser uma função pura para ser testável sem Angular TestBed.

A função usa `resolveMonthKey` (já existe no utils) para determinar qual mês está ativo.

### Step 1: Escrever os testes que devem falhar

Dois passos distintos: primeiro adicionar `computeMonthChipStates` ao import existente no topo do arquivo, depois adicionar o bloco `describe` ao final.

**1a.** No topo de `transaction-list.spec.ts`, acrescentar `computeMonthChipStates` à linha de import existente do utils:

```typescript
import {
  buildDisplayRows, DisplayRow, InstallmentGroupInfo,
  effectiveMonth, groupByEffectiveMonth, resolveMonthKey, monthBounds, formatMonthLabel,
  PeriodGroup, computeMonthChipStates,
} from './transaction-list.utils';
```

**1b.** Ao final do arquivo, adicionar o bloco:

- [ ] Execute `cd frontend && npm test` para confirmar que os novos testes **falham** (função não existe ainda).

```typescript
describe('computeMonthChipStates', () => {
  it('retorna exatamente 12 chips para o ano informado', () => {
    const chips = computeMonthChipStates(2026, 6, null, null);
    expect(chips).toHaveLength(12);
  });

  it('labels são abreviações pt-BR: Jan, Fev, Mar...', () => {
    const chips = computeMonthChipStates(2026, 6, null, null);
    expect(chips[0].label).toBe('Jan');
    expect(chips[1].label).toBe('Fev');
    expect(chips[5].label).toBe('Jun');
    expect(chips[11].label).toBe('Dez');
  });

  it('keys são no formato YYYY-MM', () => {
    const chips = computeMonthChipStates(2026, 6, null, null);
    expect(chips[0].key).toBe('2026-01');
    expect(chips[5].key).toBe('2026-06');
    expect(chips[11].key).toBe('2026-12');
  });

  it('meses futuros (> nowMonth) ficam disabled', () => {
    const chips = computeMonthChipStates(2026, 6, null, null); // nowMonth = 6 = junho
    expect(chips[5].disabled).toBe(false); // junho: não desabilitado
    expect(chips[6].disabled).toBe(true);  // julho: desabilitado
    expect(chips[11].disabled).toBe(true); // dezembro: desabilitado
  });

  it('meses passados e o mês atual não ficam disabled', () => {
    const chips = computeMonthChipStates(2026, 6, null, null);
    chips.slice(0, 6).forEach(c => expect(c.disabled).toBe(false));
  });

  it('chip do mês que coincide com startDate/endDate fica active', () => {
    const chips = computeMonthChipStates(2026, 6, '2026-06-01', '2026-06-30');
    expect(chips[5].active).toBe(true);  // junho: ativo
    expect(chips[4].active).toBe(false); // maio: inativo
  });

  it('sem datas selecionadas: nenhum chip fica active', () => {
    const chips = computeMonthChipStates(2026, 6, null, null);
    expect(chips.every(c => !c.active)).toBe(true);
  });

  it('intervalo personalizado (não exato de mês): nenhum chip fica active', () => {
    const chips = computeMonthChipStates(2026, 6, '2026-06-05', '2026-06-20');
    expect(chips.every(c => !c.active)).toBe(true);
  });
});
```

- [ ] **Step 2: Confirmar falha**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | grep -E "computeMonthChipStates|FAIL|PASS" | head -20
```
Esperado: todos os testes de `computeMonthChipStates` falham com "não é uma função" ou similar.

- [ ] **Step 3: Implementar `computeMonthChipStates` no utils**

Adicionar ao final de `transaction-list.utils.ts`, **antes** do último `}` do arquivo (o arquivo não tem classe, apenas exports — adicionar após a última função):

```typescript
export type MonthChipState = {
  label: string;
  key: string;
  active: boolean;
  disabled: boolean;
};

const MONTH_LABELS = ['Jan','Fev','Mar','Abr','Mai','Jun','Jul','Ago','Set','Out','Nov','Dez'];

export function computeMonthChipStates(
  year: number,
  nowMonth: number,
  startDate: string | null,
  endDate: string | null
): MonthChipState[] {
  const activeKey = resolveMonthKey(startDate, endDate);
  return Array.from({ length: 12 }, (_, i) => {
    const monthNum = i + 1;
    const key = `${year}-${String(monthNum).padStart(2, '0')}`;
    return {
      label: MONTH_LABELS[i],
      key,
      active: activeKey === key,
      disabled: monthNum > nowMonth,
    };
  });
}
```

- [ ] **Step 4: Confirmar que os testes passam**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | grep -E "computeMonthChipStates|✓|✗|FAIL|PASS" | head -30
```
Esperado: todos os 8 testes de `computeMonthChipStates` com ✓.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts \
        frontend/src/app/features/transaction/transaction-list/transaction-list.spec.ts
git commit -m "feat(transaction-filters): adiciona computeMonthChipStates com testes"
```

---

## Task 3: Atualizar `TransactionFiltersComponent` — novos métodos, remover código morto

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts`

### Contexto

O componente já possui `startDate`, `endDate`, `status`, `type`, `accountId`, `groupByPeriod` como sinais. O que muda:

**Adicionar:**
- `showCustomInterval = signal(false)` — controla visibilidade dos inputs de data
- `monthChipStates = computed(...)` — usa `computeMonthChipStates`
- `onMonthChipClick(key: string)` — toggle: ativo→limpa datas; inativo→aplica `monthBounds`
- `onCustomIntervalToggle()` — alterna visibilidade; ao fechar, limpa as datas

**Modificar:**
- `onTypeChange` e `onStatusChange` — adicionar comportamento de toggle (clicar no chip ativo limpa o filtro)
- `clearFilters()` — resetar também `showCustomInterval`
- `onStartDateChange` e `onEndDateChange` — remover chamada a `syncMonthLabel` (que será removida)

**Remover (código morto):**
- `monthLabel` signal
- `goToPreviousMonth()` / `goToNextMonth()`
- `syncMonthLabel()`
- `resolveCurrentKey()`
- `applyMonth()`

**Imports a adicionar:** `computeMonthChipStates`, `MonthChipState` do utils.

- [ ] **Step 1: Substituir o conteúdo completo de `transaction-filters.ts`**

```typescript
import { Component, computed, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AccountResponse } from '../../../../core/api/fintechSaaSAPI.schemas';
import { TransactionFilters, DEFAULT_FILTERS } from './transaction-filters.types';
import { monthBounds, computeMonthChipStates } from '../transaction-list.utils';

@Component({
  selector: 'app-transaction-filters',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatFormFieldModule, MatSelectModule, MatInputModule,
    MatButtonModule, MatIconModule, MatSlideToggleModule, MatTooltipModule,
  ],
  templateUrl: './transaction-filters.html',
  styleUrl: './transaction-filters.scss',
})
export class TransactionFiltersComponent {
  accounts     = input<AccountResponse[]>([]);
  filterChange = output<TransactionFilters>();

  accountId          = signal<string | null>(null);
  status             = signal<'PENDING' | 'PAID' | 'CANCELLED' | null>(null);
  type               = signal<'INCOME' | 'EXPENSE' | null>(null);
  startDate          = signal<string | null>(null);
  endDate            = signal<string | null>(null);
  groupByPeriod      = signal(false);
  showCustomInterval = signal(false);

  readonly monthChipStates = computed(() => {
    const now = new Date();
    return computeMonthChipStates(
      now.getFullYear(),
      now.getMonth() + 1,
      this.startDate(),
      this.endDate(),
    );
  });

  onAccountChange(val: string | null): void {
    this.accountId.set(val);
    this.emit();
  }

  onStatusChange(val: 'PENDING' | 'PAID' | 'CANCELLED' | null): void {
    this.status.set(val !== null && this.status() === val ? null : val);
    this.emit();
  }

  onTypeChange(val: 'INCOME' | 'EXPENSE' | null): void {
    this.type.set(val !== null && this.type() === val ? null : val);
    this.emit();
  }

  onStartDateChange(val: string): void {
    this.startDate.set(val || null);
    this.emit();
  }

  onEndDateChange(val: string): void {
    this.endDate.set(val || null);
    this.emit();
  }

  onGroupByPeriodChange(val: boolean): void {
    this.groupByPeriod.set(val);
    this.emit();
  }

  onMonthChipClick(key: string): void {
    const currentStart = this.startDate();
    const currentEnd   = this.endDate();
    const bounds = monthBounds(key);
    if (currentStart === bounds.startDate && currentEnd === bounds.endDate) {
      this.startDate.set(null);
      this.endDate.set(null);
    } else {
      this.startDate.set(bounds.startDate);
      this.endDate.set(bounds.endDate);
      this.showCustomInterval.set(false);
    }
    this.emit();
  }

  onCustomIntervalToggle(): void {
    const next = !this.showCustomInterval();
    this.showCustomInterval.set(next);
    if (!next) {
      this.startDate.set(null);
      this.endDate.set(null);
      this.emit();
    }
  }

  clearFilters(): void {
    this.accountId.set(null);
    this.status.set(null);
    this.type.set(null);
    this.startDate.set(null);
    this.endDate.set(null);
    this.showCustomInterval.set(false);
    this.emit();
  }

  private emit(): void {
    this.filterChange.emit({
      accountId:     this.accountId(),
      status:        this.status(),
      type:          this.type(),
      startDate:     this.startDate(),
      endDate:       this.endDate(),
      groupByPeriod: this.groupByPeriod(),
    });
  }
}
```

- [ ] **Step 2: Confirmar compilação TypeScript sem erros**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```
Esperado: sem saída (zero erros).

- [ ] **Step 3: Confirmar que os testes existentes continuam passando**

```bash
cd frontend && npm test 2>&1 | tail -5
```
Esperado: todos os testes passando.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts
git commit -m "refactor(transaction-filters): substitui navegador de mês por chips com computeMonthChipStates"
```

---

## Task 4: Redesign do template e SCSS do painel de filtros

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.scss`

### Contexto

O template atual tem 3 `mat-form-field` para Conta/Status/Tipo e um bloco de navegação de mês. O novo template tem:
1. Seção **Conta** — mantém `mat-select` (lista dinâmica)
2. Seção **Tipo + Status** — chips coloridos, dois grupos lado a lado
3. Seção **Período** — chips de mês + painel de intervalo personalizado retrátil
4. **Footer** — toggle de agrupamento + botão limpar

As cores dos chips de Tipo e Status são idênticas às dos badges da tabela de transações.

- [ ] **Step 1: Substituir `transaction-filters.html`**

```html
<div class="filters-panel">

  <div class="filter-section">
    <span class="section-label">Conta</span>
    <mat-form-field appearance="outline" class="conta-field">
      <mat-select [ngModel]="accountId()" (ngModelChange)="onAccountChange($event)">
        <mat-option [value]="null">Todas as contas</mat-option>
        @for (account of accounts(); track account.id) {
          <mat-option [value]="account.id">{{ account.name }}</mat-option>
        }
      </mat-select>
    </mat-form-field>
  </div>

  <div class="tipo-status-row">
    <div class="filter-section">
      <span class="section-label">Tipo</span>
      <div class="chip-group">
        <button type="button" class="fchip"
                [class.active]="type() === null"
                (click)="onTypeChange(null)">Todos</button>
        <button type="button" class="fchip expense"
                [class.active]="type() === 'EXPENSE'"
                (click)="onTypeChange('EXPENSE')">Despesa</button>
        <button type="button" class="fchip income"
                [class.active]="type() === 'INCOME'"
                (click)="onTypeChange('INCOME')">Receita</button>
      </div>
    </div>
    <div class="filter-section">
      <span class="section-label">Status</span>
      <div class="chip-group">
        <button type="button" class="fchip"
                [class.active]="status() === null"
                (click)="onStatusChange(null)">Todos</button>
        <button type="button" class="fchip pending"
                [class.active]="status() === 'PENDING'"
                (click)="onStatusChange('PENDING')">Pendente</button>
        <button type="button" class="fchip paid"
                [class.active]="status() === 'PAID'"
                (click)="onStatusChange('PAID')">Pago</button>
        <button type="button" class="fchip cancelled"
                [class.active]="status() === 'CANCELLED'"
                (click)="onStatusChange('CANCELLED')">Cancelado</button>
      </div>
    </div>
  </div>

  <div class="filter-section">
    <span class="section-label">Período</span>
    <div class="month-chips-row">
      @for (chip of monthChipStates(); track chip.key) {
        <button type="button" class="mchip"
                [class.active]="chip.active"
                [disabled]="chip.disabled"
                (click)="onMonthChipClick(chip.key)">
          {{ chip.label }}
        </button>
      }
      <button type="button" class="mchip custom"
              [class.active]="showCustomInterval()"
              (click)="onCustomIntervalToggle()">
        + Intervalo
      </button>
    </div>
    @if (showCustomInterval()) {
      <div class="custom-interval">
        <input type="date" class="date-input"
               [ngModel]="startDate() ?? ''"
               (ngModelChange)="onStartDateChange($event)">
        <span class="interval-sep">–</span>
        <input type="date" class="date-input"
               [ngModel]="endDate() ?? ''"
               (ngModelChange)="onEndDateChange($event)">
      </div>
    }
  </div>

  <div class="panel-footer">
    <mat-slide-toggle
      [ngModel]="groupByPeriod()"
      (ngModelChange)="onGroupByPeriodChange($event)">
      Agrupar por período
    </mat-slide-toggle>
    <button mat-button type="button" class="clear-btn" (click)="clearFilters()">
      <mat-icon>close</mat-icon> Limpar filtros
    </button>
  </div>

</div>
```

- [ ] **Step 2: Substituir `transaction-filters.scss`**

```scss
.filters-panel {
  background: #fff;
  border-bottom: 2px solid #e8f0fe;
}

.filter-section {
  padding: 12px 20px;
  border-bottom: 1px solid #f1f3f4;

  &:last-of-type {
    border-bottom: none;
  }
}

.section-label {
  display: block;
  font-size: 9px;
  font-weight: 700;
  color: #9aa0a6;
  text-transform: uppercase;
  letter-spacing: 0.6px;
  margin-bottom: 8px;
}

// --- Conta ---

.conta-field {
  width: 220px;

  ::ng-deep .mat-mdc-form-field-subscript-wrapper {
    display: none;
  }
}

// --- Tipo + Status lado a lado ---

.tipo-status-row {
  display: flex;
  border-bottom: 1px solid #f1f3f4;

  .filter-section {
    flex: 1;
    border-bottom: none;

    &:first-child {
      border-right: 1px solid #f1f3f4;
    }
  }
}

// --- Chips de filtro (Tipo / Status) ---

.chip-group {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.fchip {
  display: inline-flex;
  align-items: center;
  border: 1.5px solid #e0e0e0;
  border-radius: 20px;
  padding: 4px 12px;
  font-size: 11px;
  font-weight: 400;
  color: #5f6368;
  background: #fff;
  cursor: pointer;
  line-height: 1.4;
  transition: background 0.12s, border-color 0.12s, color 0.12s;

  &:focus { outline: none; }

  &.active {
    font-weight: 600;
  }

  // Neutro (Todos)
  &:not(.income):not(.expense):not(.pending):not(.paid):not(.cancelled) {
    &.active {
      background: #f1f3f4;
      border-color: #bdc1c6;
      color: #3c4043;
    }
  }

  &.expense {
    &:hover, &.active { background: #fce8e6; border-color: #f28b82; color: #c5221f; }
  }

  &.income {
    &:hover, &.active { background: #e6f4ea; border-color: #81c995; color: #1e8e3e; }
  }

  &.pending {
    &:hover, &.active { background: #fef7e0; border-color: #fbcc64; color: #f29900; }
  }

  &.paid {
    &:hover, &.active { background: #e6f4ea; border-color: #81c995; color: #1e8e3e; }
  }

  &.cancelled {
    &:hover, &.active { background: #f1f3f4; border-color: #bdc1c6; color: #80868b; }
  }
}

// --- Chips de mês ---

.month-chips-row {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
  margin-bottom: 2px;
}

.mchip {
  display: inline-flex;
  align-items: center;
  border: 1.5px solid #e0e0e0;
  border-radius: 20px;
  padding: 4px 10px;
  font-size: 11px;
  font-weight: 400;
  color: #5f6368;
  background: #fff;
  cursor: pointer;
  line-height: 1.4;
  transition: background 0.12s, border-color 0.12s;

  &:focus { outline: none; }

  &.active {
    background: #e8f0fe;
    border-color: #4285f4;
    color: #1a73e8;
    font-weight: 600;
  }

  &[disabled] {
    opacity: 0.32;
    cursor: default;
    pointer-events: none;
  }

  &.custom {
    border-style: dashed;
    color: #9aa0a6;
    font-size: 10px;

    &.active {
      background: #f8f9fa;
      border-style: solid;
      border-color: #9aa0a6;
      color: #5f6368;
    }
  }
}

// --- Intervalo personalizado ---

.custom-interval {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.date-input {
  border: 1.5px solid #ccc;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 11px;
  color: #3c4043;
  background: #fafafa;
  flex: 1;
  max-width: 160px;

  &:focus {
    outline: none;
    border-color: #4285f4;
  }
}

.interval-sep {
  font-size: 12px;
  color: #9aa0a6;
}

// --- Footer ---

.panel-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 20px 12px;
  border-top: 1px solid #f1f3f4;
}

.clear-btn {
  font-size: 11px;
  color: #5f6368;
}
```

- [ ] **Step 3: Verificar compilação TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```
Esperado: sem erros.

- [ ] **Step 4: Rodar todos os testes**

```bash
cd frontend && npm test 2>&1 | tail -5
```
Esperado: todos passando.

- [ ] **Step 5: Verificar visualmente no browser**

Com o backend rodando (`cd backend && ./mvnw spring-boot:run -q &`) e o frontend rodando (`cd frontend && npm start`), navegar até **http://localhost:4200/transactions**, clicar em "Filtros" e confirmar:
- Os chips de Tipo e Status aparecem com cores corretas ao clicar
- Os chips de mês mostram Jan–Dez; futuros esmaecidos e não clicáveis
- Clicar em "Jun" preenche a tabela com transações de junho
- Clicar em "Jun" novamente limpa o filtro de período
- Clicar em "+ Intervalo" revela dois campos de data

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html \
        frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.scss
git commit -m "feat(transaction-filters): redesign visual com chips coloridos e chips de mês"
```

---

## Task 5: Chips ativos coloridos no `TransactionList`

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.html`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.scss`

### Contexto

`activeFilterChips` atualmente retorna `{ label: string; field: string }[]`. Precisamos adicionar `colorClass: string` para que o HTML possa aplicar a cor semântica correta em cada chip.

O `clearFilterChip` já trata `field === 'period'` (limpa `startDate` e `endDate`). Esse comportamento se mantém.

- [ ] **Step 1: Atualizar `activeFilterChips` em `transaction-list.ts`**

Importar `resolveMonthKey` e `formatMonthLabel` no topo (já podem estar importados — verificar):

```typescript
import { buildDisplayRows, InstallmentGroupInfo, DisplayRow, resolveMonthKey, formatMonthLabel } from './transaction-list.utils';
```

Substituir o bloco `activeFilterChips = computed(...)` (linhas 69–88) pelo seguinte:

```typescript
activeFilterChips = computed((): Array<{ label: string; field: string; colorClass: string }> => {
  const f = this.filters();
  const chips: Array<{ label: string; field: string; colorClass: string }> = [];
  if (f.accountId) {
    const account = this.accounts().find(a => a.id === f.accountId);
    chips.push({ label: account?.name ?? 'Conta', field: 'accountId', colorClass: 'chip-account' });
  }
  if (f.status === 'PENDING')   chips.push({ label: 'Pendente',  field: 'status', colorClass: 'chip-pending' });
  if (f.status === 'PAID')      chips.push({ label: 'Pago',      field: 'status', colorClass: 'chip-paid' });
  if (f.status === 'CANCELLED') chips.push({ label: 'Cancelado', field: 'status', colorClass: 'chip-cancelled' });
  if (f.type === 'EXPENSE') chips.push({ label: 'Despesa', field: 'type', colorClass: 'chip-expense' });
  if (f.type === 'INCOME')  chips.push({ label: 'Receita', field: 'type', colorClass: 'chip-income' });
  if (f.startDate && f.endDate) {
    const key = resolveMonthKey(f.startDate, f.endDate);
    const label = key && key !== 'custom'
      ? formatMonthLabel(key)
      : `${f.startDate} – ${f.endDate}`;
    chips.push({ label, field: 'period', colorClass: 'chip-period' });
  }
  return chips;
});
```

- [ ] **Step 2: Atualizar o bloco de chips ativos em `transaction-list.html`**

Localizar o `@if (activeFilterChips().length > 0)` block e substituir:

```html
@if (activeFilterChips().length > 0) {
  <div class="active-chips">
    <span class="active-chips-label">Filtros ativos:</span>
    @for (chip of activeFilterChips(); track chip.field) {
      <span [class]="'filter-chip ' + chip.colorClass">
        {{ chip.label }}
        <button mat-icon-button class="chip-remove" (click)="clearFilterChip(chip.field)" matTooltip="Remover filtro">
          <mat-icon>close</mat-icon>
        </button>
      </span>
    }
  </div>
}
```

- [ ] **Step 3: Atualizar estilos em `transaction-list.scss`**

Localizar o bloco `// --- Chips de filtros ativos ---` e substituir todo o conteúdo de `.active-chips` e `.filter-chip` pelo seguinte:

```scss
// --- Chips de filtros ativos ---

.active-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 24px 6px;
  align-items: center;
}

.active-chips-label {
  font-size: 10px;
  font-weight: 600;
  color: #9aa0a6;
  text-transform: uppercase;
  letter-spacing: 0.4px;
  margin-right: 4px;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  border-radius: 20px;
  padding: 3px 6px 3px 11px;
  font-size: 11px;
  font-weight: 500;
  border: 1.5px solid transparent;
  gap: 2px;

  .chip-remove {
    width: 20px;
    height: 20px;
    line-height: 20px;

    mat-icon {
      font-size: 13px;
      width: 13px;
      height: 13px;
    }
  }

  &.chip-period   { background: #e8f0fe; color: #1a73e8; border-color: #4285f4; }
  &.chip-expense  { background: #fce8e6; color: #c5221f; border-color: #f28b82; }
  &.chip-income   { background: #e6f4ea; color: #1e8e3e; border-color: #81c995; }
  &.chip-pending  { background: #fef7e0; color: #f29900; border-color: #fbcc64; }
  &.chip-paid     { background: #e6f4ea; color: #1e8e3e; border-color: #81c995; }
  &.chip-cancelled{ background: #f1f3f4; color: #80868b; border-color: #bdc1c6; }
  &.chip-account  { background: #f3e8fd; color: #7b1fa2; border-color: #ce93d8; }
}
```

- [ ] **Step 4: Verificar compilação TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```
Esperado: sem erros.

- [ ] **Step 5: Rodar todos os testes**

```bash
cd frontend && npm test 2>&1 | tail -5
```
Esperado: todos os testes passando.

- [ ] **Step 6: Verificar visualmente**

No browser em **http://localhost:4200/transactions**:
- Aplicar filtro "Despesa" → chip vermelho "Despesa" aparece abaixo do painel
- Aplicar filtro "Junho 2026" → chip azul "Junho de 2026" aparece
- Aplicar conta → chip roxo claro com o nome da conta aparece
- Clicar × no chip → filtro removido, tabela atualiza

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.ts \
        frontend/src/app/features/transaction/transaction-list/transaction-list.html \
        frontend/src/app/features/transaction/transaction-list/transaction-list.scss
git commit -m "feat(transaction-list): chips de filtros ativos com cores semânticas"
```

---

## Verificação final

Após completar todas as tasks:

```bash
cd frontend && npm test 2>&1 | tail -5
cd frontend && npx tsc --noEmit 2>&1 | head -5
```

Checklist de critérios de aceitação:
- [ ] Toolbar exibe "Nosso Dinheirinho"
- [ ] Clicar em "Jun" filtra a tabela para junho de 2026 sem outros cliques
- [ ] Clicar em "Despesa" exibe só despesas; chip fica vermelho
- [ ] Clicar no mesmo chip ativo novamente remove o filtro (toggle)
- [ ] "+ Intervalo" revela dois inputs de data; digitar neles funciona como antes
- [ ] Chips ativos têm cores semânticas (azul=período, vermelho=despesa, verde=receita, âmbar=pendente)
- [ ] Nenhum teste existente quebra
