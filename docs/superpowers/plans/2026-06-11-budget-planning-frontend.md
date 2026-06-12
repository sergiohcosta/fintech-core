# Planejamento Mensal — Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **Pré-requisito:** Plano de backend (`2026-06-11-budget-planning-backend.md`) concluído e backend rodando na porta 8080.

**Goal:** Implementar o frontend Angular da feature de planejamento mensal — rotas, serviço, componentes de ciclo atual, templates recorrentes, histórico e detalhe.

**Architecture:** Feature standalone em `features/planning/`. Estado gerenciado com Signals (`signal`, `computed`). Serviço gerado pelo Orval chama o backend. Componentes de diálogo para criar item, vincular transação, criar template.

**Tech Stack:** Angular 21 Zoneless, Angular Material 3, Signals, Orval (geração de client HTTP), Vitest.

---

## Mapa de Arquivos

**Criar:**
- `frontend/src/app/features/planning/planning.routes.ts`
- `frontend/src/app/features/planning/planning.service.ts`
- `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.ts`
- `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html`
- `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.scss`
- `frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.ts`
- `frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.spec.ts`
- `frontend/src/app/features/planning/budget-item-form/budget-item-form.ts`
- `frontend/src/app/features/planning/budget-item-form/budget-item-form.html`
- `frontend/src/app/features/planning/link-transaction-dialog/link-transaction-dialog.ts`
- `frontend/src/app/features/planning/link-transaction-dialog/link-transaction-dialog.html`
- `frontend/src/app/features/planning/recurring-item-list/recurring-item-list.ts`
- `frontend/src/app/features/planning/recurring-item-list/recurring-item-list.html`
- `frontend/src/app/features/planning/recurring-item-form/recurring-item-form.ts`
- `frontend/src/app/features/planning/recurring-item-form/recurring-item-form.html`
- `frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.ts`
- `frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.html`
- `frontend/src/app/features/planning/budget-cycle-detail/budget-cycle-detail.ts`
- `frontend/src/app/features/planning/budget-cycle-detail/budget-cycle-detail.html`

**Modificar:**
- `frontend/src/app/app.routes.ts` — lazy routes `/planning/*`
- `frontend/src/app/components/shell/shell.html` — item "Planejamento" no sidenav

---

## Task 9: Codegen Orval + PlanningService

**Files:**
- Run: `npm run api:generate`
- Create: `planning.service.ts`

- [ ] **Passo 1: Rodar o codegen do Orval para gerar o client TypeScript**

```bash
cd frontend && npm run api:generate
```

Verificar que foram gerados arquivos relacionados a budget:
```bash
find frontend/src/app/core/api -name "*budget*" | sort
```

Esperado: arquivos como `budget/budget.service.ts` e tipos em `fintechSaaSAPI.schemas.ts`.

- [ ] **Passo 2: Verificar os tipos gerados**

```bash
grep -n "BudgetCycleResponse\|BudgetItemResponse\|RecurringBudgetItem" \
  frontend/src/app/core/api/fintechSaaSAPI.schemas.ts | head -20
```

Anotar o nome exato dos tipos para usar nos componentes.

- [ ] **Passo 3: Criar planning.service.ts**

Este service é uma fachada fina sobre os clients Orval gerados, adicionando apenas o tratamento de tenant settings.

```typescript
// frontend/src/app/features/planning/planning.service.ts
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { BudgetService } from '../../core/api/budget/budget.service';
import { TenantService } from '../../core/api/tenant/tenant.service';
import {
  BudgetCycleOpenRequest,
  BudgetCycleResponse,
  BudgetItemCreateRequest,
  BudgetItemLinkRequest,
  BudgetItemResponse,
  BudgetItemUpdateRequest,
  RecurringBudgetItemRequest,
  RecurringBudgetItemResponse,
  TenantSettingsPatchRequest,
} from '../../core/api/fintechSaaSAPI.schemas';

@Injectable({ providedIn: 'root' })
export class PlanningService {
  private readonly budget = inject(BudgetService);
  private readonly tenant = inject(TenantService);

  getCurrentCycle(): Observable<BudgetCycleResponse> {
    return this.budget.getCurrentBudgetCycle();
  }

  openCycle(req: BudgetCycleOpenRequest): Observable<BudgetCycleResponse> {
    return this.budget.openBudgetCycle(req);
  }

  closeCycle(id: string): Observable<BudgetCycleResponse> {
    return this.budget.closeBudgetCycle(id);
  }

  listCycles(page = 0, size = 12) {
    return this.budget.listBudgetCycles({ page, size });
  }

  getCycle(id: string): Observable<BudgetCycleResponse> {
    return this.budget.getBudgetCycle(id);
  }

  syncInstallments(id: string): Observable<BudgetCycleResponse> {
    return this.budget.syncInstallments(id);
  }

  createItem(cycleId: string, req: BudgetItemCreateRequest): Observable<BudgetItemResponse> {
    return this.budget.createBudgetItem(cycleId, req);
  }

  updateItem(id: string, req: BudgetItemUpdateRequest): Observable<BudgetItemResponse> {
    return this.budget.updateBudgetItem(id, req);
  }

  deleteItem(id: string): Observable<void> {
    return this.budget.deleteBudgetItem(id);
  }

  linkItem(id: string, req: BudgetItemLinkRequest): Observable<BudgetItemResponse> {
    return this.budget.linkBudgetItem(id, req);
  }

  unlinkItem(id: string): Observable<BudgetItemResponse> {
    return this.budget.unlinkBudgetItem(id);
  }

  listRecurring(): Observable<RecurringBudgetItemResponse[]> {
    return this.budget.listRecurringBudgetItems();
  }

  createRecurring(req: RecurringBudgetItemRequest): Observable<RecurringBudgetItemResponse> {
    return this.budget.createRecurringBudgetItem(req);
  }

  updateRecurring(id: string, req: RecurringBudgetItemRequest): Observable<RecurringBudgetItemResponse> {
    return this.budget.updateRecurringBudgetItem(id, req);
  }

  deleteRecurring(id: string): Observable<void> {
    return this.budget.deleteRecurringBudgetItem(id);
  }

  patchTenantSettings(req: TenantSettingsPatchRequest): Observable<void> {
    return this.tenant.patchTenantSettings(req);
  }
}
```

> **Nota:** Se o Orval gerar os services em namespaces diferentes (ex: `BudgetsService`), ajustar o import conforme os arquivos gerados. Use `grep -r "openBudgetCycle\|getCurrentBudgetCycle" frontend/src/app/core/api` para encontrar o service correto.

- [ ] **Passo 4: Verificar que compila**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Esperado: sem erros de tipo.

- [ ] **Passo 5: Commit**

```bash
git add frontend/src/app/core/api/ \
        frontend/src/app/features/planning/planning.service.ts
git commit -m "feat(planning): adiciona codegen Orval e PlanningService"
```

---

## Task 10: Utilitários + Testes (budget-cycle.utils.ts)

Esta função é pura (sem injeção Angular) — testável diretamente com Vitest.

**Files:**
- Create: `budget-cycle.utils.ts`, `budget-cycle.utils.spec.ts`

- [ ] **Passo 1: Escrever os testes primeiro**

```typescript
// frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.spec.ts
import { describe, it, expect } from 'vitest';
import { buildSummary } from './budget-cycle.utils';
import { BudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';

function item(overrides: Partial<BudgetItemResponse>): BudgetItemResponse {
  return {
    id: '1',
    description: 'Item',
    amount: 100,
    type: 'EXPENSE',
    expectedDate: '2026-06-10',
    source: 'MANUAL',
    status: 'PENDING',
    ...overrides,
  } as BudgetItemResponse;
}

describe('buildSummary', () => {
  it('retorna zeros quando lista está vazia', () => {
    const s = buildSummary([], 0);
    expect(s.plannedIncome).toBe(0);
    expect(s.plannedExpense).toBe(0);
    expect(s.projectedBalance).toBe(0);
    expect(s.currentBalance).toBe(0);
    expect(s.pendingCount).toBe(0);
  });

  it('soma corretamente receitas planejadas', () => {
    const items = [
      item({ type: 'INCOME', amount: 3000, status: 'PENDING' }),
      item({ type: 'INCOME', amount: 5000, status: 'PENDING' }),
    ];
    const s = buildSummary(items, 0);
    expect(s.plannedIncome).toBe(8000);
    expect(s.plannedExpense).toBe(0);
  });

  it('calcula saldo projetado: openingBalance + plannedIncome - plannedExpense', () => {
    const items = [
      item({ type: 'INCOME', amount: 8000, status: 'PENDING' }),
      item({ type: 'EXPENSE', amount: 5400, status: 'PENDING' }),
    ];
    const s = buildSummary(items, 1000);
    expect(s.projectedBalance).toBe(3600); // 1000 + 8000 - 5400
  });

  it('separa realizado de pendente corretamente', () => {
    const items = [
      item({ type: 'INCOME', amount: 8000, status: 'REALIZED' }),
      item({ type: 'EXPENSE', amount: 2100, status: 'REALIZED' }),
      item({ type: 'EXPENSE', amount: 3300, status: 'PENDING' }),
    ];
    const s = buildSummary(items, 0);
    expect(s.realizedIncome).toBe(8000);
    expect(s.realizedExpense).toBe(2100);
    expect(s.currentBalance).toBe(5900); // 0 + 8000 - 2100
    expect(s.pendingCount).toBe(1);
  });

  it('ignora itens com status SKIPPED no realized', () => {
    const items = [
      item({ type: 'EXPENSE', amount: 500, status: 'SKIPPED' }),
    ];
    const s = buildSummary(items, 0);
    expect(s.realizedExpense).toBe(0);
    expect(s.pendingCount).toBe(0);
  });
});
```

- [ ] **Passo 2: Rodar o teste (deve falhar)**

```bash
cd frontend && npm test -- budget-cycle.utils 2>&1 | tail -10
```

Esperado: falha porque o arquivo não existe.

- [ ] **Passo 3: Criar budget-cycle.utils.ts**

```typescript
// frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.ts
import { BudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';

export interface CycleSummary {
  plannedIncome: number;
  plannedExpense: number;
  projectedBalance: number;
  realizedIncome: number;
  realizedExpense: number;
  currentBalance: number;
  pendingCount: number;
}

export function buildSummary(items: BudgetItemResponse[], openingBalance: number): CycleSummary {
  let plannedIncome = 0, plannedExpense = 0;
  let realizedIncome = 0, realizedExpense = 0;
  let pendingCount = 0;

  for (const item of items) {
    const isIncome = item.type === 'INCOME';

    if (isIncome) plannedIncome  += item.amount;
    else          plannedExpense += item.amount;

    if (item.status === 'REALIZED') {
      if (isIncome) realizedIncome  += item.amount;
      else          realizedExpense += item.amount;
    }

    if (item.status === 'PENDING') pendingCount++;
  }

  return {
    plannedIncome,
    plannedExpense,
    projectedBalance: openingBalance + plannedIncome - plannedExpense,
    realizedIncome,
    realizedExpense,
    currentBalance: openingBalance + realizedIncome - realizedExpense,
    pendingCount,
  };
}
```

- [ ] **Passo 4: Rodar os testes**

```bash
cd frontend && npm test -- budget-cycle.utils 2>&1 | tail -10
```

Esperado: `5 passed`.

- [ ] **Passo 5: Commit**

```bash
git add frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.ts \
        frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.spec.ts
git commit -m "feat(planning): adiciona utilitário buildSummary com testes"
```

---

## Task 11: Rotas de Planning + Shell nav

**Files:**
- Create: `planning.routes.ts`
- Modify: `app.routes.ts`, `shell.html`

- [ ] **Passo 1: Criar planning.routes.ts**

```typescript
// frontend/src/app/features/planning/planning.routes.ts
import { Routes } from '@angular/router';

export const planningRoutes: Routes = [
  { path: '', redirectTo: 'current', pathMatch: 'full' },
  {
    path: 'current',
    loadComponent: () =>
      import('./budget-cycle-current/budget-cycle-current').then(m => m.BudgetCycleCurrentComponent),
  },
  {
    path: 'cycles',
    loadComponent: () =>
      import('./budget-cycle-list/budget-cycle-list').then(m => m.BudgetCycleList),
  },
  {
    path: 'cycles/:id',
    loadComponent: () =>
      import('./budget-cycle-detail/budget-cycle-detail').then(m => m.BudgetCycleDetail),
  },
  {
    path: 'recurring',
    loadComponent: () =>
      import('./recurring-item-list/recurring-item-list').then(m => m.RecurringItemList),
  },
];
```

- [ ] **Passo 2: Adicionar lazy route `/planning` em app.routes.ts**

Dentro do array `children` (após a rota de invoices), adicionar:

```typescript
{
  path: 'planning',
  loadChildren: () =>
    import('./features/planning/planning.routes').then(m => m.planningRoutes),
},
```

- [ ] **Passo 3: Adicionar item "Planejamento" no sidenav**

No arquivo `frontend/src/app/components/shell/shell.html`, localizar a seção de nav links e adicionar:

```html
<a mat-list-item routerLink="/planning" routerLinkActive="active-link">
  <mat-icon matListItemIcon>event_note</mat-icon>
  <span matListItemTitle>Planejamento</span>
</a>
```

Inserir após o item de Faturas (ou antes, na posição desejada na navegação).

- [ ] **Passo 4: Verificar compilação TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Esperado: sem erros.

- [ ] **Passo 5: Commit**

```bash
git add frontend/src/app/features/planning/planning.routes.ts \
        frontend/src/app/app.routes.ts \
        frontend/src/app/components/shell/shell.html
git commit -m "feat(planning): adiciona rotas lazy e item Planejamento no sidenav"
```

---

## Task 12: BudgetCycleCurrentComponent — tela principal

**Files:**
- Create: `budget-cycle-current.ts`, `budget-cycle-current.html`, `budget-cycle-current.scss`

- [ ] **Passo 1: Criar budget-cycle-current.ts**

```typescript
// frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.ts
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs/operators';

import { PlanningService } from '../planning.service';
import { BudgetCycleResponse, BudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { buildSummary } from './budget-cycle.utils';
import { BudgetItemFormComponent, BudgetItemFormResult } from '../budget-item-form/budget-item-form';
import { LinkTransactionDialogComponent, LinkTransactionDialogData } from '../link-transaction-dialog/link-transaction-dialog';

@Component({
  selector: 'app-budget-cycle-current',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
    MatButtonModule, MatCardModule, MatChipsModule, MatExpansionModule,
    MatIconModule, MatSnackBarModule, MatTableModule, MatTooltipModule,
  ],
  templateUrl: './budget-cycle-current.html',
  styleUrl: './budget-cycle-current.scss',
})
export class BudgetCycleCurrentComponent implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly cycle = signal<BudgetCycleResponse | null>(null);
  readonly items = signal<BudgetItemResponse[]>([]);
  readonly loading = signal(true);
  readonly closing = signal(false);

  readonly incomeItems  = computed(() => this.items().filter(i => i.type === 'INCOME'));
  readonly expenseItems = computed(() => this.items().filter(i => i.type === 'EXPENSE' && i.source !== 'INSTALLMENT'));
  readonly installmentItems = computed(() => this.items().filter(i => i.source === 'INSTALLMENT'));
  readonly summary = computed(() => buildSummary(this.items(), this.cycle()?.openingBalance ?? 0));

  ngOnInit(): void {
    this.loadCurrentCycle();
  }

  private loadCurrentCycle(): void {
    this.loading.set(true);
    this.planningService.getCurrentCycle()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: c => {
          this.cycle.set(c);
          this.items.set(c.items ?? []);
        },
        error: err => {
          if (err.status === 404) {
            this.cycle.set(null);
          }
        },
      });
  }

  openCycle(): void {
    const ref = this.dialog.open(BudgetItemFormComponent, {
      width: '400px',
      data: { mode: 'openCycle' },
    });
    ref.afterClosed().subscribe((referenceMonth?: string) => {
      if (!referenceMonth) return;
      this.planningService.openCycle({ referenceMonth }).subscribe({
        next: c => {
          this.cycle.set(c);
          this.items.set(c.items ?? []);
          this.snackBar.open('Ciclo aberto com sucesso.', 'OK', { duration: 3000 });
        },
        error: () => this.snackBar.open('Erro ao abrir ciclo.', 'OK', { duration: 3000 }),
      });
    });
  }

  closeCycle(): void {
    const id = this.cycle()?.id;
    if (!id) return;
    this.closing.set(true);
    this.planningService.closeCycle(id)
      .pipe(finalize(() => this.closing.set(false)))
      .subscribe({
        next: c => {
          this.cycle.set(c);
          this.snackBar.open('Ciclo fechado.', 'OK', { duration: 3000 });
        },
        error: () => this.snackBar.open('Erro ao fechar ciclo.', 'OK', { duration: 3000 }),
      });
  }

  addItem(): void {
    const cycleId = this.cycle()?.id;
    if (!cycleId) return;
    const ref = this.dialog.open(BudgetItemFormComponent, {
      width: '500px',
      data: { cycleId },
    });
    ref.afterClosed().subscribe((result?: BudgetItemFormResult) => {
      if (!result) return;
      this.planningService.createItem(cycleId, result).subscribe({
        next: item => {
          this.items.update(list => [...list, item]);
          this.snackBar.open('Item adicionado.', 'OK', { duration: 2000 });
        },
      });
    });
  }

  linkTransaction(item: BudgetItemResponse): void {
    const cycleId = this.cycle()?.id;
    if (!cycleId) return;
    const ref = this.dialog.open(LinkTransactionDialogComponent, {
      width: '600px',
      data: { item, cycleId } satisfies LinkTransactionDialogData,
    });
    ref.afterClosed().subscribe((transactionId?: string) => {
      if (!transactionId) return;
      this.planningService.linkItem(item.id, { transactionId }).subscribe({
        next: updated => this.replaceItem(updated),
      });
    });
  }

  unlinkTransaction(item: BudgetItemResponse): void {
    this.planningService.unlinkItem(item.id).subscribe({
      next: updated => this.replaceItem(updated),
    });
  }

  deleteItem(item: BudgetItemResponse): void {
    this.planningService.deleteItem(item.id).subscribe({
      next: () => this.items.update(list => list.filter(i => i.id !== item.id)),
    });
  }

  private replaceItem(updated: BudgetItemResponse): void {
    this.items.update(list => list.map(i => i.id === updated.id ? updated : i));
  }
}
```

- [ ] **Passo 2: Criar budget-cycle-current.html**

```html
<!-- frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html -->
<div class="planning-container">

  <!-- Estado de carregamento -->
  @if (loading()) {
    <p>Carregando...</p>
  }

  <!-- Empty state: nenhum ciclo aberto -->
  @if (!loading() && !cycle()) {
    <mat-card class="empty-state-card">
      <mat-card-content>
        <mat-icon>event_note</mat-icon>
        <h2>Nenhum ciclo de planejamento aberto</h2>
        <p>Abra um ciclo para começar a planejar o mês.</p>
        <button mat-flat-button color="primary" (click)="openCycle()">
          Abrir ciclo
        </button>
      </mat-card-content>
    </mat-card>
  }

  <!-- Ciclo aberto -->
  @if (!loading() && cycle()) {
    <!-- Cabeçalho -->
    <div class="cycle-header">
      <div class="cycle-dates">
        <h1>Planejamento</h1>
        <span class="period">{{ cycle()!.startDate | date:'dd/MM/yyyy' }} –
          {{ cycle()!.endDate | date:'dd/MM/yyyy' }}</span>
      </div>
      <div class="cycle-actions">
        <mat-chip [class.chip-open]="cycle()!.status === 'OPEN'"
                  [class.chip-closed]="cycle()!.status === 'CLOSED'">
          {{ cycle()!.status === 'OPEN' ? 'Aberto' : 'Fechado' }}
        </mat-chip>
        @if (cycle()!.status === 'OPEN') {
          <button mat-stroked-button [disabled]="closing()" (click)="closeCycle()">
            Fechar ciclo
          </button>
        }
      </div>
    </div>

    <!-- Cards de resumo -->
    <div class="summary-grid">
      <mat-card>
        <mat-card-header><mat-card-title>Receitas</mat-card-title></mat-card-header>
        <mat-card-content>
          <div class="summary-line">
            <span>Previsto</span>
            <strong>{{ summary().plannedIncome | currency:'BRL' }}</strong>
          </div>
          <div class="summary-line realized">
            <span>Realizado</span>
            <strong>{{ summary().realizedIncome | currency:'BRL' }}</strong>
          </div>
        </mat-card-content>
      </mat-card>

      <mat-card>
        <mat-card-header><mat-card-title>Despesas</mat-card-title></mat-card-header>
        <mat-card-content>
          <div class="summary-line">
            <span>Previsto</span>
            <strong>{{ summary().plannedExpense | currency:'BRL' }}</strong>
          </div>
          <div class="summary-line realized">
            <span>Realizado</span>
            <strong>{{ summary().realizedExpense | currency:'BRL' }}</strong>
          </div>
        </mat-card-content>
      </mat-card>

      <mat-card>
        <mat-card-header><mat-card-title>Saldo</mat-card-title></mat-card-header>
        <mat-card-content>
          <div class="summary-line">
            <span>Abertura</span>
            <strong>{{ cycle()!.openingBalance | currency:'BRL' }}</strong>
          </div>
          <div class="summary-line">
            <span>Projetado</span>
            <strong>{{ summary().projectedBalance | currency:'BRL' }}</strong>
          </div>
          <div class="summary-line realized">
            <span>Atual</span>
            <strong>{{ summary().currentBalance | currency:'BRL' }}</strong>
          </div>
        </mat-card-content>
      </mat-card>
    </div>

    <!-- Botão adicionar item -->
    @if (cycle()!.status === 'OPEN') {
      <button mat-flat-button color="primary" (click)="addItem()" class="add-btn">
        <mat-icon>add</mat-icon> Adicionar item
      </button>
    }

    <!-- Receitas -->
    <mat-expansion-panel [expanded]="true">
      <mat-expansion-panel-header>
        <mat-panel-title>
          <mat-icon>trending_up</mat-icon> Receitas ({{ incomeItems().length }})
        </mat-panel-title>
      </mat-expansion-panel-header>
      <ng-template matExpansionPanelContent>
        <table mat-table [dataSource]="incomeItems()">
          <ng-container matColumnDef="description">
            <th mat-header-cell *matHeaderCellDef>Descrição</th>
            <td mat-cell *matCellDef="let item">{{ item.description }}</td>
          </ng-container>
          <ng-container matColumnDef="expectedDate">
            <th mat-header-cell *matHeaderCellDef>Data</th>
            <td mat-cell *matCellDef="let item">{{ item.expectedDate | date:'dd/MM' }}</td>
          </ng-container>
          <ng-container matColumnDef="amount">
            <th mat-header-cell *matHeaderCellDef>Valor</th>
            <td mat-cell *matCellDef="let item">{{ item.amount | currency:'BRL' }}</td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let item">
              <mat-chip>{{ item.status }}</mat-chip>
            </td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let item">
              @if (!item.transactionId && cycle()!.status === 'OPEN') {
                <button mat-icon-button matTooltip="Vincular transação"
                        (click)="linkTransaction(item)">
                  <mat-icon>link</mat-icon>
                </button>
              }
              @if (item.transactionId) {
                <button mat-icon-button matTooltip="Desvincular"
                        (click)="unlinkTransaction(item)">
                  <mat-icon>link_off</mat-icon>
                </button>
              }
              @if (item.source !== 'INSTALLMENT' && cycle()!.status === 'OPEN') {
                <button mat-icon-button matTooltip="Remover" (click)="deleteItem(item)">
                  <mat-icon>delete_outline</mat-icon>
                </button>
              }
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="['description','expectedDate','amount','status','actions']"></tr>
          <tr mat-row *matRowDef="let row; columns: ['description','expectedDate','amount','status','actions']"></tr>
        </table>
      </ng-template>
    </mat-expansion-panel>

    <!-- Despesas Fixas -->
    <mat-expansion-panel [expanded]="true">
      <mat-expansion-panel-header>
        <mat-panel-title>
          <mat-icon>trending_down</mat-icon> Despesas fixas ({{ expenseItems().length }})
        </mat-panel-title>
      </mat-expansion-panel-header>
      <ng-template matExpansionPanelContent>
        <table mat-table [dataSource]="expenseItems()">
          <ng-container matColumnDef="description">
            <th mat-header-cell *matHeaderCellDef>Descrição</th>
            <td mat-cell *matCellDef="let item">{{ item.description }}</td>
          </ng-container>
          <ng-container matColumnDef="expectedDate">
            <th mat-header-cell *matHeaderCellDef>Data</th>
            <td mat-cell *matCellDef="let item">{{ item.expectedDate | date:'dd/MM' }}</td>
          </ng-container>
          <ng-container matColumnDef="amount">
            <th mat-header-cell *matHeaderCellDef>Valor</th>
            <td mat-cell *matCellDef="let item">{{ item.amount | currency:'BRL' }}</td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let item"><mat-chip>{{ item.status }}</mat-chip></td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let item">
              @if (!item.transactionId && cycle()!.status === 'OPEN') {
                <button mat-icon-button matTooltip="Vincular transação"
                        (click)="linkTransaction(item)">
                  <mat-icon>link</mat-icon>
                </button>
              }
              @if (item.transactionId) {
                <button mat-icon-button (click)="unlinkTransaction(item)">
                  <mat-icon>link_off</mat-icon>
                </button>
              }
              @if (cycle()!.status === 'OPEN') {
                <button mat-icon-button (click)="deleteItem(item)">
                  <mat-icon>delete_outline</mat-icon>
                </button>
              }
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="['description','expectedDate','amount','status','actions']"></tr>
          <tr mat-row *matRowDef="let row; columns: ['description','expectedDate','amount','status','actions']"></tr>
        </table>
      </ng-template>
    </mat-expansion-panel>

    <!-- Parcelas do Cartão -->
    <mat-expansion-panel>
      <mat-expansion-panel-header>
        <mat-panel-title>
          <mat-icon>credit_card</mat-icon> Parcelas do cartão ({{ installmentItems().length }})
        </mat-panel-title>
      </mat-expansion-panel-header>
      <ng-template matExpansionPanelContent>
        <table mat-table [dataSource]="installmentItems()">
          <ng-container matColumnDef="description">
            <th mat-header-cell *matHeaderCellDef>Descrição</th>
            <td mat-cell *matCellDef="let item">{{ item.description }}</td>
          </ng-container>
          <ng-container matColumnDef="expectedDate">
            <th mat-header-cell *matHeaderCellDef>Data</th>
            <td mat-cell *matCellDef="let item">{{ item.expectedDate | date:'dd/MM' }}</td>
          </ng-container>
          <ng-container matColumnDef="amount">
            <th mat-header-cell *matHeaderCellDef>Valor</th>
            <td mat-cell *matCellDef="let item">{{ item.amount | currency:'BRL' }}</td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let item"><mat-chip>{{ item.status }}</mat-chip></td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="['description','expectedDate','amount','status']"></tr>
          <tr mat-row *matRowDef="let row; columns: ['description','expectedDate','amount','status']"></tr>
        </table>
      </ng-template>
    </mat-expansion-panel>
  }
</div>
```

- [ ] **Passo 3: Criar budget-cycle-current.scss**

```scss
.planning-container {
  padding: 24px;
  max-width: 960px;
  margin: 0 auto;
}

.cycle-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;

  .cycle-dates h1 { margin: 0; }
  .period { color: var(--mat-sys-on-surface-variant); }
  .cycle-actions { display: flex; align-items: center; gap: 12px; }
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.summary-line {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  &.realized strong { color: var(--mat-sys-primary); }
}

.empty-state-card {
  text-align: center;
  padding: 40px;
  mat-icon { font-size: 48px; width: 48px; height: 48px; opacity: 0.4; }
}

.add-btn { margin-bottom: 16px; }

mat-expansion-panel { margin-bottom: 12px; }
```

- [ ] **Passo 4: Verificar TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -30
```

Corrigir quaisquer erros de tipo antes de continuar.

- [ ] **Passo 5: Commit**

```bash
git add frontend/src/app/features/planning/budget-cycle-current/
git commit -m "feat(planning): adiciona BudgetCycleCurrentComponent com resumo e seções expansíveis"
```

---

## Task 13: BudgetItemFormComponent (dialog)

**Files:**
- Create: `budget-item-form.ts`, `budget-item-form.html`

- [ ] **Passo 1: Criar budget-item-form.ts**

```typescript
// frontend/src/app/features/planning/budget-item-form/budget-item-form.ts
import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { BudgetItemCreateRequest } from '../../../core/api/fintechSaaSAPI.schemas';

export type BudgetItemFormResult = BudgetItemCreateRequest;

export interface BudgetItemFormData {
  cycleId: string;
  /** mode='openCycle' usa o dialog para capturar referenceMonth */
  mode?: 'openCycle';
}

@Component({
  selector: 'app-budget-item-form',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatButtonModule, MatDatepickerModule, MatDialogModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
  ],
  templateUrl: './budget-item-form.html',
})
export class BudgetItemFormComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<BudgetItemFormComponent>);
  readonly data: BudgetItemFormData = inject(MAT_DIALOG_DATA);

  readonly isOpenCycleMode = signal(false);

  readonly cycleForm = this.fb.group({
    referenceMonth: ['', [Validators.required, Validators.pattern(/^\d{4}-\d{2}$/)]],
  });

  readonly itemForm = this.fb.group({
    description: ['', Validators.required],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    type: ['EXPENSE', Validators.required],
    expectedDate: [null as Date | null, Validators.required],
  });

  ngOnInit(): void {
    this.isOpenCycleMode.set(this.data?.mode === 'openCycle');
  }

  onSubmit(): void {
    if (this.isOpenCycleMode()) {
      if (this.cycleForm.invalid) return;
      this.dialogRef.close(this.cycleForm.value.referenceMonth);
    } else {
      if (this.itemForm.invalid) return;
      const v = this.itemForm.getRawValue();
      const result: BudgetItemCreateRequest = {
        description: v.description!,
        amount: v.amount!,
        type: v.type as 'INCOME' | 'EXPENSE',
        expectedDate: v.expectedDate!.toISOString().substring(0, 10),
      };
      this.dialogRef.close(result);
    }
  }

  onCancel(): void {
    this.dialogRef.close();
  }
}
```

- [ ] **Passo 2: Criar budget-item-form.html**

```html
<!-- frontend/src/app/features/planning/budget-item-form/budget-item-form.html -->
@if (isOpenCycleMode()) {
  <h2 mat-dialog-title>Abrir ciclo de planejamento</h2>
  <mat-dialog-content>
    <form [formGroup]="cycleForm">
      <mat-form-field appearance="outline" style="width:100%">
        <mat-label>Mês de referência (yyyy-MM)</mat-label>
        <input matInput formControlName="referenceMonth" placeholder="2026-06" />
        <mat-error>Formato esperado: yyyy-MM (ex: 2026-06)</mat-error>
      </mat-form-field>
    </form>
  </mat-dialog-content>
  <mat-dialog-actions align="end">
    <button mat-button type="button" (click)="onCancel()">Cancelar</button>
    <button mat-flat-button color="primary" type="button"
            [disabled]="cycleForm.invalid" (click)="onSubmit()">Abrir</button>
  </mat-dialog-actions>
} @else {
  <h2 mat-dialog-title>Adicionar item</h2>
  <mat-dialog-content>
    <form [formGroup]="itemForm" style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
      <mat-form-field appearance="outline">
        <mat-label>Descrição</mat-label>
        <input matInput formControlName="description" />
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Tipo</mat-label>
        <mat-select formControlName="type">
          <mat-option value="INCOME">Receita</mat-option>
          <mat-option value="EXPENSE">Despesa</mat-option>
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Valor</mat-label>
        <span matTextPrefix>R$&nbsp;</span>
        <input matInput type="number" formControlName="amount" min="0.01" />
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Data prevista</mat-label>
        <input matInput [matDatepicker]="picker" formControlName="expectedDate" />
        <mat-datepicker-toggle matSuffix [for]="picker"></mat-datepicker-toggle>
        <mat-datepicker #picker></mat-datepicker>
      </mat-form-field>
    </form>
  </mat-dialog-content>
  <mat-dialog-actions align="end">
    <button mat-button type="button" (click)="onCancel()">Cancelar</button>
    <button mat-flat-button color="primary" type="button"
            [disabled]="itemForm.invalid" (click)="onSubmit()">Salvar</button>
  </mat-dialog-actions>
}
```

- [ ] **Passo 3: Compilar**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

- [ ] **Passo 4: Commit**

```bash
git add frontend/src/app/features/planning/budget-item-form/
git commit -m "feat(planning): adiciona BudgetItemFormComponent (dialog)"
```

---

## Task 14: LinkTransactionDialogComponent

**Files:**
- Create: `link-transaction-dialog.ts`, `link-transaction-dialog.html`

- [ ] **Passo 1: Criar link-transaction-dialog.ts**

```typescript
// frontend/src/app/features/planning/link-transaction-dialog/link-transaction-dialog.ts
import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';

import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { BudgetItemResponse, TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export interface LinkTransactionDialogData {
  item: BudgetItemResponse;
  cycleId: string;
}

@Component({
  selector: 'app-link-transaction-dialog',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatButtonModule, MatDialogModule, MatIconModule, MatTableModule,
  ],
  templateUrl: './link-transaction-dialog.html',
})
export class LinkTransactionDialogComponent implements OnInit {
  private readonly txService = inject(TransactionsService);
  private readonly dialogRef = inject(MatDialogRef<LinkTransactionDialogComponent>);
  readonly data: LinkTransactionDialogData = inject(MAT_DIALOG_DATA);

  readonly transactions = signal<TransactionResponseDTO[]>([]);
  readonly loading = signal(true);

  displayedColumns = ['date', 'description', 'amount', 'select'];

  ngOnInit(): void {
    // Busca transações do mesmo tipo e período do ciclo
    this.txService.listTransactions({ type: this.data.item.type } as any)
      .subscribe({
        next: txs => {
          this.transactions.set(txs);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  select(tx: TransactionResponseDTO): void {
    this.dialogRef.close(tx.id);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
```

- [ ] **Passo 2: Criar link-transaction-dialog.html**

```html
<!-- frontend/src/app/features/planning/link-transaction-dialog/link-transaction-dialog.html -->
<h2 mat-dialog-title>Vincular transação — {{ data.item.description }}</h2>
<mat-dialog-content>
  @if (loading()) {
    <p>Carregando transações...</p>
  } @else if (transactions().length === 0) {
    <p>Nenhuma transação encontrada para vincular.</p>
  } @else {
    <table mat-table [dataSource]="transactions()" style="width:100%">
      <ng-container matColumnDef="date">
        <th mat-header-cell *matHeaderCellDef>Data</th>
        <td mat-cell *matCellDef="let tx">{{ tx.date | date:'dd/MM/yyyy' }}</td>
      </ng-container>
      <ng-container matColumnDef="description">
        <th mat-header-cell *matHeaderCellDef>Descrição</th>
        <td mat-cell *matCellDef="let tx">{{ tx.description }}</td>
      </ng-container>
      <ng-container matColumnDef="amount">
        <th mat-header-cell *matHeaderCellDef>Valor</th>
        <td mat-cell *matCellDef="let tx">{{ tx.amount | currency:'BRL' }}</td>
      </ng-container>
      <ng-container matColumnDef="select">
        <th mat-header-cell *matHeaderCellDef></th>
        <td mat-cell *matCellDef="let tx">
          <button mat-icon-button (click)="select(tx)">
            <mat-icon>check_circle_outline</mat-icon>
          </button>
        </td>
      </ng-container>
      <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
      <tr mat-row *matRowDef="let row; columns: displayedColumns" style="cursor:pointer"
          (click)="select(row)"></tr>
    </table>
  }
</mat-dialog-content>
<mat-dialog-actions align="end">
  <button mat-button type="button" (click)="cancel()">Cancelar</button>
</mat-dialog-actions>
```

- [ ] **Passo 3: Compilar**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

- [ ] **Passo 4: Commit**

```bash
git add frontend/src/app/features/planning/link-transaction-dialog/
git commit -m "feat(planning): adiciona LinkTransactionDialogComponent"
```

---

## Task 15: RecurringItemList + RecurringItemForm

**Files:**
- Create: 4 arquivos

- [ ] **Passo 1: Criar recurring-item-form.ts**

```typescript
// frontend/src/app/features/planning/recurring-item-form/recurring-item-form.ts
import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RecurringBudgetItemRequest, RecurringBudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';

@Component({
  selector: 'app-recurring-item-form',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule,
  ],
  templateUrl: './recurring-item-form.html',
})
export class RecurringItemFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<RecurringItemFormComponent>);
  readonly existing: RecurringBudgetItemResponse | null = inject(MAT_DIALOG_DATA, { optional: true });

  readonly form = this.fb.group({
    description: [this.existing?.description ?? '', Validators.required],
    amount: [this.existing?.amount ?? null as number | null, [Validators.required, Validators.min(0.01)]],
    type: [this.existing?.type ?? 'EXPENSE', Validators.required],
    dayOfMonth: [this.existing?.dayOfMonth ?? 1, [Validators.required, Validators.min(1), Validators.max(28)]],
  });

  onSubmit(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const result: RecurringBudgetItemRequest = {
      description: v.description!,
      amount: v.amount!,
      type: v.type as 'INCOME' | 'EXPENSE',
      dayOfMonth: v.dayOfMonth!,
    };
    this.dialogRef.close(result);
  }

  onCancel(): void { this.dialogRef.close(); }
}
```

- [ ] **Passo 2: Criar recurring-item-form.html**

```html
<!-- frontend/src/app/features/planning/recurring-item-form/recurring-item-form.html -->
<h2 mat-dialog-title>{{ existing ? 'Editar' : 'Novo' }} template recorrente</h2>
<mat-dialog-content>
  <form [formGroup]="form" style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
    <mat-form-field appearance="outline">
      <mat-label>Descrição</mat-label>
      <input matInput formControlName="description" />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Tipo</mat-label>
      <mat-select formControlName="type">
        <mat-option value="INCOME">Receita</mat-option>
        <mat-option value="EXPENSE">Despesa</mat-option>
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Valor</mat-label>
      <span matTextPrefix>R$&nbsp;</span>
      <input matInput type="number" formControlName="amount" min="0.01" />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Dia do mês (1–28)</mat-label>
      <input matInput type="number" formControlName="dayOfMonth" min="1" max="28" />
      <mat-hint>Dia em que o item ocorre tipicamente.</mat-hint>
    </mat-form-field>
  </form>
</mat-dialog-content>
<mat-dialog-actions align="end">
  <button mat-button type="button" (click)="onCancel()">Cancelar</button>
  <button mat-flat-button color="primary" type="button"
          [disabled]="form.invalid" (click)="onSubmit()">Salvar</button>
</mat-dialog-actions>
```

- [ ] **Passo 3: Criar recurring-item-list.ts**

```typescript
// frontend/src/app/features/planning/recurring-item-list/recurring-item-list.ts
import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';

import { PlanningService } from '../planning.service';
import { RecurringBudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { RecurringItemFormComponent } from '../recurring-item-form/recurring-item-form';

@Component({
  selector: 'app-recurring-item-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe,
    MatButtonModule, MatIconModule, MatSnackBarModule,
    MatTableModule, MatTooltipModule,
  ],
  templateUrl: './recurring-item-list.html',
})
export class RecurringItemList implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly items = signal<RecurringBudgetItemResponse[]>([]);
  readonly loading = signal(true);

  displayedColumns = ['day', 'description', 'type', 'amount', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.planningService.listRecurring().subscribe({
      next: items => { this.items.set(items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openForm(existing?: RecurringBudgetItemResponse): void {
    const ref = this.dialog.open(RecurringItemFormComponent, {
      width: '460px',
      data: existing ?? null,
    });
    ref.afterClosed().subscribe(result => {
      if (!result) return;
      const obs$ = existing
        ? this.planningService.updateRecurring(existing.id, result)
        : this.planningService.createRecurring(result);
      obs$.subscribe({
        next: () => {
          this.load();
          this.snackBar.open(existing ? 'Template atualizado.' : 'Template criado.', 'OK', { duration: 2000 });
        },
      });
    });
  }

  deactivate(item: RecurringBudgetItemResponse): void {
    this.planningService.deleteRecurring(item.id).subscribe({
      next: () => {
        this.items.update(list => list.filter(i => i.id !== item.id));
        this.snackBar.open('Template desativado.', 'OK', { duration: 2000 });
      },
    });
  }
}
```

- [ ] **Passo 4: Criar recurring-item-list.html**

```html
<!-- frontend/src/app/features/planning/recurring-item-list/recurring-item-list.html -->
<div style="padding:24px;max-width:800px;margin:0 auto">
  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
    <h1 style="margin:0">Templates recorrentes</h1>
    <button mat-flat-button color="primary" (click)="openForm()">
      <mat-icon>add</mat-icon> Novo template
    </button>
  </div>

  @if (loading()) {
    <p>Carregando...</p>
  } @else if (items().length === 0) {
    <p>Nenhum template recorrente configurado.</p>
  } @else {
    <table mat-table [dataSource]="items()" style="width:100%">
      <ng-container matColumnDef="day">
        <th mat-header-cell *matHeaderCellDef>Dia</th>
        <td mat-cell *matCellDef="let item">{{ item.dayOfMonth }}</td>
      </ng-container>
      <ng-container matColumnDef="description">
        <th mat-header-cell *matHeaderCellDef>Descrição</th>
        <td mat-cell *matCellDef="let item">{{ item.description }}</td>
      </ng-container>
      <ng-container matColumnDef="type">
        <th mat-header-cell *matHeaderCellDef>Tipo</th>
        <td mat-cell *matCellDef="let item">{{ item.type === 'INCOME' ? 'Receita' : 'Despesa' }}</td>
      </ng-container>
      <ng-container matColumnDef="amount">
        <th mat-header-cell *matHeaderCellDef>Valor</th>
        <td mat-cell *matCellDef="let item">{{ item.amount | currency:'BRL' }}</td>
      </ng-container>
      <ng-container matColumnDef="actions">
        <th mat-header-cell *matHeaderCellDef></th>
        <td mat-cell *matCellDef="let item">
          <button mat-icon-button matTooltip="Editar" (click)="openForm(item)">
            <mat-icon>edit</mat-icon>
          </button>
          <button mat-icon-button matTooltip="Desativar" (click)="deactivate(item)">
            <mat-icon>delete_outline</mat-icon>
          </button>
        </td>
      </ng-container>
      <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
      <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
    </table>
  }
</div>
```

- [ ] **Passo 5: Compilar**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

- [ ] **Passo 6: Commit**

```bash
git add frontend/src/app/features/planning/recurring-item-list/ \
        frontend/src/app/features/planning/recurring-item-form/
git commit -m "feat(planning): adiciona gestão de templates recorrentes"
```

---

## Task 16: BudgetCycleList + BudgetCycleDetail

**Files:**
- Create: 4 arquivos

- [ ] **Passo 1: Criar budget-cycle-list.ts**

```typescript
// frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.ts
import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { PlanningService } from '../planning.service';
import { BudgetCycleResponse } from '../../../core/api/fintechSaaSAPI.schemas';

@Component({
  selector: 'app-budget-cycle-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
    MatButtonModule, MatChipsModule, MatIconModule, MatTableModule,
  ],
  templateUrl: './budget-cycle-list.html',
})
export class BudgetCycleList implements OnInit {
  private readonly planningService = inject(PlanningService);

  readonly cycles = signal<BudgetCycleResponse[]>([]);
  readonly loading = signal(true);

  displayedColumns = ['period', 'openingBalance', 'status', 'actions'];

  ngOnInit(): void {
    this.planningService.listCycles().subscribe({
      next: (page: any) => { this.cycles.set(page.content ?? []); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }
}
```

- [ ] **Passo 2: Criar budget-cycle-list.html**

```html
<!-- frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.html -->
<div style="padding:24px;max-width:800px;margin:0 auto">
  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
    <h1 style="margin:0">Histórico de ciclos</h1>
    <button mat-stroked-button routerLink="/planning/current">
      <mat-icon>arrow_back</mat-icon> Ciclo atual
    </button>
  </div>

  @if (loading()) {
    <p>Carregando...</p>
  } @else if (cycles().length === 0) {
    <p>Nenhum ciclo encontrado.</p>
  } @else {
    <table mat-table [dataSource]="cycles()" style="width:100%">
      <ng-container matColumnDef="period">
        <th mat-header-cell *matHeaderCellDef>Período</th>
        <td mat-cell *matCellDef="let c">
          {{ c.startDate | date:'dd/MM/yyyy' }} – {{ c.endDate | date:'dd/MM/yyyy' }}
        </td>
      </ng-container>
      <ng-container matColumnDef="openingBalance">
        <th mat-header-cell *matHeaderCellDef>Saldo inicial</th>
        <td mat-cell *matCellDef="let c">{{ c.openingBalance | currency:'BRL' }}</td>
      </ng-container>
      <ng-container matColumnDef="status">
        <th mat-header-cell *matHeaderCellDef>Status</th>
        <td mat-cell *matCellDef="let c"><mat-chip>{{ c.status }}</mat-chip></td>
      </ng-container>
      <ng-container matColumnDef="actions">
        <th mat-header-cell *matHeaderCellDef></th>
        <td mat-cell *matCellDef="let c">
          <a mat-icon-button [routerLink]="['/planning/cycles', c.id]">
            <mat-icon>visibility</mat-icon>
          </a>
        </td>
      </ng-container>
      <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
      <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
    </table>
  }
</div>
```

- [ ] **Passo 3: Criar budget-cycle-detail.ts**

```typescript
// frontend/src/app/features/planning/budget-cycle-detail/budget-cycle-detail.ts
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatTableModule } from '@angular/material/table';
import { PlanningService } from '../planning.service';
import { BudgetCycleResponse, BudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { buildSummary } from '../budget-cycle-current/budget-cycle.utils';

@Component({
  selector: 'app-budget-cycle-detail',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatButtonModule, MatCardModule, MatChipsModule, MatTableModule,
  ],
  templateUrl: './budget-cycle-detail.html',
})
export class BudgetCycleDetail implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly planningService = inject(PlanningService);

  readonly cycle = signal<BudgetCycleResponse | null>(null);
  readonly items = signal<BudgetItemResponse[]>([]);
  readonly loading = signal(true);

  readonly summary = computed(() =>
    buildSummary(this.items(), this.cycle()?.openingBalance ?? 0));

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.planningService.getCycle(id).subscribe({
      next: c => { this.cycle.set(c); this.items.set(c.items ?? []); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }
}
```

- [ ] **Passo 4: Criar budget-cycle-detail.html**

```html
<!-- frontend/src/app/features/planning/budget-cycle-detail/budget-cycle-detail.html -->
<div style="padding:24px;max-width:960px;margin:0 auto">
  @if (loading()) {
    <p>Carregando...</p>
  } @else if (cycle()) {
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:24px">
      <div>
        <h1 style="margin:0">Ciclo — {{ cycle()!.startDate | date:'dd/MM/yyyy' }}
          a {{ cycle()!.endDate | date:'dd/MM/yyyy' }}</h1>
      </div>
      <mat-chip>{{ cycle()!.status }}</mat-chip>
    </div>

    <!-- Resumo -->
    <div style="display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin-bottom:24px">
      <mat-card>
        <mat-card-header><mat-card-title>Receitas</mat-card-title></mat-card-header>
        <mat-card-content>
          <div style="display:flex;justify-content:space-between">
            <span>Previsto</span><strong>{{ summary().plannedIncome | currency:'BRL' }}</strong>
          </div>
          <div style="display:flex;justify-content:space-between">
            <span>Realizado</span><strong>{{ summary().realizedIncome | currency:'BRL' }}</strong>
          </div>
        </mat-card-content>
      </mat-card>
      <mat-card>
        <mat-card-header><mat-card-title>Despesas</mat-card-title></mat-card-header>
        <mat-card-content>
          <div style="display:flex;justify-content:space-between">
            <span>Previsto</span><strong>{{ summary().plannedExpense | currency:'BRL' }}</strong>
          </div>
          <div style="display:flex;justify-content:space-between">
            <span>Realizado</span><strong>{{ summary().realizedExpense | currency:'BRL' }}</strong>
          </div>
        </mat-card-content>
      </mat-card>
      <mat-card>
        <mat-card-header><mat-card-title>Saldo</mat-card-title></mat-card-header>
        <mat-card-content>
          <div style="display:flex;justify-content:space-between">
            <span>Projetado</span><strong>{{ summary().projectedBalance | currency:'BRL' }}</strong>
          </div>
          <div style="display:flex;justify-content:space-between">
            <span>Realizado</span><strong>{{ summary().currentBalance | currency:'BRL' }}</strong>
          </div>
        </mat-card-content>
      </mat-card>
    </div>

    <!-- Itens -->
    <table mat-table [dataSource]="items()" style="width:100%">
      <ng-container matColumnDef="description">
        <th mat-header-cell *matHeaderCellDef>Descrição</th>
        <td mat-cell *matCellDef="let item">{{ item.description }}</td>
      </ng-container>
      <ng-container matColumnDef="type">
        <th mat-header-cell *matHeaderCellDef>Tipo</th>
        <td mat-cell *matCellDef="let item">{{ item.type === 'INCOME' ? 'Receita' : 'Despesa' }}</td>
      </ng-container>
      <ng-container matColumnDef="expectedDate">
        <th mat-header-cell *matHeaderCellDef>Data</th>
        <td mat-cell *matCellDef="let item">{{ item.expectedDate | date:'dd/MM' }}</td>
      </ng-container>
      <ng-container matColumnDef="amount">
        <th mat-header-cell *matHeaderCellDef>Valor</th>
        <td mat-cell *matCellDef="let item">{{ item.amount | currency:'BRL' }}</td>
      </ng-container>
      <ng-container matColumnDef="status">
        <th mat-header-cell *matHeaderCellDef>Status</th>
        <td mat-cell *matCellDef="let item"><mat-chip>{{ item.status }}</mat-chip></td>
      </ng-container>
      <tr mat-header-row *matHeaderRowDef="['description','type','expectedDate','amount','status']"></tr>
      <tr mat-row *matRowDef="let row; columns: ['description','type','expectedDate','amount','status']"></tr>
    </table>
  }
</div>
```

- [ ] **Passo 5: Compilar e rodar testes**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20 && npm test -- --run 2>&1 | tail -10
```

Esperado: sem erros TypeScript e todos os testes passando.

- [ ] **Passo 6: Subir o app e testar o golden path manualmente**

```bash
cd frontend && npm start
```

Fluxo a testar em `http://localhost:4200`:
1. Login com `carlos@costa.com` / `costa123`
2. Navegar para Planejamento → deve aparecer empty state (ciclo ainda não aberto)
3. Clicar "Abrir ciclo" → inserir `2026-06` → confirmar
4. Verificar que ciclo aparece com parcelas do Nubank populadas automaticamente
5. Clicar "Adicionar item" → adicionar despesa manual
6. Navegar para Templates recorrentes → criar template "Salário", INCOME, dia 5, R$ 8.000
7. Fechar ciclo

- [ ] **Passo 7: Commit final do frontend**

```bash
git add frontend/src/app/features/planning/budget-cycle-list/ \
        frontend/src/app/features/planning/budget-cycle-detail/
git commit -m "feat(planning): adiciona BudgetCycleList e BudgetCycleDetail"
```
