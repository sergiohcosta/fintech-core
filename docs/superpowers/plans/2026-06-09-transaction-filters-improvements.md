# Melhorias nos Filtros de Transações e Faturas — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar 4 melhorias frontend: auto-seleção de conta única em faturas (#52), persistência dos filtros (#62), filtro por descrição client-side (#66) e agrupamento de transações por fatura (#64).

**Architecture:** Todas as mudanças são frontend-only (Angular 21, Zoneless). Os filtros de servidor (conta, tipo, status, período) continuam sendo passados via query params à API. O filtro de descrição é aplicado client-side via `computed`. O agrupamento por fatura introduz um novo `DisplayRow` kind (`invoice-header`) processado em `transaction-list.utils.ts`.

**Tech Stack:** Angular 21 Zoneless, Signals (`signal`, `computed`, `input`, `output`, `effect`), `toSignal`, Angular Material 3, TypeScript estrito.

---

## Mapa de arquivos

| Arquivo | Tasks |
|---|---|
| `frontend/src/app/features/invoice/invoice-list/invoice-list.ts` | Task 1 |
| `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.types.ts` | Tasks 2, 4 |
| `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts` | Tasks 2, 3, 4 |
| `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html` | Tasks 2, 4 |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.ts` | Tasks 2, 3, 4 |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.html` | Tasks 3, 4 |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts` | Task 4 |

---

## Task 1: Auto-selecionar conta única em Faturas (#52)

**Files:**
- Modify: `frontend/src/app/features/invoice/invoice-list/invoice-list.ts`

- [ ] **Step 1: Localizar o bloco de preselect em `ngOnInit`**

Abrir `invoice-list.ts`. Localizar (linhas ~64-75) o bloco:

```typescript
next: (data) => {
  const cc = data.filter(a => a.type === 'CREDIT_CARD');
  this.creditCardAccounts.set(cc);
  const preselect = this.route.snapshot.queryParamMap.get('accountId');
  if (preselect && cc.some(a => a.id === preselect)) {
    this.selectedId.set(preselect);
  }
},
```

- [ ] **Step 2: Adicionar `else if` para conta única**

Substituir pelo bloco abaixo (adiciona `else if` após a condição existente):

```typescript
next: (data) => {
  const cc = data.filter(a => a.type === 'CREDIT_CARD');
  this.creditCardAccounts.set(cc);
  const preselect = this.route.snapshot.queryParamMap.get('accountId');
  if (preselect && cc.some(a => a.id === preselect)) {
    this.selectedId.set(preselect);
  } else if (cc.length === 1) {
    this.selectedId.set(cc[0].id);
  }
},
```

- [ ] **Step 3: Verificar manualmente**

Subir o backend (`cd backend && ./mvnw spring-boot:run`) e o frontend (`cd frontend && npm start`).  
Navegar para `/invoices`. Se o tenant tiver exatamente 1 cartão de crédito, as faturas devem carregar sem precisar selecionar manualmente a conta.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/features/invoice/invoice-list/invoice-list.ts
git commit -m "fix(invoices): seleciona automaticamente quando há um único cartão (#52)"
```

---

## Task 2: Filtro por Descrição client-side (#66)

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.types.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`

- [ ] **Step 1: Adicionar `description` ao tipo `TransactionFilters`**

Substituir o conteúdo completo de `transaction-filters.types.ts`:

```typescript
export interface TransactionFilters {
  accountIds: string[];
  status: 'PENDING' | 'PAID' | 'CANCELLED' | null;
  type: 'INCOME' | 'EXPENSE' | null;
  startDate: string | null;
  endDate: string | null;
  groupByPeriod: boolean;
  description: string | null;
}

export const DEFAULT_FILTERS: TransactionFilters = {
  accountIds: [],
  status: null,
  type: null,
  startDate: null,
  endDate: null,
  groupByPeriod: false,
  description: null,
};
```

- [ ] **Step 2: Atualizar `transaction-filters.ts`**

Adicionar o signal `description` e o handler `onDescriptionChange`. Atualizar `emit()` e `clearFilters()`.

Substituir o conteúdo completo de `transaction-filters.ts`:

```typescript
import { Component, computed, input, OnInit, output, signal } from '@angular/core';
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
import { monthBounds, computeMonthChipStates, resolveMonthKey } from '../transaction-list.utils';

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
export class TransactionFiltersComponent implements OnInit {
  accounts       = input<AccountResponse[]>([]);
  initialFilters = input<TransactionFilters>(DEFAULT_FILTERS);
  filterChange   = output<TransactionFilters>();

  accountIds         = signal<string[]>([]);
  status             = signal<'PENDING' | 'PAID' | 'CANCELLED' | null>(null);
  type               = signal<'INCOME' | 'EXPENSE' | null>(null);
  startDate          = signal<string | null>(null);
  endDate            = signal<string | null>(null);
  groupByPeriod      = signal(false);
  description        = signal<string | null>(null);
  showCustomInterval = signal(false);
  selectedMonths     = signal<string[]>([]);

  readonly monthChipStates = computed(() => {
    const now = new Date();
    const selected = new Set(this.selectedMonths());
    return computeMonthChipStates(
      now.getFullYear(),
      now.getMonth() + 1,
      null,
      null,
    ).map(chip => ({ ...chip, active: selected.has(chip.key) }));
  });

  ngOnInit(): void {
    const f = this.initialFilters();
    this.accountIds.set(f.accountIds);
    this.status.set(f.status);
    this.type.set(f.type);
    this.startDate.set(f.startDate);
    this.endDate.set(f.endDate);
    this.groupByPeriod.set(f.groupByPeriod);
    this.description.set(null); // descrição nunca é restaurada (busca pontual)
    if (f.startDate && f.endDate) {
      const key = resolveMonthKey(f.startDate, f.endDate);
      if (key && key !== 'custom') {
        this.selectedMonths.set([key]);
      } else {
        this.showCustomInterval.set(true);
      }
    }
  }

  onAccountChange(val: string[]): void {
    this.accountIds.set(val ?? []);
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
    this.selectedMonths.set([]);
    this.emit();
  }

  onEndDateChange(val: string): void {
    this.endDate.set(val || null);
    this.selectedMonths.set([]);
    this.emit();
  }

  onGroupByPeriodChange(val: boolean): void {
    this.groupByPeriod.set(val);
    this.emit();
  }

  onDescriptionChange(val: string): void {
    this.description.set(val.trim() || null);
    this.emit();
  }

  onMonthChipClick(key: string, ctrl: boolean): void {
    const current = this.selectedMonths();
    let next: string[];
    if (ctrl) {
      next = current.includes(key)
        ? current.filter(k => k !== key)
        : [...current, key];
    } else {
      next = current.length === 1 && current[0] === key ? [] : [key];
    }
    this.selectedMonths.set(next);
    this.applyMonthSelection(next);
  }

  private applyMonthSelection(keys: string[]): void {
    if (keys.length === 0) {
      this.startDate.set(null);
      this.endDate.set(null);
    } else {
      const sorted = [...keys].sort();
      this.startDate.set(monthBounds(sorted[0]).startDate);
      this.endDate.set(monthBounds(sorted[sorted.length - 1]).endDate);
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
    this.selectedMonths.set([]);
  }

  clearFilters(): void {
    this.accountIds.set([]);
    this.status.set(null);
    this.type.set(null);
    this.startDate.set(null);
    this.endDate.set(null);
    this.groupByPeriod.set(false);
    this.description.set(null);
    this.showCustomInterval.set(false);
    this.selectedMonths.set([]);
    this.emit();
  }

  private emit(): void {
    this.filterChange.emit({
      accountIds:    this.accountIds(),
      status:        this.status(),
      type:          this.type(),
      startDate:     this.startDate(),
      endDate:       this.endDate(),
      groupByPeriod: this.groupByPeriod(),
      description:   this.description(),
    });
  }
}
```

- [ ] **Step 3: Adicionar campo de busca no template `transaction-filters.html`**

Inserir o bloco abaixo **antes** da `div.tipo-status-row` (após a seção de Conta):

```html
  <div class="filter-section">
    <span class="section-label">Buscar por descrição</span>
    <mat-form-field appearance="outline" class="conta-field">
      <mat-icon matPrefix>search</mat-icon>
      <input matInput
             [ngModel]="description() ?? ''"
             (ngModelChange)="onDescriptionChange($event)"
             placeholder="Ex: Supermercado..." />
      @if (description()) {
        <button matSuffix mat-icon-button (click)="onDescriptionChange('')" type="button">
          <mat-icon>close</mat-icon>
        </button>
      }
    </mat-form-field>
  </div>
```

- [ ] **Step 4: Atualizar `transaction-list.ts`**

**4a.** Adicionar `filteredTransactions` computed logo após a declaração de `displayRows`. Também atualizar `displayRows` para usar `filteredTransactions()`, atualizar `activeFilterChips`, `clearFilterChip` e `onFilterChange`.

Localizar e substituir o bloco de computed/signals no topo da classe (após `showFilters = signal(false)`):

```typescript
  filteredTransactions = computed(() => {
    const desc = this.filters().description?.toLowerCase().trim();
    if (!desc) return this.transactions();
    return this.transactions().filter(t =>
      t.description?.toLowerCase().includes(desc)
    );
  });

  displayedColumns = ['description', 'amount', 'date', 'type', 'status', 'category', 'account', 'actions'];

  displayRows = computed(() =>
    buildDisplayRows(this.filteredTransactions(), this.expandedTransactions(), this.filters().groupByPeriod)
  );
```

**4b.** Atualizar `activeFilterChips` — adicionar chip de descrição ao final, antes do `return chips`:

```typescript
    if (f.description) {
      chips.push({ label: `"${f.description}"`, field: 'description', colorClass: 'chip-description' });
    }
    return chips;
```

**4c.** Atualizar `clearFilterChip` para tratar `description` sem chamar o servidor:

```typescript
  clearFilterChip(field: string): void {
    this.filters.update(f => {
      if (field === 'accountIds')  return { ...f, accountIds: [] };
      if (field === 'status')      return { ...f, status: null };
      if (field === 'type')        return { ...f, type: null };
      if (field === 'period')      return { ...f, startDate: null, endDate: null };
      if (field === 'description') return { ...f, description: null };
      return f;
    });
    if (field !== 'description') {
      untracked(() => this.loadTransactions(this.filters()));
    }
  }
```

**4d.** Atualizar `onFilterChange` para não chamar o servidor quando só a descrição muda:

```typescript
  onFilterChange(newFilters: TransactionFilters): void {
    const prev = this.filters();
    this.filters.set(newFilters);
    const prevServer = { ...prev,       description: null };
    const newServer  = { ...newFilters, description: null };
    if (JSON.stringify(prevServer) !== JSON.stringify(newServer)) {
      untracked(() => this.loadTransactions(newFilters));
    }
  }
```

**4e.** Atualizar o template `transaction-list.html` para passar `initialFilters` ao componente filho:

```html
      <app-transaction-filters
        [accounts]="accounts()"
        [initialFilters]="filters()"
        (filterChange)="onFilterChange($event)">
      </app-transaction-filters>
```

- [ ] **Step 5: Verificar manualmente**

Navegar para `/transactions`. Abrir o painel de filtros. Digitar texto no campo "Buscar por descrição". A lista deve filtrar instantaneamente. Um chip com o texto deve aparecer acima da tabela. Clicar no `×` do chip remove o filtro.

- [ ] **Step 6: Commit**

```bash
git add \
  frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.types.ts \
  frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts \
  frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html \
  frontend/src/app/features/transaction/transaction-list/transaction-list.ts \
  frontend/src/app/features/transaction/transaction-list/transaction-list.html
git commit -m "feat(transactions): adiciona filtro por descrição client-side (#66)"
```

---

## Task 3: Persistência dos Filtros (#62)

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`

- [ ] **Step 1: Adicionar métodos de localStorage em `transaction-list.ts`**

Inserir os dois métodos privados a seguir no final da classe, antes do último `}`:

```typescript
  private loadFromStorage(): TransactionFilters {
    try {
      const raw = localStorage.getItem('fintech.transaction.filters');
      if (!raw) return DEFAULT_FILTERS;
      const parsed = JSON.parse(raw);
      // spread sobre DEFAULT_FILTERS garante que campos adicionados futuramente recebam seu default
      return { ...DEFAULT_FILTERS, ...parsed, description: null };
    } catch {
      return DEFAULT_FILTERS;
    }
  }

  private saveToStorage(f: TransactionFilters): void {
    const { description, ...toSave } = f; // descrição nunca é persistida
    localStorage.setItem('fintech.transaction.filters', JSON.stringify(toSave));
  }
```

- [ ] **Step 2: Restaurar filtros no `ngOnInit`**

Localizar o `ngOnInit` atual:

```typescript
  ngOnInit(): void {
    forkJoin({
      accounts:     this.accountService.listAccounts(),
      transactions: this.service.listTransactions(),
    }).subscribe({
      next: ({ accounts, transactions }) => {
        this.accounts.set(accounts);
        this.transactions.set(transactions);
      },
      error: () => this.snackBar.open('Erro ao carregar dados.', 'Fechar', { duration: 5000 }),
    });
  }
```

Substituir por:

```typescript
  ngOnInit(): void {
    const saved = this.loadFromStorage();
    this.filters.set(saved);
    forkJoin({
      accounts:     this.accountService.listAccounts(),
      transactions: this.service.listTransactions({
        accountIds: saved.accountIds.length > 0 ? saved.accountIds : undefined,
        status:    saved.status    ?? undefined,
        type:      saved.type      ?? undefined,
        startDate: saved.startDate ?? undefined,
        endDate:   saved.endDate   ?? undefined,
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

- [ ] **Step 3: Salvar no `onFilterChange`**

Localizar `onFilterChange` (atualizado na Task 2) e adicionar `this.saveToStorage(newFilters)`:

```typescript
  onFilterChange(newFilters: TransactionFilters): void {
    const prev = this.filters();
    this.filters.set(newFilters);
    this.saveToStorage(newFilters);
    const prevServer = { ...prev,       description: null };
    const newServer  = { ...newFilters, description: null };
    if (JSON.stringify(prevServer) !== JSON.stringify(newServer)) {
      untracked(() => this.loadTransactions(newFilters));
    }
  }
```

- [ ] **Step 4: Verificar manualmente**

Navegar para `/transactions`. Selecionar filtro de conta e status PENDING. Navegar para `/accounts` e voltar para `/transactions`. Os filtros devem estar restaurados e a lista deve mostrar apenas transações pendentes da conta selecionada. O painel de filtros, ao ser aberto, deve refletir as seleções salvas.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.ts
git commit -m "feat(transactions): persiste filtros no localStorage (#62)"
```

---

## Task 4: Agrupar Transações por Fatura (#64)

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.types.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.html`

- [ ] **Step 1: Adicionar `groupByInvoice` ao tipo**

Em `transaction-filters.types.ts`, adicionar o campo `groupByInvoice` à interface e ao default:

```typescript
export interface TransactionFilters {
  accountIds: string[];
  status: 'PENDING' | 'PAID' | 'CANCELLED' | null;
  type: 'INCOME' | 'EXPENSE' | null;
  startDate: string | null;
  endDate: string | null;
  groupByPeriod: boolean;
  groupByInvoice: boolean;
  description: string | null;
}

export const DEFAULT_FILTERS: TransactionFilters = {
  accountIds: [],
  status: null,
  type: null,
  startDate: null,
  endDate: null,
  groupByPeriod: false,
  groupByInvoice: false,
  description: null,
};
```

- [ ] **Step 2: Atualizar `transaction-filters.ts`**

Adicionar `groupByInvoice = signal(false)` entre `groupByPeriod` e `description`. Adicionar `onGroupByInvoiceChange`. Fazer os dois toggles mutuamente exclusivos. Atualizar `emit()`, `clearFilters()` e `ngOnInit()`.

Substituir o conteúdo completo de `transaction-filters.ts`:

```typescript
import { Component, computed, input, OnInit, output, signal } from '@angular/core';
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
import { monthBounds, computeMonthChipStates, resolveMonthKey } from '../transaction-list.utils';

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
export class TransactionFiltersComponent implements OnInit {
  accounts       = input<AccountResponse[]>([]);
  initialFilters = input<TransactionFilters>(DEFAULT_FILTERS);
  filterChange   = output<TransactionFilters>();

  accountIds         = signal<string[]>([]);
  status             = signal<'PENDING' | 'PAID' | 'CANCELLED' | null>(null);
  type               = signal<'INCOME' | 'EXPENSE' | null>(null);
  startDate          = signal<string | null>(null);
  endDate            = signal<string | null>(null);
  groupByPeriod      = signal(false);
  groupByInvoice     = signal(false);
  description        = signal<string | null>(null);
  showCustomInterval = signal(false);
  selectedMonths     = signal<string[]>([]);

  readonly monthChipStates = computed(() => {
    const now = new Date();
    const selected = new Set(this.selectedMonths());
    return computeMonthChipStates(
      now.getFullYear(),
      now.getMonth() + 1,
      null,
      null,
    ).map(chip => ({ ...chip, active: selected.has(chip.key) }));
  });

  ngOnInit(): void {
    const f = this.initialFilters();
    this.accountIds.set(f.accountIds);
    this.status.set(f.status);
    this.type.set(f.type);
    this.startDate.set(f.startDate);
    this.endDate.set(f.endDate);
    this.groupByPeriod.set(f.groupByPeriod);
    this.groupByInvoice.set(f.groupByInvoice);
    this.description.set(null);
    if (f.startDate && f.endDate) {
      const key = resolveMonthKey(f.startDate, f.endDate);
      if (key && key !== 'custom') {
        this.selectedMonths.set([key]);
      } else {
        this.showCustomInterval.set(true);
      }
    }
  }

  onAccountChange(val: string[]): void {
    this.accountIds.set(val ?? []);
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
    this.selectedMonths.set([]);
    this.emit();
  }

  onEndDateChange(val: string): void {
    this.endDate.set(val || null);
    this.selectedMonths.set([]);
    this.emit();
  }

  onGroupByPeriodChange(val: boolean): void {
    this.groupByPeriod.set(val);
    if (val) this.groupByInvoice.set(false); // mutuamente exclusivos
    this.emit();
  }

  onGroupByInvoiceChange(val: boolean): void {
    this.groupByInvoice.set(val);
    if (val) this.groupByPeriod.set(false); // mutuamente exclusivos
    this.emit();
  }

  onDescriptionChange(val: string): void {
    this.description.set(val.trim() || null);
    this.emit();
  }

  onMonthChipClick(key: string, ctrl: boolean): void {
    const current = this.selectedMonths();
    let next: string[];
    if (ctrl) {
      next = current.includes(key)
        ? current.filter(k => k !== key)
        : [...current, key];
    } else {
      next = current.length === 1 && current[0] === key ? [] : [key];
    }
    this.selectedMonths.set(next);
    this.applyMonthSelection(next);
  }

  private applyMonthSelection(keys: string[]): void {
    if (keys.length === 0) {
      this.startDate.set(null);
      this.endDate.set(null);
    } else {
      const sorted = [...keys].sort();
      this.startDate.set(monthBounds(sorted[0]).startDate);
      this.endDate.set(monthBounds(sorted[sorted.length - 1]).endDate);
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
    this.selectedMonths.set([]);
  }

  clearFilters(): void {
    this.accountIds.set([]);
    this.status.set(null);
    this.type.set(null);
    this.startDate.set(null);
    this.endDate.set(null);
    this.groupByPeriod.set(false);
    this.groupByInvoice.set(false);
    this.description.set(null);
    this.showCustomInterval.set(false);
    this.selectedMonths.set([]);
    this.emit();
  }

  private emit(): void {
    this.filterChange.emit({
      accountIds:    this.accountIds(),
      status:        this.status(),
      type:          this.type(),
      startDate:     this.startDate(),
      endDate:       this.endDate(),
      groupByPeriod: this.groupByPeriod(),
      groupByInvoice: this.groupByInvoice(),
      description:   this.description(),
    });
  }
}
```

- [ ] **Step 3: Adicionar toggle "Agrupar por fatura" no template**

Em `transaction-filters.html`, localizar o `panel-footer` e substituir por:

```html
  <div class="panel-footer">
    <mat-slide-toggle
      [ngModel]="groupByPeriod()"
      (ngModelChange)="onGroupByPeriodChange($event)">
      Agrupar por período
    </mat-slide-toggle>
    <mat-slide-toggle
      [ngModel]="groupByInvoice()"
      (ngModelChange)="onGroupByInvoiceChange($event)">
      Agrupar por fatura
    </mat-slide-toggle>
    <button mat-button type="button" class="clear-btn" (click)="clearFilters()">
      <mat-icon>close</mat-icon> Limpar filtros
    </button>
  </div>
```

- [ ] **Step 4: Adicionar `invoice-header` ao DisplayRow e função de agrupamento em `transaction-list.utils.ts`**

No início do arquivo, após a declaração de `PeriodGroup`, adicionar:

```typescript
export type InvoiceHeaderRow = {
  kind: 'invoice-header';
  invoiceId: string | null;
  label: string;
  dueDate: string | null;
  totalAmount: number;
  status: string | null;
  transactionCount: number;
};
```

Atualizar o tipo `DisplayRow` para incluir `InvoiceHeaderRow`:

```typescript
export type DisplayRow =
  | { kind: 'single';             data: TransactionResponseDTO }
  | { kind: 'installment';        data: TransactionResponseDTO; group: InstallmentGroupInfo; isExpanded: boolean }
  | { kind: 'installment-detail'; data: TransactionResponseDTO; group: InstallmentGroupInfo }
  | { kind: 'period-header';      key: string; label: string; totalIncome: number; totalExpense: number; balance: number }
  | InvoiceHeaderRow;
```

Adicionar a função `buildDisplayRowsGroupedByInvoice` antes de `buildDisplayRows`:

```typescript
function buildDisplayRowsGroupedByInvoice(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>
): DisplayRow[] {
  const withInvoice    = transactions.filter(t => t.invoiceId);
  const withoutInvoice = transactions.filter(t => !t.invoiceId);

  type InvoiceBucket = { dueDate: string | null; status: string | null; label: string; transactions: TransactionResponseDTO[] };
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
    rows.push(...buildFlatRows(bucket.transactions, expandedIds));
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
    rows.push(...buildFlatRows(withoutInvoice, expandedIds));
  }

  return rows;
}
```

Atualizar a assinatura de `buildDisplayRows` para aceitar o novo parâmetro:

```typescript
export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>,
  groupByPeriod = false,
  groupByInvoice = false
): DisplayRow[] {
  if (groupByInvoice) return buildDisplayRowsGroupedByInvoice(transactions, expandedIds);
  if (!groupByPeriod) return buildFlatRows(transactions, expandedIds);
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
    ...buildFlatRows(group.transactions, expandedIds),
  ]);
}
```

- [ ] **Step 5: Atualizar `transaction-list.ts`**

**5a.** Atualizar `displayRows` para passar `groupByInvoice`:

```typescript
  displayRows = computed(() =>
    buildDisplayRows(
      this.filteredTransactions(),
      this.expandedTransactions(),
      this.filters().groupByPeriod,
      this.filters().groupByInvoice,
    )
  );
```

**5b.** Adicionar predicado e métodos auxiliares (inserir junto com `isDataRow`, `isDetailRow`, `isPeriodHeader`):

```typescript
  isInvoiceHeader = (_: number, row: DisplayRow) => row.kind === 'invoice-header';

  invoiceHeaderChipClass(status: string | null): string {
    if (!status) return '';
    const map: Record<string, string> = { OPEN: 'invoice-open', CLOSED: 'invoice-closed', PAID: 'invoice-paid' };
    return 'invoice-chip ' + (map[status] ?? '');
  }

  invoiceHeaderStatusLabel(status: string | null): string {
    if (!status) return '';
    const map: Record<string, string> = { OPEN: 'Aberta', CLOSED: 'Fechada', PAID: 'Paga' };
    return map[status] ?? status;
  }
```

**5c.** Importar `InvoiceHeaderRow` no import de `transaction-list.utils`:

```typescript
import { buildDisplayRows, InstallmentGroupInfo, DisplayRow, InvoiceHeaderRow, resolveMonthKey, formatMonthLabel } from './transaction-list.utils';
export { buildDisplayRows } from './transaction-list.utils';
export type { InstallmentGroupInfo, DisplayRow, InvoiceHeaderRow } from './transaction-list.utils';
```

- [ ] **Step 6: Adicionar a row `invoice-header` em `transaction-list.html`**

**6a.** Inserir o `ng-container` do `invoiceHeader` após o `ng-container` do `periodHeader` (após a linha `</ng-container>` do periodHeader):

```html
      <ng-container matColumnDef="invoiceHeader">
        <td mat-cell *matCellDef="let row" [attr.colspan]="displayedColumns.length" class="invoice-header-cell">
          <div class="invoice-header-content">
            <span class="invoice-header-label">{{ $any(row).label }}</span>
            @if ($any(row).status) {
              <span [class]="invoiceHeaderChipClass($any(row).status)">
                {{ invoiceHeaderStatusLabel($any(row).status) }}
              </span>
            }
            <span class="invoice-header-total">
              {{ $any(row).totalAmount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
            </span>
            <span class="invoice-header-count">{{ $any(row).transactionCount }} lançamento(s)</span>
          </div>
        </td>
      </ng-container>
```

**6b.** Adicionar a `<tr>` do `invoiceHeader` junto com as demais (após a `<tr>` do `periodHeader`):

```html
      <tr mat-row *matRowDef="let row; columns: ['invoiceHeader']; when: isInvoiceHeader" class="invoice-header-row"></tr>
```

- [ ] **Step 7: Verificar manualmente**

Navegar para `/transactions`. Abrir o painel de filtros. Ativar "Agrupar por fatura". As transações com fatura devem ser agrupadas sob headers de fatura com label, status e total. Ativar "Agrupar por período" deve desativar "Agrupar por fatura" automaticamente e vice-versa.

- [ ] **Step 8: Verificar TypeScript**

```bash
cd frontend && npx tsc --noEmit
```

Saída esperada: nenhum erro.

- [ ] **Step 9: Commit**

```bash
git add \
  frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.types.ts \
  frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts \
  frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html \
  frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts \
  frontend/src/app/features/transaction/transaction-list/transaction-list.ts \
  frontend/src/app/features/transaction/transaction-list/transaction-list.html
git commit -m "feat(transactions): agrupa transações de cartão por fatura com toggle (#64)"
```

---

## Self-Review

**Cobertura do spec:**
- #52 auto-select → Task 1 ✓
- #62 localStorage → Task 3 ✓
- #66 filtro client-side → Task 2 ✓
- #64 toggle + invoice-header → Task 4 ✓
- `description` não persiste → Task 3 `saveToStorage` desestrutura e exclui ✓
- `groupByPeriod` ↔ `groupByInvoice` mutuamente exclusivos → Task 4 `onGroupByInvoiceChange` / `onGroupByPeriodChange` ✓
- Transações sem fatura em seção "Avulsas" → Task 4 `buildDisplayRowsGroupedByInvoice` ✓

**Consistência de tipos:**
- `InvoiceHeaderRow` exportada de `utils`, re-exportada em `transaction-list.ts` ✓
- `buildDisplayRows` nova assinatura com default params — retrocompatível com código existente ✓
- `clearFilterChip` trata `'description'` sem chamar servidor ✓
- `onFilterChange` chama `saveToStorage` (adicionado na Task 3, referenciado na Task 2 de forma incremental) ✓

**Nota:** Task 2 define `onFilterChange` sem `saveToStorage`. Task 3 adiciona `saveToStorage`. Isso é intencional — as tasks são incrementais. Se executadas fora de ordem, o TypeScript compila em ambos os estados.
