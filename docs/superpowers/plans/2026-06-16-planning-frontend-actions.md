# Planning Frontend — Ações de Ciclo e Saldos Disponíveis — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expor no frontend de planejamento as ações de realizar/pular/editar itens, os cards de saldo disponível/diário, e reativação de templates recorrentes — todos já implementados no backend.

**Architecture:** Cada task é autocontida e commitável individualmente. Orval gera os métodos faltantes; `PlanningService` adiciona wrappers; componentes de ciclo e templates ganham novos métodos e HTML. Sem novos arquivos — somente modificações.

**Tech Stack:** Angular 21 Zoneless · Angular Material 3 · Orval (geração de cliente) · TypeScript strict · Vitest (testes de utils puros)

---

## Mapa de arquivos

| Arquivo | Operação |
|---------|----------|
| `frontend/src/app/core/api/` (gerado) | Regenerar via Orval |
| `features/planning/planning.service.ts` | + 5 métodos |
| `features/planning/budget-cycle-current/budget-cycle-current.ts` | + realizeItem, skipItem, unrealizeItem, unskipItem, editItem, syncInstallments |
| `features/planning/budget-cycle-current/budget-cycle-current.html` | + 4º card + ações por item nos 3 painéis |
| `features/planning/budget-cycle-current/budget-cycle-current.scss` | + `.negative` |
| `features/planning/budget-item-form/budget-item-form.ts` | + modo edit |
| `features/planning/budget-item-form/budget-item-form.html` | + branch de modo edit |
| `features/planning/link-transaction-dialog/link-transaction-dialog.ts` | + modo realize |
| `features/planning/link-transaction-dialog/link-transaction-dialog.html` | + botão "Realizar sem vincular" |
| `features/planning/recurring-item-list/recurring-item-list.ts` | + toggle inativos + reactivate |
| `features/planning/recurring-item-list/recurring-item-list.html` | + toggle + inativos + reativar |

---

## Task 1: Regenerar cliente Orval

Adiciona ao cliente gerado os 5 métodos faltantes e os 3 novos campos de `BudgetCycleSummary`.

**Files:**
- Regenerar: `frontend/src/app/core/api/` (todo o diretório gerado)

- [ ] **Step 1: Executar geração**

```bash
cd frontend && npm run api:generate
```

- [ ] **Step 2: Verificar métodos adicionados em `budget.service.ts`**

```bash
grep -n "realizeBudgetItem\|unrealizeBudgetItem\|skipBudgetItem\|unskipBudgetItem\|reactivateRecurringBudgetItem" src/app/core/api/budget/budget.service.ts
```

Esperado: 5 ocorrências (uma por método novo).

- [ ] **Step 3: Verificar novos campos em `fintechSaaSAPI.schemas.ts`**

```bash
grep -n "availableToSpend\|dailyAllowance\|remainingDays\|BudgetItemRealizeRequest" src/app/core/api/fintechSaaSAPI.schemas.ts
```

Esperado: 4 ocorrências — `availableToSpend`, `dailyAllowance`, `remainingDays` em `BudgetCycleSummary` e a interface `BudgetItemRealizeRequest`.

- [ ] **Step 4: Verificar compilação TypeScript**

```bash
npx tsc --noEmit
```

Esperado: 0 erros.

- [ ] **Step 5: Commit**

```bash
git add src/app/core/api/
git commit -m "chore(planning): regenera cliente Orval com endpoints de realizar/pular/reativar"
```

---

## Task 2: PlanningService — novos wrappers

**Files:**
- Modify: `features/planning/planning.service.ts`

- [ ] **Step 1: Atualizar `addItem()` no budget-cycle-current.ts**

O callback de `addItem()` usa `BudgetItemFormResult`, que na Task 5 passa a ser `BudgetItemCreateRequest | BudgetItemUpdateRequest`. `createItem()` espera `BudgetItemCreateRequest` — narrowing explícito evita o erro de tipo.

No `budget-cycle-current.ts`, localizar o método `addItem()` e trocar:

```ts
// antes
ref.afterClosed().subscribe((result?: BudgetItemFormResult) => {
```

por:

```ts
// depois
ref.afterClosed().subscribe((result?: BudgetItemCreateRequest) => {
```

Adicionar `BudgetItemCreateRequest` nos imports de schemas se ainda não estiver. Remover `BudgetItemFormResult` dos imports do componente (não é mais necessário lá — a form o exporta mas o componente pode usar os tipos concretos).

- [ ] **Step 2: Adicionar os 5 métodos ao PlanningService**

Abrir `frontend/src/app/features/planning/planning.service.ts`. Após o método `unlinkItem`, adicionar:

```ts
  realizeItem(id: string, req: BudgetItemRealizeRequest): Observable<BudgetItemResponse> {
    return this.budget.realizeBudgetItem(id, req);
  }

  unrealizeItem(id: string): Observable<BudgetItemResponse> {
    return this.budget.unrealizeBudgetItem(id);
  }

  skipItem(id: string): Observable<BudgetItemResponse> {
    return this.budget.skipBudgetItem(id);
  }

  unskipItem(id: string): Observable<BudgetItemResponse> {
    return this.budget.unskipBudgetItem(id);
  }

  reactivateRecurring(id: string): Observable<RecurringBudgetItemResponse> {
    return this.budget.reactivateRecurringBudgetItem(id);
  }
```

Adicionar `BudgetItemRealizeRequest` na linha de imports do arquivo:

```ts
import {
  BudgetCycleOpenRequest,
  BudgetCyclePageResponse,
  BudgetCycleResponse,
  BudgetItemCreateRequest,
  BudgetItemLinkRequest,
  BudgetItemRealizeRequest,   // ← adicionar
  BudgetItemResponse,
  BudgetItemUpdateRequest,
  RecurringBudgetItemRequest,
  RecurringBudgetItemResponse,
  TenantSettingsPatchRequest,
} from '../../core/api/fintechSaaSAPI.schemas';
```

- [ ] **Step 3: Verificar compilação**

```bash
cd frontend && npx tsc --noEmit
```

Esperado: 0 erros.

- [ ] **Step 4: Commit**

```bash
git add src/app/features/planning/planning.service.ts
git commit -m "feat(planning): adiciona wrappers realize/skip/unrealize/unskip/reactivate ao PlanningService"
```

---

## Task 3: Card "Disponível" no summary grid

Adiciona o 4º card com `availableToSpend`, `dailyAllowance` e `remainingDays`. Dados vêm de `cycle().summary` (campo do backend — sem recomputar no cliente).

**Files:**
- Modify: `features/planning/budget-cycle-current/budget-cycle-current.html`
- Modify: `features/planning/budget-cycle-current/budget-cycle-current.scss`

- [ ] **Step 1: Adicionar o card no HTML**

Em `budget-cycle-current.html`, dentro de `<div class="summary-grid">`, após o card de Saldo, adicionar:

```html
    @if (cycle()!.status === 'OPEN') {
      <mat-card>
        <mat-card-header><mat-card-title>Disponível</mat-card-title></mat-card-header>
        <mat-card-content>
          <div class="summary-line"
               [class.negative]="(cycle()!.summary?.availableToSpend ?? 0) < 0">
            <span>Para gastar</span>
            <strong>{{ cycle()!.summary?.availableToSpend | currency:'BRL' }}</strong>
          </div>
          <div class="summary-line">
            <span>Por dia</span>
            <strong>{{ cycle()!.summary?.dailyAllowance | currency:'BRL' }}</strong>
          </div>
          <div class="summary-line">
            <span>Dias restantes</span>
            <strong>{{ cycle()!.summary?.remainingDays }}</strong>
          </div>
        </mat-card-content>
      </mat-card>
    }
```

- [ ] **Step 2: Adicionar estilo `.negative` no SCSS**

Em `budget-cycle-current.scss`, adicionar:

```scss
.negative {
  color: var(--mat-sys-error);
}
```

- [ ] **Step 3: Verificar compilação**

```bash
cd frontend && npx tsc --noEmit
```

- [ ] **Step 4: Commit**

```bash
git add src/app/features/planning/budget-cycle-current/budget-cycle-current.html \
        src/app/features/planning/budget-cycle-current/budget-cycle-current.scss
git commit -m "feat(planning): adiciona card Disponível com saldo restante e diário"
```

---

## Task 4: LinkTransactionDialog — modo realize

Adiciona `mode: 'link' | 'realize'` ao dialog. No modo realize, aparece botão "Realizar sem vincular" que fecha com `null`.

**Files:**
- Modify: `features/planning/link-transaction-dialog/link-transaction-dialog.ts`
- Modify: `features/planning/link-transaction-dialog/link-transaction-dialog.html`

- [ ] **Step 1: Atualizar a interface e o componente TypeScript**

Substituir o conteúdo de `link-transaction-dialog.ts` por:

```ts
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
  mode?: 'link' | 'realize';
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
  readonly isRealizeMode = () => this.data.mode === 'realize';

  displayedColumns = ['date', 'description', 'amount', 'select'];

  ngOnInit(): void {
    const itemType = this.data.item.type;
    this.txService.listTransactions({ type: itemType })
      .subscribe({
        next: (result: TransactionResponseDTO[]) => {
          this.transactions.set(result);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  select(tx: TransactionResponseDTO): void {
    this.dialogRef.close(tx.id);
  }

  realizeWithoutLink(): void {
    this.dialogRef.close(null);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
```

- [ ] **Step 2: Atualizar o template HTML**

Substituir o conteúdo de `link-transaction-dialog.html` por:

```html
<h2 mat-dialog-title>
  @if (isRealizeMode()) { Realizar item — {{ data.item.description }} }
  @else { Vincular transação — {{ data.item.description }} }
</h2>
<mat-dialog-content>
  @if (isRealizeMode()) {
    <p style="margin:0 0 8px;color:var(--mat-sys-on-surface-variant)">
      Selecione a transação correspondente ou realize sem vincular.
    </p>
  }
  @if (loading()) {
    <p>Carregando transações...</p>
  } @else if (transactions().length === 0) {
    <p>Nenhuma transação encontrada.</p>
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
  @if (isRealizeMode()) {
    <button mat-stroked-button type="button" (click)="realizeWithoutLink()">
      Realizar sem vincular
    </button>
  }
  <button mat-button type="button" (click)="cancel()">Cancelar</button>
</mat-dialog-actions>
```

- [ ] **Step 3: Verificar compilação**

```bash
cd frontend && npx tsc --noEmit
```

- [ ] **Step 4: Commit**

```bash
git add src/app/features/planning/link-transaction-dialog/
git commit -m "feat(planning): adiciona modo realize ao LinkTransactionDialog"
```

---

## Task 5: BudgetItemForm — modo edit

Adiciona `mode: 'edit'` que pré-preenche o form com os dados do item existente. `type` fica desabilitado (não editável via `PUT`).

**Files:**
- Modify: `features/planning/budget-item-form/budget-item-form.ts`
- Modify: `features/planning/budget-item-form/budget-item-form.html`

- [ ] **Step 1: Atualizar TypeScript**

Substituir o conteúdo de `budget-item-form.ts` por:

```ts
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import {
  BudgetCycleOpenRequest,
  BudgetItemCreateRequest,
  BudgetItemResponse,
  BudgetItemUpdateRequest,
} from '../../../core/api/fintechSaaSAPI.schemas';

export type BudgetItemFormResult = BudgetItemCreateRequest | BudgetItemUpdateRequest;

export interface BudgetItemFormData {
  cycleId?: string;
  mode?: 'openCycle' | 'edit';
  item?: BudgetItemResponse;
}

@Component({
  selector: 'app-budget-item-form',
  standalone: true,
  providers: [provideNativeDateAdapter()],
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
  readonly isEditMode = signal(false);

  readonly cycleForm = this.fb.group({
    referenceMonth: ['', [Validators.required, Validators.pattern(/^\d{4}-\d{2}$/)]],
    startDay: [1, [Validators.required, Validators.min(1), Validators.max(28)]],
  });

  readonly itemForm = this.fb.group({
    description: ['', Validators.required],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    type: ['EXPENSE', Validators.required],
    expectedDate: [null as Date | null, Validators.required],
  });

  ngOnInit(): void {
    this.isOpenCycleMode.set(this.data?.mode === 'openCycle');
    this.isEditMode.set(this.data?.mode === 'edit');

    if (this.isEditMode()) {
      const item = this.data.item!;
      this.itemForm.patchValue({
        description: item.description ?? '',
        amount: item.amount ?? null,
        type: item.type ?? 'EXPENSE',
        expectedDate: item.expectedDate ? new Date(item.expectedDate + 'T00:00:00') : null,
      });
      this.itemForm.get('type')!.disable();
    }
  }

  onSubmit(): void {
    if (this.isOpenCycleMode()) {
      if (this.cycleForm.invalid) return;
      const v = this.cycleForm.getRawValue();
      this.dialogRef.close({ referenceMonth: v.referenceMonth!, startDay: v.startDay! } satisfies BudgetCycleOpenRequest);
    } else if (this.isEditMode()) {
      if (this.itemForm.invalid) return;
      const v = this.itemForm.getRawValue();
      const result: BudgetItemUpdateRequest = {
        description: v.description!,
        amount: v.amount!,
        expectedDate: v.expectedDate!.toISOString().substring(0, 10),
      };
      this.dialogRef.close(result);
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

- [ ] **Step 2: Atualizar o template HTML**

Substituir o conteúdo de `budget-item-form.html` por:

```html
@if (isOpenCycleMode()) {
  <h2 mat-dialog-title>Abrir ciclo de planejamento</h2>
  <mat-dialog-content>
    <form [formGroup]="cycleForm" style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
      <mat-form-field appearance="outline" style="width:100%">
        <mat-label>Mês de referência (yyyy-MM)</mat-label>
        <input matInput formControlName="referenceMonth" placeholder="2026-06" />
        <mat-error>Formato esperado: yyyy-MM (ex: 2026-06)</mat-error>
      </mat-form-field>

      <mat-form-field appearance="outline" style="width:100%">
        <mat-label>Dia de início do ciclo (1–28)</mat-label>
        <input matInput type="number" formControlName="startDay" min="1" max="28" />
        <mat-hint>Dia 1 = mês calendário; dia 11 = ciclo do dia 11 ao dia 10</mat-hint>
        <mat-error>Informe um dia entre 1 e 28</mat-error>
      </mat-form-field>
    </form>
  </mat-dialog-content>
  <mat-dialog-actions align="end">
    <button mat-button type="button" (click)="onCancel()">Cancelar</button>
    <button mat-flat-button color="primary" type="button"
            [disabled]="cycleForm.invalid" (click)="onSubmit()">Abrir</button>
  </mat-dialog-actions>
} @else {
  <h2 mat-dialog-title>{{ isEditMode() ? 'Editar item' : 'Adicionar item' }}</h2>
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
        <input matInput [matDatepicker]="picker" formControlName="expectedDate"
               (click)="picker.open()" />
        <mat-datepicker-toggle matIconSuffix [for]="picker" />
        <mat-datepicker #picker />
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

- [ ] **Step 3: Verificar compilação**

```bash
cd frontend && npx tsc --noEmit
```

- [ ] **Step 4: Commit**

```bash
git add src/app/features/planning/budget-item-form/
git commit -m "feat(planning): adiciona modo edit ao BudgetItemFormComponent"
```

---

## Task 6: budget-cycle-current — ações por item + sync

A maior mudança: adiciona 6 métodos ao componente e reescreve a coluna de ações em todos os painéis. Também adiciona o botão de sync no header.

**Files:**
- Modify: `features/planning/budget-cycle-current/budget-cycle-current.ts`
- Modify: `features/planning/budget-cycle-current/budget-cycle-current.html`

- [ ] **Step 1: Atualizar os imports e os novos métodos no TypeScript**

Abrir `budget-cycle-current.ts`. Atualizar os imports para incluir os novos tipos e `BudgetItemUpdateRequest`:

```ts
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { HttpErrorResponse } from '@angular/common/http';
import { finalize } from 'rxjs/operators';

import { PlanningService } from '../planning.service';
import {
  BudgetCycleOpenRequest,
  BudgetCycleResponse,
  BudgetItemResponse,
  BudgetItemUpdateRequest,
} from '../../../core/api/fintechSaaSAPI.schemas';
import { buildSummary } from './budget-cycle.utils';
import { BudgetItemFormComponent, BudgetItemFormData, BudgetItemFormResult } from '../budget-item-form/budget-item-form';
import { LinkTransactionDialogComponent, LinkTransactionDialogData } from '../link-transaction-dialog/link-transaction-dialog';
```

Adicionar os 6 métodos novos após `deleteItem()`:

```ts
  realizeItem(item: BudgetItemResponse): void {
    const cycleId = this.cycle()?.id;
    if (!cycleId) return;
    const ref = this.dialog.open(LinkTransactionDialogComponent, {
      width: '600px',
      data: { item, cycleId, mode: 'realize' } satisfies LinkTransactionDialogData,
    });
    ref.afterClosed().subscribe((result: string | null | undefined) => {
      if (result === undefined) return;
      const req = result ? { transactionId: result } : {};
      this.planningService.realizeItem(item.id!, req).subscribe({
        next: updated => {
          this.replaceItem(updated);
          this.snackBar.open('Item realizado.', 'OK', { duration: 2000 });
        },
        error: (err: HttpErrorResponse) => {
          const msg = err.error?.message ?? 'Erro ao realizar item.';
          this.snackBar.open(msg, 'OK', { duration: 3000 });
        },
      });
    });
  }

  skipItem(item: BudgetItemResponse): void {
    this.planningService.skipItem(item.id!).subscribe({
      next: updated => {
        this.replaceItem(updated);
        this.snackBar.open('Item pulado.', 'OK', { duration: 2000 });
      },
      error: (err: HttpErrorResponse) => {
        const msg = err.error?.message ?? 'Erro ao pular item.';
        this.snackBar.open(msg, 'OK', { duration: 3000 });
      },
    });
  }

  unrealizeItem(item: BudgetItemResponse): void {
    this.planningService.unrealizeItem(item.id!).subscribe({
      next: updated => {
        this.replaceItem(updated);
        this.snackBar.open('Realização desfeita.', 'OK', { duration: 2000 });
      },
      error: (err: HttpErrorResponse) => {
        const msg = err.error?.message ?? 'Erro ao desfazer realização.';
        this.snackBar.open(msg, 'OK', { duration: 3000 });
      },
    });
  }

  unskipItem(item: BudgetItemResponse): void {
    this.planningService.unskipItem(item.id!).subscribe({
      next: updated => {
        this.replaceItem(updated);
        this.snackBar.open('Item reativado.', 'OK', { duration: 2000 });
      },
      error: (err: HttpErrorResponse) => {
        const msg = err.error?.message ?? 'Erro ao desfazer pulo.';
        this.snackBar.open(msg, 'OK', { duration: 3000 });
      },
    });
  }

  editItem(item: BudgetItemResponse): void {
    const ref = this.dialog.open(BudgetItemFormComponent, {
      width: '500px',
      data: { mode: 'edit', item } satisfies BudgetItemFormData,
    });
    ref.afterClosed().subscribe((result?: BudgetItemUpdateRequest) => {
      if (!result) return;
      this.planningService.updateItem(item.id!, result).subscribe({
        next: updated => {
          this.replaceItem(updated);
          this.snackBar.open('Item atualizado.', 'OK', { duration: 2000 });
        },
        error: (err: HttpErrorResponse) => {
          const msg = err.error?.message ?? 'Erro ao atualizar item.';
          this.snackBar.open(msg, 'OK', { duration: 3000 });
        },
      });
    });
  }

  syncInstallments(): void {
    const id = this.cycle()?.id;
    if (!id) return;
    this.planningService.syncInstallments(id).subscribe({
      next: c => {
        this.cycle.set(c);
        this.items.set(c.items ?? []);
        this.snackBar.open('Parcelas sincronizadas.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro ao sincronizar parcelas.', 'OK', { duration: 3000 }),
    });
  }
```

- [ ] **Step 2: Atualizar o header do ciclo com botão sync**

Em `budget-cycle-current.html`, dentro de `<div class="cycle-actions">`, adicionar o botão de sync antes do botão de fechar:

```html
      <div class="cycle-actions">
        <mat-chip [class.chip-open]="cycle()!.status === 'OPEN'"
                  [class.chip-closed]="cycle()!.status === 'CLOSED'">
          {{ cycle()!.status === 'OPEN' ? 'Aberto' : 'Fechado' }}
        </mat-chip>
        @if (cycle()!.status === 'OPEN') {
          <button mat-icon-button matTooltip="Sincronizar parcelas do cartão"
                  (click)="syncInstallments()">
            <mat-icon>sync</mat-icon>
          </button>
          <button mat-stroked-button [disabled]="closing()" (click)="closeCycle()">
            Fechar ciclo
          </button>
        }
      </div>
```

- [ ] **Step 3: Substituir a coluna de ações no painel de Receitas**

No `mat-expansion-panel` de Receitas, substituir o `<ng-container matColumnDef="actions">` por:

```html
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let item">
              @if (item.status === 'PENDING' && cycle()!.status === 'OPEN') {
                <button mat-icon-button matTooltip="Realizar" (click)="realizeItem(item)">
                  <mat-icon>check_circle_outline</mat-icon>
                </button>
                <button mat-icon-button matTooltip="Pular" (click)="skipItem(item)">
                  <mat-icon>skip_next</mat-icon>
                </button>
                @if (item.source === 'MANUAL') {
                  <button mat-icon-button matTooltip="Editar" (click)="editItem(item)">
                    <mat-icon>edit</mat-icon>
                  </button>
                  <button mat-icon-button matTooltip="Remover" (click)="deleteItem(item)">
                    <mat-icon>delete_outline</mat-icon>
                  </button>
                }
                @if (!item.transactionId) {
                  <button mat-icon-button matTooltip="Vincular transação" (click)="linkTransaction(item)">
                    <mat-icon>link</mat-icon>
                  </button>
                } @else {
                  <button mat-icon-button matTooltip="Desvincular" (click)="unlinkTransaction(item)">
                    <mat-icon>link_off</mat-icon>
                  </button>
                }
              }
              @if (item.status === 'REALIZED' && cycle()!.status === 'OPEN') {
                <button mat-icon-button matTooltip="Desfazer realização" (click)="unrealizeItem(item)">
                  <mat-icon>undo</mat-icon>
                </button>
                @if (item.transactionId) {
                  <button mat-icon-button matTooltip="Desvincular" (click)="unlinkTransaction(item)">
                    <mat-icon>link_off</mat-icon>
                  </button>
                }
              }
              @if (item.status === 'SKIPPED' && cycle()!.status === 'OPEN') {
                <button mat-icon-button matTooltip="Desfazer" (click)="unskipItem(item)">
                  <mat-icon>undo</mat-icon>
                </button>
              }
            </td>
          </ng-container>
```

- [ ] **Step 4: Substituir a coluna de ações no painel de Despesas fixas**

No painel de Despesas, substituir o `<ng-container matColumnDef="actions">` com o mesmo bloco de ações do Step 3 (idêntico — copiar e colar).

- [ ] **Step 5: Adicionar coluna de ações no painel de Parcelas do cartão**

O painel de Parcelas atualmente **não tem** coluna `actions`. Adicionar após a coluna `status`:

```html
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let item">
              @if (item.status === 'PENDING' && cycle()!.status === 'OPEN') {
                <button mat-icon-button matTooltip="Realizar" (click)="realizeItem(item)">
                  <mat-icon>check_circle_outline</mat-icon>
                </button>
                <button mat-icon-button matTooltip="Pular" (click)="skipItem(item)">
                  <mat-icon>skip_next</mat-icon>
                </button>
                @if (!item.transactionId) {
                  <button mat-icon-button matTooltip="Vincular transação" (click)="linkTransaction(item)">
                    <mat-icon>link</mat-icon>
                  </button>
                } @else {
                  <button mat-icon-button matTooltip="Desvincular" (click)="unlinkTransaction(item)">
                    <mat-icon>link_off</mat-icon>
                  </button>
                }
              }
              @if (item.status === 'REALIZED' && cycle()!.status === 'OPEN') {
                <button mat-icon-button matTooltip="Desfazer realização" (click)="unrealizeItem(item)">
                  <mat-icon>undo</mat-icon>
                </button>
                @if (item.transactionId) {
                  <button mat-icon-button matTooltip="Desvincular" (click)="unlinkTransaction(item)">
                    <mat-icon>link_off</mat-icon>
                  </button>
                }
              }
              @if (item.status === 'SKIPPED' && cycle()!.status === 'OPEN') {
                <button mat-icon-button matTooltip="Desfazer" (click)="unskipItem(item)">
                  <mat-icon>undo</mat-icon>
                </button>
              }
            </td>
          </ng-container>
```

E atualizar as linhas `*matHeaderRowDef` e `*matRowDef` do painel de Parcelas para incluir `'actions'`:

```html
          <tr mat-header-row *matHeaderRowDef="['description','expectedDate','amount','status','actions']"></tr>
          <tr mat-row *matRowDef="let row; columns: ['description','expectedDate','amount','status','actions']"></tr>
```

- [ ] **Step 6: Verificar compilação**

```bash
cd frontend && npx tsc --noEmit
```

- [ ] **Step 7: Commit**

```bash
git add src/app/features/planning/budget-cycle-current/
git commit -m "feat(planning): adiciona ações realizar/pular/editar/desfazer por item e botão de sync"
```

---

## Task 7: Templates recorrentes — reativar inativos

Adiciona toggle para mostrar itens inativos e botão de reativar. Carrega todos os items uma vez e separa por `active` no cliente.

**Files:**
- Modify: `features/planning/recurring-item-list/recurring-item-list.ts`
- Modify: `features/planning/recurring-item-list/recurring-item-list.html`

- [ ] **Step 1: Atualizar TypeScript**

Substituir o conteúdo de `recurring-item-list.ts` por:

```ts
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';

import { filter, finalize, switchMap } from 'rxjs/operators';

import { PlanningService } from '../planning.service';
import { RecurringBudgetItemRequest, RecurringBudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { RecurringItemFormComponent } from '../recurring-item-form/recurring-item-form';

@Component({
  selector: 'app-recurring-item-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe,
    MatButtonModule, MatIconModule, MatSlideToggleModule, MatSnackBarModule,
    MatTableModule, MatTooltipModule,
  ],
  templateUrl: './recurring-item-list.html',
})
export class RecurringItemList implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly allItems = signal<RecurringBudgetItemResponse[]>([]);
  readonly loading = signal(true);
  readonly showInactive = signal(false);

  readonly activeItems = computed(() => this.allItems().filter(i => i.active !== false));
  readonly inactiveItems = computed(() => this.allItems().filter(i => i.active === false));

  displayedColumns = ['day', 'description', 'type', 'amount', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.planningService.listRecurring()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: items => this.allItems.set(items),
        error: () => this.snackBar.open('Erro ao carregar templates.', 'OK', { duration: 3000 }),
      });
  }

  openForm(existing?: RecurringBudgetItemResponse): void {
    const ref = this.dialog.open(RecurringItemFormComponent, {
      width: '460px',
      data: existing ?? null,
    });
    ref.afterClosed().pipe(
      filter(Boolean),
      switchMap((result: RecurringBudgetItemRequest) => existing
        ? this.planningService.updateRecurring(existing.id!, result)
        : this.planningService.createRecurring(result)
      )
    ).subscribe({
      next: () => {
        this.load();
        this.snackBar.open(existing ? 'Template atualizado.' : 'Template criado.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro ao salvar template.', 'OK', { duration: 3000 }),
    });
  }

  deactivate(item: RecurringBudgetItemResponse): void {
    this.planningService.deleteRecurring(item.id!).subscribe({
      next: () => {
        this.load();
        this.snackBar.open('Template desativado.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro ao desativar template.', 'OK', { duration: 3000 }),
    });
  }

  reactivate(item: RecurringBudgetItemResponse): void {
    this.planningService.reactivateRecurring(item.id!).subscribe({
      next: () => {
        this.load();
        this.snackBar.open('Template reativado.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro ao reativar template.', 'OK', { duration: 3000 }),
    });
  }
}
```

- [ ] **Step 2: Atualizar o template HTML**

Substituir o conteúdo de `recurring-item-list.html` por:

```html
<div style="padding:24px;max-width:800px;margin:0 auto">
  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
    <h1 style="margin:0">Templates recorrentes</h1>
    <div style="display:flex;align-items:center;gap:16px">
      <mat-slide-toggle [checked]="showInactive()" (change)="showInactive.set($event.checked)">
        Mostrar inativos
      </mat-slide-toggle>
      <button mat-flat-button color="primary" (click)="openForm()">
        <mat-icon>add</mat-icon> Novo template
      </button>
    </div>
  </div>

  @if (loading()) {
    <p>Carregando...</p>
  } @else if (activeItems().length === 0 && !showInactive()) {
    <p>Nenhum template recorrente configurado.</p>
  } @else {
    <table mat-table [dataSource]="activeItems()" style="width:100%">
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

    @if (showInactive() && inactiveItems().length > 0) {
      <h3 style="margin-top:24px;opacity:0.6">Inativos</h3>
      <table mat-table [dataSource]="inactiveItems()" style="width:100%;opacity:0.6">
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
            <button mat-icon-button matTooltip="Reativar" (click)="reactivate(item)">
              <mat-icon>play_circle_outline</mat-icon>
            </button>
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
        <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
      </table>
    }

    @if (showInactive() && inactiveItems().length === 0) {
      <p style="margin-top:16px;opacity:0.6">Nenhum template inativo.</p>
    }
  }
</div>
```

- [ ] **Step 3: Verificar compilação**

```bash
cd frontend && npx tsc --noEmit
```

- [ ] **Step 4: Commit**

```bash
git add src/app/features/planning/recurring-item-list/
git commit -m "feat(planning): adiciona reativação de templates recorrentes e toggle de inativos"
```

---

## Task 8: Verificação final e testes

- [ ] **Step 1: Rodar os testes Vitest**

```bash
cd frontend && npm test
```

Esperado: todos os testes passando. Nenhum novo teste necessário (nenhuma lógica pura foi adicionada — os novos campos `availableToSpend`/`dailyAllowance`/`remainingDays` vêm do backend sem cálculo no cliente).

- [ ] **Step 2: Iniciar dev server e verificar visualmente**

```bash
cd frontend && npm start
```

Verificar no browser (`http://localhost:4200`):

1. **Card "Disponível"** aparece no ciclo OPEN com os 3 valores. Se `availableToSpend < 0`, o texto aparece em vermelho.
2. **Receitas e Despesas PENDING** têm botões Realizar (`check_circle_outline`) e Pular (`skip_next`). Itens MANUAL também têm Editar e Remover.
3. **Realizar** abre o dialog com título "Realizar item" e botão "Realizar sem vincular".
4. **Pular** muda o status para SKIPPED imediatamente. O botão "Desfazer" aparece na linha.
5. **REALIZED** mostra botão Desfazer (unrealize).
6. **Editar** (itens MANUAL) abre o form pré-preenchido; campo Tipo está desabilitado.
7. **Parcelas do cartão** agora têm ações (Realizar, Pular).
8. **Botão sync** (ícone `sync`) aparece no header do ciclo OPEN.
9. **Templates recorrentes**: toggle "Mostrar inativos" funciona; botão "Reativar" restaura o template.

- [ ] **Step 3: Commit final (se houver ajustes)**

```bash
git add -p  # só o que foi ajustado
git commit -m "fix(planning): ajustes pós-verificação visual"
```

---

## Notas de implementação

- **`items` signal permanece a única fonte de verdade** dos painéis. `replaceItem()` já existente substitui o item atualizado sem recarregar o ciclo todo.
- **`cycle().summary`** é o backend-computed summary. Os 3 novos campos vêm dele diretamente — não há cálculo no cliente.
- **`listRecurring()` sem parâmetros** retorna todos os items. A separação ativo/inativo é feita com `computed()` no cliente — evita duas chamadas paralelas.
- **Tipo de retorno do dialog realize** é `string | null | undefined`. A verificação `result === undefined` é intencional: `null` é um valor válido ("realizar sem vincular") e `undefined` significa cancelado.
