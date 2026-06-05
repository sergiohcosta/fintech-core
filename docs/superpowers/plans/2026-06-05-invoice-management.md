# Invoice Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar a UI de gerenciamento de faturas de cartão de crédito no frontend Angular, incluindo listagem por conta, detalhe com resumo financeiro e breakdown por categoria.

**Architecture:** Duas novas rotas (`/invoices` e `/invoices/:id`) com componentes standalone. O backend já está completo — toda a lógica nova é frontend. O breakdown por categoria é calculado com `computed()` a partir das transações retornadas por `GET /api/transactions?invoiceId=`. Três pontos de entrada: sidenav, botão na lista de contas, chip de fatura nas transações.

**Tech Stack:** Angular 21, Zoneless Change Detection, Angular Material 3, Signals, Vitest.

---

## Mapa de Arquivos

| Arquivo | Operação | Responsabilidade |
|---|---|---|
| `frontend/src/app/app.routes.ts` | Modificar | Adicionar rotas `/invoices` e `/invoices/:id` |
| `frontend/src/app/components/shell/shell.ts` | Modificar | Adicionar item "Faturas" ao navItems |
| `frontend/src/app/features/account/account-list/account-list.html` | Modificar | Botão "Ver Faturas" para contas CREDIT_CARD |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.html` | Modificar | RouterLink no chip de fatura |
| `frontend/src/app/features/invoice/invoice-list/invoice-list.ts` | Criar | Componente de listagem de faturas |
| `frontend/src/app/features/invoice/invoice-list/invoice-list.html` | Criar | Template da listagem |
| `frontend/src/app/features/invoice/invoice-list/invoice-list.scss` | Criar | Estilos da listagem |
| `frontend/src/app/features/invoice/invoice-list/invoice-list.spec.ts` | Criar | Testes da listagem |
| `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.ts` | Criar | Função pura `computeBreakdown` |
| `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.spec.ts` | Criar | Testes unitários do breakdown |
| `frontend/src/app/features/invoice/invoice-detail/invoice-detail.ts` | Criar | Componente de detalhe da fatura |
| `frontend/src/app/features/invoice/invoice-detail/invoice-detail.html` | Criar | Template do detalhe |
| `frontend/src/app/features/invoice/invoice-detail/invoice-detail.scss` | Criar | Estilos do detalhe |
| `frontend/src/app/features/invoice/invoice-detail/invoice-detail.spec.ts` | Criar | Testes do detalhe |

---

## Task 1: Pontos de Entrada de Navegação

**Files:**
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/shell/shell.ts`
- Modify: `frontend/src/app/features/account/account-list/account-list.html`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.html`

- [ ] **Step 1: Adicionar rotas em `app.routes.ts`**

Adicionar dentro do array `children` do shell (após a rota `team`):

```typescript
{
  path: 'invoices',
  loadComponent: () => import('./features/invoice/invoice-list/invoice-list').then(m => m.InvoiceList)
},
{
  path: 'invoices/:id',
  loadComponent: () => import('./features/invoice/invoice-detail/invoice-detail').then(m => m.InvoiceDetail)
},
```

- [ ] **Step 2: Adicionar item "Faturas" no sidenav (`shell.ts`)**

Adicionar após o item "Transações" no array `navItems`:

```typescript
{ label: 'Faturas', icon: 'receipt_long', route: '/invoices' },
```

O array completo ficará:
```typescript
readonly navItems: NavItem[] = [
  { label: 'Dashboard',  icon: 'dashboard',    route: '/dashboard' },
  { label: 'Transações', icon: 'swap_horiz',   route: '/transactions' },
  { label: 'Faturas',    icon: 'receipt_long', route: '/invoices' },
  { label: 'Contas',     icon: 'credit_card',  route: '/accounts' },
  { label: 'Categorias', icon: 'category',     route: '/categories' },
  { label: 'Equipe',     icon: 'group',        route: '/team', adminOnly: true },
];
```

- [ ] **Step 3: Adicionar botão "Ver Faturas" em `account-list.html`**

Dentro do `<ng-container matColumnDef="actions">`, adicionar antes do botão de editar:

```html
@if (row.type === 'CREDIT_CARD') {
  <a mat-icon-button color="accent"
     [routerLink]="['/invoices']"
     [queryParams]="{ accountId: row.id }"
     matTooltip="Ver Faturas">
    <mat-icon>receipt_long</mat-icon>
  </a>
}
```

O bloco `<div class="actions-group">` completo ficará:
```html
<div class="actions-group">
  @if (row.type === 'CREDIT_CARD') {
    <a mat-icon-button color="accent"
       [routerLink]="['/invoices']"
       [queryParams]="{ accountId: row.id }"
       matTooltip="Ver Faturas">
      <mat-icon>receipt_long</mat-icon>
    </a>
  }
  <a mat-icon-button color="primary" [routerLink]="['/accounts', row.id]" matTooltip="Editar">
    <mat-icon>edit</mat-icon>
  </a>
  <button mat-icon-button color="warn" (click)="archive(row.id)" matTooltip="Arquivar">
    <mat-icon>archive</mat-icon>
  </button>
</div>
```

- [ ] **Step 4: Adicionar `routerLink` no chip de fatura em `transaction-list.html`**

Localizar o trecho (linha ~91):
```html
<span [class]="invoiceChipClass($any(row).data?.invoiceStatus)">{{ label }}</span>
```

Substituir por:
```html
<a [routerLink]="['/invoices', $any(row).data?.invoiceId]"
   [class]="invoiceChipClass($any(row).data?.invoiceStatus)"
   style="text-decoration: none;">{{ label }}</a>
```

- [ ] **Step 5: Verificar compilação TypeScript**

```bash
cd /home/sergio/fintech-core/frontend && npx tsc --noEmit
```

Esperado: sem erros. Se `InvoiceList` ou `InvoiceDetail` não existirem ainda, os erros de import nas rotas são esperados — eles serão criados nas próximas tasks.

- [ ] **Step 6: Commit**

```bash
cd /home/sergio/fintech-core && git add frontend/src/app/app.routes.ts frontend/src/app/components/shell/shell.ts frontend/src/app/features/account/account-list/account-list.html frontend/src/app/features/transaction/transaction-list/transaction-list.html
git commit -m "feat: adiciona pontos de entrada para gerenciamento de faturas"
```

---

## Task 2: InvoiceListComponent

**Files:**
- Create: `frontend/src/app/features/invoice/invoice-list/invoice-list.ts`
- Create: `frontend/src/app/features/invoice/invoice-list/invoice-list.html`
- Create: `frontend/src/app/features/invoice/invoice-list/invoice-list.scss`
- Create: `frontend/src/app/features/invoice/invoice-list/invoice-list.spec.ts`

- [ ] **Step 1: Escrever o teste antes do componente**

Criar `frontend/src/app/features/invoice/invoice-list/invoice-list.spec.ts`:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, LOCALE_ID } from '@angular/core';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';

import { InvoiceList } from './invoice-list';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { AccountResponse, InvoiceResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

registerLocaleData(localePt, 'pt-BR');

const makeRoute = (accountId: string | null) => ({
  snapshot: { queryParamMap: { get: (key: string) => key === 'accountId' ? accountId : null } }
});

const mockCcAccount: AccountResponse = {
  id: 'cc-1', name: 'Nubank', type: 'CREDIT_CARD',
  countInLiquidBalance: false, countInNetWorth: true, active: true, balance: 0
};
const mockCheckingAccount: AccountResponse = {
  id: 'ch-1', name: 'Bradesco', type: 'CHECKING',
  countInLiquidBalance: true, countInNetWorth: true, active: true, balance: 1000
};
const mockInvoice: InvoiceResponseDTO = {
  id: 'inv-1', accountId: 'cc-1', accountName: 'Nubank',
  referenceMonth: 6, referenceYear: 2026, label: 'Junho/2026',
  closingDate: '2026-06-20', dueDate: '2026-07-05',
  status: 'OPEN', totalAmount: 500, transactionCount: 3
};

describe('InvoiceList', () => {
  let accountsService: AccountsService;
  let invoicesService: InvoicesService;

  function setup(accountId: string | null = null) {
    TestBed.configureTestingModule({
      imports: [InvoiceList, NoopAnimationsModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'pt-BR' },
        { provide: ActivatedRoute, useValue: makeRoute(accountId) }
      ]
    });
    accountsService = TestBed.inject(AccountsService);
    invoicesService = TestBed.inject(InvoicesService);
  }

  it('filtra apenas contas CREDIT_CARD', async () => {
    setup();
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(
      of([mockCcAccount, mockCheckingAccount]) as any
    );
    vi.spyOn(invoicesService, 'listInvoices').mockReturnValue(of([]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.creditCardAccounts()).toHaveLength(1);
    expect(fixture.componentInstance.creditCardAccounts()[0].name).toBe('Nubank');
  });

  it('exibe empty state quando nenhuma conta selecionada', async () => {
    setup();
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockCcAccount]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Selecione um cartão');
  });

  it('pré-seleciona conta quando ?accountId está na URL', async () => {
    setup('cc-1');
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockCcAccount]) as any);
    vi.spyOn(invoicesService, 'listInvoices').mockReturnValue(of([mockInvoice]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.selectedId()).toBe('cc-1');
    expect(fixture.componentInstance.invoices()).toHaveLength(1);
  });

  it('carrega faturas quando conta é selecionada', async () => {
    setup();
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockCcAccount]) as any);
    vi.spyOn(invoicesService, 'listInvoices').mockReturnValue(of([mockInvoice]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    await fixture.whenStable();

    fixture.componentInstance.selectedId.set('cc-1');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.invoices()).toHaveLength(1);
    expect(fixture.componentInstance.invoices()[0].label).toBe('Junho/2026');
  });

  it('statusChipClass retorna classe correta para cada status', async () => {
    setup();
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    const comp = fixture.componentInstance;

    expect(comp.statusChipClass('OPEN')).toContain('status-open');
    expect(comp.statusChipClass('CLOSED')).toContain('status-closed');
    expect(comp.statusChipClass('PAID')).toContain('status-paid');
  });
});
```

- [ ] **Step 2: Executar o teste e confirmar que falha (componente não existe)**

```bash
cd /home/sergio/fintech-core/frontend && npm test 2>&1 | grep -E "FAIL|PASS|invoice-list" | head -20
```

Esperado: FAIL — `Cannot find module './invoice-list'`.

- [ ] **Step 3: Criar `invoice-list.ts`**

Criar `frontend/src/app/features/invoice/invoice-list/invoice-list.ts`:

```typescript
import { Component, inject, OnInit, signal, effect } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { AccountResponse, InvoiceResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

@Component({
  selector: 'app-invoice-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
    MatTableModule, MatButtonModule, MatIconModule,
    MatSelectModule, MatFormFieldModule, MatSnackBarModule
  ],
  templateUrl: './invoice-list.html',
  styleUrl: './invoice-list.scss'
})
export class InvoiceList implements OnInit {
  private accountsService = inject(AccountsService);
  private invoicesService = inject(InvoicesService);
  private route = inject(ActivatedRoute);
  private snackBar = inject(MatSnackBar);

  creditCardAccounts = signal<AccountResponse[]>([]);
  selectedId = signal<string | null>(null);
  invoices = signal<InvoiceResponseDTO[]>([]);
  loading = signal(false);

  displayedColumns = ['label', 'closingDate', 'dueDate', 'transactionCount', 'totalAmount', 'status', 'actions'];

  constructor() {
    // Recarrega faturas sempre que a conta selecionada muda.
    // onCleanup cancela a subscription anterior se o usuário trocar de conta rapidamente.
    effect((onCleanup) => {
      const id = this.selectedId();
      if (!id) {
        this.invoices.set([]);
        return;
      }
      this.loading.set(true);
      const sub = this.invoicesService.listInvoices({ accountId: id }).subscribe({
        next: (data) => { this.invoices.set(data); this.loading.set(false); },
        error: () => {
          this.snackBar.open('Erro ao carregar faturas.', 'Fechar', { duration: 5000 });
          this.loading.set(false);
        }
      });
      onCleanup(() => sub.unsubscribe());
    });
  }

  ngOnInit(): void {
    this.accountsService.listAccounts().subscribe({
      next: (data) => {
        const cc = data.filter(a => a.type === 'CREDIT_CARD');
        this.creditCardAccounts.set(cc);
        const preselect = this.route.snapshot.queryParamMap.get('accountId');
        if (preselect && cc.some(a => a.id === preselect)) {
          this.selectedId.set(preselect);
        }
      },
      error: () => this.snackBar.open('Erro ao carregar contas.', 'Fechar', { duration: 5000 })
    });
  }

  statusChipClass(status: string): string {
    const map: Record<string, string> = {
      OPEN:   'status-chip status-open',
      CLOSED: 'status-chip status-closed',
      PAID:   'status-chip status-paid'
    };
    return map[status] ?? 'status-chip';
  }

  statusLabel(status: string): string {
    const map: Record<string, string> = { OPEN: 'Aberta', CLOSED: 'Fechada', PAID: 'Paga' };
    return map[status] ?? status;
  }
}
```

- [ ] **Step 4: Criar `invoice-list.html`**

Criar `frontend/src/app/features/invoice/invoice-list/invoice-list.html`:

```html
<div class="page-container">
  <header class="page-header">
    <div>
      <h1>Faturas</h1>
      <p class="subtitle">Gerencie as faturas dos seus cartões de crédito</p>
    </div>
  </header>

  <mat-form-field appearance="outline" class="account-selector">
    <mat-label>Cartão de Crédito</mat-label>
    <mat-select
      [value]="selectedId()"
      (valueChange)="selectedId.set($event)">
      @for (acc of creditCardAccounts(); track acc.id) {
        <mat-option [value]="acc.id">{{ acc.name }}</mat-option>
      }
    </mat-select>
  </mat-form-field>

  @if (!selectedId()) {
    <div class="empty-state">
      <mat-icon>credit_card</mat-icon>
      <p>Selecione um cartão para ver as faturas.</p>
    </div>
  } @else {
    <div class="table-container mat-elevation-z2">
      <table mat-table [dataSource]="invoices()">

        <ng-container matColumnDef="label">
          <th mat-header-cell *matHeaderCellDef>Mês/Ano</th>
          <td mat-cell *matCellDef="let row">{{ row.label }}</td>
        </ng-container>

        <ng-container matColumnDef="closingDate">
          <th mat-header-cell *matHeaderCellDef>Fechamento</th>
          <td mat-cell *matCellDef="let row">{{ row.closingDate | date:'dd/MM/yyyy' }}</td>
        </ng-container>

        <ng-container matColumnDef="dueDate">
          <th mat-header-cell *matHeaderCellDef>Vencimento</th>
          <td mat-cell *matCellDef="let row">{{ row.dueDate | date:'dd/MM/yyyy' }}</td>
        </ng-container>

        <ng-container matColumnDef="transactionCount">
          <th mat-header-cell *matHeaderCellDef>Transações</th>
          <td mat-cell *matCellDef="let row">{{ row.transactionCount }}</td>
        </ng-container>

        <ng-container matColumnDef="totalAmount">
          <th mat-header-cell *matHeaderCellDef>Total</th>
          <td mat-cell *matCellDef="let row">
            {{ row.totalAmount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
          </td>
        </ng-container>

        <ng-container matColumnDef="status">
          <th mat-header-cell *matHeaderCellDef>Status</th>
          <td mat-cell *matCellDef="let row">
            <span [class]="statusChipClass(row.status)">{{ statusLabel(row.status) }}</span>
          </td>
        </ng-container>

        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let row">
            <a mat-button color="primary" [routerLink]="['/invoices', row.id]">
              Ver detalhes
            </a>
          </td>
        </ng-container>

        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
        <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>

        <tr class="mat-row" *matNoDataRow>
          <td class="mat-cell empty-state" [attr.colspan]="displayedColumns.length">
            <mat-icon>receipt_long</mat-icon>
            <p>Nenhuma fatura encontrada.</p>
          </td>
        </tr>
      </table>
    </div>
  }
</div>
```

- [ ] **Step 5: Criar `invoice-list.scss`**

Criar `frontend/src/app/features/invoice/invoice-list/invoice-list.scss`:

```scss
.page-container {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;
  h1 { margin: 0; }
  .subtitle { margin: 4px 0 0; color: #666; font-size: 14px; }
}

.account-selector {
  width: 320px;
  margin-bottom: 16px;
  display: block;
}

.table-container {
  border-radius: 8px;
  overflow: hidden;
}

table { width: 100%; }

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 48px;
  color: #999;
  mat-icon { font-size: 48px; height: 48px; width: 48px; margin-bottom: 16px; }
  p { margin: 0; font-size: 16px; }
}

.status-chip {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}
.status-open   { background: #e3f2fd; color: #1565c0; }
.status-closed { background: #fff3e0; color: #e65100; }
.status-paid   { background: #e8f5e9; color: #2e7d32; }
```

- [ ] **Step 6: Executar os testes e confirmar que passam**

```bash
cd /home/sergio/fintech-core/frontend && npm test 2>&1 | grep -E "FAIL|PASS|invoice-list" | head -20
```

Esperado: todos os testes de `invoice-list.spec.ts` passando (5 testes).

- [ ] **Step 7: Commit**

```bash
cd /home/sergio/fintech-core && git add frontend/src/app/features/invoice/
git commit -m "feat: implementa InvoiceListComponent com seletor de conta e listagem de faturas"
```

---

## Task 3: Utilitário computeBreakdown

**Files:**
- Create: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.ts`
- Create: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.spec.ts`

- [ ] **Step 1: Escrever os testes unitários do utilitário**

Criar `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.spec.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { computeBreakdown } from './invoice-detail.utils';
import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

const makeTransaction = (overrides: Partial<TransactionResponseDTO>): TransactionResponseDTO => ({
  id: 't1', description: 'Test', amount: 100, date: '2026-06-01',
  type: 'EXPENSE', status: 'PAID',
  installmentLabel: null, categoryName: 'Alimentação', categoryId: 'cat-1',
  categoryArchived: false, accountName: 'Nubank', accountId: 'cc-1',
  transferId: null, installmentGroupId: null, installmentGroupDescription: null,
  installmentNumber: null, totalInstallments: null,
  invoiceId: 'inv-1', invoiceDueDate: '2026-07-05', invoiceStatus: 'OPEN',
  ...overrides
});

describe('computeBreakdown', () => {
  it('agrupa transações por categoria e soma valores', () => {
    const txs = [
      makeTransaction({ id: 't1', categoryName: 'Alimentação', amount: 300 }),
      makeTransaction({ id: 't2', categoryName: 'Alimentação', amount: 200 }),
      makeTransaction({ id: 't3', categoryName: 'Transporte',  amount: 100 })
    ];

    const result = computeBreakdown(txs, 600);

    expect(result).toHaveLength(2);
    const alimentacao = result.find(r => r.categoryName === 'Alimentação')!;
    expect(alimentacao.count).toBe(2);
    expect(alimentacao.total).toBe(500);
  });

  it('trata null/undefined em categoryName como "Sem categoria"', () => {
    const txs = [
      makeTransaction({ id: 't1', categoryName: null, amount: 150 }),
      makeTransaction({ id: 't2', categoryName: undefined, amount: 50 })
    ];

    const result = computeBreakdown(txs, 200);

    expect(result).toHaveLength(1);
    expect(result[0].categoryName).toBe('Sem categoria');
    expect(result[0].total).toBe(200);
  });

  it('exclui transações CANCELLED do agrupamento', () => {
    const txs = [
      makeTransaction({ id: 't1', amount: 400, status: 'PAID' }),
      makeTransaction({ id: 't2', amount: 300, status: 'CANCELLED' })
    ];

    const result = computeBreakdown(txs, 400);

    expect(result[0].total).toBe(400);
  });

  it('ordena por valor absoluto decrescente', () => {
    const txs = [
      makeTransaction({ id: 't1', categoryName: 'Pequena', amount: 50 }),
      makeTransaction({ id: 't2', categoryName: 'Grande', amount: 500 }),
      makeTransaction({ id: 't3', categoryName: 'Média', amount: 200 })
    ];

    const result = computeBreakdown(txs, 750);

    expect(result[0].categoryName).toBe('Grande');
    expect(result[1].categoryName).toBe('Média');
    expect(result[2].categoryName).toBe('Pequena');
  });

  it('calcula porcentagem em relação ao totalExpense', () => {
    const txs = [
      makeTransaction({ id: 't1', categoryName: 'Alimentação', amount: 250 })
    ];

    const result = computeBreakdown(txs, 500);

    expect(result[0].percentage).toBeCloseTo(50, 1);
  });

  it('retorna percentage 0 quando totalExpense é 0', () => {
    const txs = [makeTransaction({ id: 't1', amount: 100, type: 'INCOME' })];

    const result = computeBreakdown(txs, 0);

    expect(result[0].percentage).toBe(0);
  });

  it('retorna array vazio quando não há transações ativas', () => {
    const txs = [makeTransaction({ id: 't1', status: 'CANCELLED' })];

    const result = computeBreakdown(txs, 0);

    expect(result).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Executar testes e confirmar que falham**

```bash
cd /home/sergio/fintech-core/frontend && npm test 2>&1 | grep -E "FAIL|PASS|utils" | head -20
```

Esperado: FAIL — `Cannot find module './invoice-detail.utils'`.

- [ ] **Step 3: Criar `invoice-detail.utils.ts`**

Criar `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.ts`:

```typescript
import { TransactionResponseDTO, TransactionStatus } from '../../../core/api/fintechSaaSAPI.schemas';

export interface CategoryBreakdownRow {
  categoryName: string;
  count: number;
  total: number;
  percentage: number;
}

export function computeBreakdown(
  transactions: TransactionResponseDTO[],
  totalExpense: number
): CategoryBreakdownRow[] {
  const active = transactions.filter(t => t.status !== TransactionStatus.CANCELLED);
  const map = new Map<string, { count: number; total: number }>();

  for (const t of active) {
    const key = t.categoryName ?? 'Sem categoria';
    const curr = map.get(key) ?? { count: 0, total: 0 };
    map.set(key, { count: curr.count + 1, total: curr.total + t.amount });
  }

  return Array.from(map.entries())
    .map(([categoryName, { count, total }]) => ({
      categoryName,
      count,
      total,
      percentage: totalExpense > 0 ? (Math.abs(total) / totalExpense) * 100 : 0
    }))
    .sort((a, b) => Math.abs(b.total) - Math.abs(a.total));
}
```

- [ ] **Step 4: Executar testes e confirmar que passam**

```bash
cd /home/sergio/fintech-core/frontend && npm test 2>&1 | grep -E "FAIL|PASS|utils" | head -20
```

Esperado: todos os 7 testes de `invoice-detail.utils.spec.ts` passando.

- [ ] **Step 5: Commit**

```bash
cd /home/sergio/fintech-core && git add frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.ts frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.spec.ts
git commit -m "feat: implementa utilitário computeBreakdown para breakdown de faturas por categoria"
```

---

## Task 4: InvoiceDetailComponent

**Files:**
- Create: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.ts`
- Create: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.html`
- Create: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.scss`
- Create: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.spec.ts`

- [ ] **Step 1: Escrever os testes antes do componente**

Criar `frontend/src/app/features/invoice/invoice-detail/invoice-detail.spec.ts`:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, LOCALE_ID } from '@angular/core';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialogModule } from '@angular/material/dialog';
import { of } from 'rxjs';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';

import { InvoiceDetail } from './invoice-detail';
import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { InvoiceResponseDTO, TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

registerLocaleData(localePt, 'pt-BR');

const makeRoute = (id: string) => ({
  snapshot: { paramMap: { get: (_: string) => id } }
});

const baseInvoice: InvoiceResponseDTO = {
  id: 'inv-1', accountId: 'cc-1', accountName: 'Nubank',
  referenceMonth: 6, referenceYear: 2026, label: 'Junho/2026',
  closingDate: '2026-06-20', dueDate: '2026-07-05',
  status: 'OPEN', totalAmount: 800, transactionCount: 3
};

const makeTransaction = (overrides: Partial<TransactionResponseDTO>): TransactionResponseDTO => ({
  id: 't1', description: 'Compra', amount: 100, date: '2026-06-01',
  type: 'EXPENSE', status: 'PAID',
  installmentLabel: null, categoryName: 'Alimentação', categoryId: 'cat-1',
  categoryArchived: false, accountName: 'Nubank', accountId: 'cc-1',
  transferId: null, installmentGroupId: null, installmentGroupDescription: null,
  installmentNumber: null, totalInstallments: null,
  invoiceId: 'inv-1', invoiceDueDate: '2026-07-05', invoiceStatus: 'OPEN',
  ...overrides
});

describe('InvoiceDetail', () => {
  let invoicesService: InvoicesService;
  let transactionsService: TransactionsService;

  function setup(invoiceId = 'inv-1') {
    TestBed.configureTestingModule({
      imports: [InvoiceDetail, NoopAnimationsModule, MatDialogModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'pt-BR' },
        { provide: ActivatedRoute, useValue: makeRoute(invoiceId) }
      ]
    });
    invoicesService = TestBed.inject(InvoicesService);
    transactionsService = TestBed.inject(TransactionsService);
  }

  it('totalIncome soma apenas transações INCOME não canceladas', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of(baseInvoice) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([
      makeTransaction({ id: 't1', type: 'INCOME',  status: 'PAID',      amount: 200 }),
      makeTransaction({ id: 't2', type: 'INCOME',  status: 'CANCELLED', amount: 100 }), // ignorado
      makeTransaction({ id: 't3', type: 'EXPENSE', status: 'PAID',      amount: 500 })
    ]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.totalIncome()).toBe(200);
  });

  it('totalExpense soma apenas transações EXPENSE não canceladas', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of(baseInvoice) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([
      makeTransaction({ id: 't1', type: 'EXPENSE', status: 'PAID',      amount: 500 }),
      makeTransaction({ id: 't2', type: 'EXPENSE', status: 'CANCELLED', amount: 200 }), // ignorado
      makeTransaction({ id: 't3', type: 'INCOME',  status: 'PAID',      amount: 50  })
    ]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.totalExpense()).toBe(500);
  });

  it('netBalance = totalIncome - totalExpense', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of(baseInvoice) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([
      makeTransaction({ id: 't1', type: 'INCOME',  amount: 80,  status: 'PAID' }),
      makeTransaction({ id: 't2', type: 'EXPENSE', amount: 500, status: 'PAID' })
    ]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.netBalance()).toBe(-420);
  });

  it('mostra botão "Fechar Fatura" apenas quando status OPEN', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of({ ...baseInvoice, status: 'OPEN' }) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Fechar Fatura');
    expect(fixture.nativeElement.textContent).not.toContain('Pagar Fatura');
  });

  it('mostra botão "Pagar Fatura" apenas quando status CLOSED', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of({ ...baseInvoice, status: 'CLOSED' }) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Pagar Fatura');
    expect(fixture.nativeElement.textContent).not.toContain('Fechar Fatura');
  });

  it('não mostra botões de ação quando status PAID', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of({ ...baseInvoice, status: 'PAID' }) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).not.toContain('Fechar Fatura');
    expect(fixture.nativeElement.textContent).not.toContain('Pagar Fatura');
  });
});
```

- [ ] **Step 2: Executar testes e confirmar que falham**

```bash
cd /home/sergio/fintech-core/frontend && npm test 2>&1 | grep -E "FAIL|PASS|invoice-detail.spec" | head -20
```

Esperado: FAIL — `Cannot find module './invoice-detail'`.

- [ ] **Step 3: Criar `invoice-detail.ts`**

Criar `frontend/src/app/features/invoice/invoice-detail/invoice-detail.ts`:

```typescript
import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import {
  InvoiceResponseDTO, TransactionResponseDTO,
  TransactionStatus, TransactionType, InvoiceStatus
} from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';
import { computeBreakdown, CategoryBreakdownRow } from './invoice-detail.utils';

@Component({
  selector: 'app-invoice-detail',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatTableModule, MatButtonModule, MatIconModule,
    MatCardModule, MatSnackBarModule, MatDialogModule
  ],
  templateUrl: './invoice-detail.html',
  styleUrl: './invoice-detail.scss'
})
export class InvoiceDetail implements OnInit {
  private route = inject(ActivatedRoute);
  private invoicesService = inject(InvoicesService);
  private transactionsService = inject(TransactionsService);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);

  invoice = signal<InvoiceResponseDTO | null>(null);
  transactions = signal<TransactionResponseDTO[]>([]);

  // CANCELLED excluídas dos cálculos financeiros; aparecem na tabela como registro histórico
  activeTransactions = computed(() =>
    this.transactions().filter(t => t.status !== TransactionStatus.CANCELLED)
  );

  totalIncome = computed(() =>
    this.activeTransactions()
      .filter(t => t.type === TransactionType.INCOME)
      .reduce((sum, t) => sum + t.amount, 0)
  );

  totalExpense = computed(() =>
    this.activeTransactions()
      .filter(t => t.type === TransactionType.EXPENSE)
      .reduce((sum, t) => sum + t.amount, 0)
  );

  netBalance = computed(() => this.totalIncome() - this.totalExpense());

  breakdown = computed<CategoryBreakdownRow[]>(() =>
    computeBreakdown(this.transactions(), this.totalExpense())
  );

  transactionColumns = ['description', 'amount', 'date', 'type', 'status', 'installmentLabel'];
  breakdownColumns = ['categoryName', 'count', 'total', 'percentage'];

  // Exposto ao template para comparação de status sem string literal
  readonly InvoiceStatus = InvoiceStatus;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.invoicesService.getInvoice(id).subscribe({
      next: (inv) => this.invoice.set(inv),
      error: () => this.snackBar.open('Fatura não encontrada.', 'Fechar', { duration: 5000 })
    });
    this.transactionsService.listTransactions({ invoiceId: id }).subscribe({
      next: (txs) => this.transactions.set(txs),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 })
    });
  }

  onClose(): void {
    const ref = this.dialog.open(ConfirmationDialogComponent, {
      data: { title: 'Fechar Fatura', message: 'Confirma o fechamento desta fatura? Não será mais possível adicionar transações.', confirmText: 'Fechar Fatura' }
    });
    ref.afterClosed().subscribe(confirmed => {
      if (!confirmed) return;
      this.invoicesService.closeInvoice(this.invoice()!.id).subscribe({
        next: (inv) => { this.invoice.set(inv); this.snackBar.open('Fatura fechada.', 'OK', { duration: 3000 }); },
        error: () => this.snackBar.open('Erro ao fechar fatura.', 'Fechar', { duration: 5000 })
      });
    });
  }

  onPay(): void {
    const ref = this.dialog.open(ConfirmationDialogComponent, {
      data: { title: 'Pagar Fatura', message: 'Confirma o pagamento desta fatura?', confirmText: 'Pagar Fatura' }
    });
    ref.afterClosed().subscribe(confirmed => {
      if (!confirmed) return;
      this.invoicesService.payInvoice(this.invoice()!.id).subscribe({
        next: (inv) => { this.invoice.set(inv); this.snackBar.open('Fatura paga.', 'OK', { duration: 3000 }); },
        error: () => this.snackBar.open('Erro ao pagar fatura.', 'Fechar', { duration: 5000 })
      });
    });
  }

  statusChipClass(status: string): string {
    const map: Record<string, string> = {
      OPEN:   'status-chip status-open',
      CLOSED: 'status-chip status-closed',
      PAID:   'status-chip status-paid'
    };
    return map[status] ?? 'status-chip';
  }

  statusLabel(status: string): string {
    const map: Record<string, string> = { OPEN: 'Aberta', CLOSED: 'Fechada', PAID: 'Paga' };
    return map[status] ?? status;
  }

  typeLabel(type: string): string {
    const map: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' };
    return map[type] ?? type;
  }

  transactionStatusLabel(status: string): string {
    const map: Record<string, string> = { PENDING: 'Pendente', PAID: 'Pago', CANCELLED: 'Cancelado' };
    return map[status] ?? status;
  }
}
```

- [ ] **Step 4: Criar `invoice-detail.html`**

Criar `frontend/src/app/features/invoice/invoice-detail/invoice-detail.html`:

```html
<div class="page-container">
  @if (invoice(); as inv) {

    <!-- Bloco 1: Cabeçalho -->
    <header class="invoice-header mat-elevation-z1">
      <div class="header-info">
        <p class="account-name">{{ inv.accountName }}</p>
        <h1>Fatura {{ inv.label }}</h1>
        <div class="header-dates">
          <span>Fechamento: {{ inv.closingDate | date:'dd/MM/yyyy' }}</span>
          <span class="separator">·</span>
          <span>Vencimento: {{ inv.dueDate | date:'dd/MM/yyyy' }}</span>
        </div>
      </div>
      <div class="header-actions">
        <span [class]="statusChipClass(inv.status)">{{ statusLabel(inv.status) }}</span>
        @if (inv.status === InvoiceStatus.OPEN) {
          <button mat-flat-button color="accent" (click)="onClose()">
            <mat-icon>lock</mat-icon>
            Fechar Fatura
          </button>
        }
        @if (inv.status === InvoiceStatus.CLOSED) {
          <button mat-flat-button color="primary" (click)="onPay()">
            <mat-icon>payments</mat-icon>
            Pagar Fatura
          </button>
        }
      </div>
    </header>

    <!-- Bloco 2: Resumo financeiro -->
    <section class="summary-section">
      <div class="summary-cards">
        <mat-card class="summary-card income-card">
          <mat-card-content>
            <p class="card-label">Receitas</p>
            <p class="card-value">{{ totalIncome() | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p>
          </mat-card-content>
        </mat-card>
        <mat-card class="summary-card expense-card">
          <mat-card-content>
            <p class="card-label">Despesas</p>
            <p class="card-value">{{ totalExpense() | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</p>
          </mat-card-content>
        </mat-card>
        <mat-card class="summary-card balance-card">
          <mat-card-content>
            <p class="card-label">Saldo Líquido</p>
            <p class="card-value" [class.negative]="netBalance() < 0">
              {{ netBalance() | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
            </p>
          </mat-card-content>
        </mat-card>
      </div>

      @if (breakdown().length > 0) {
        <div class="breakdown-container mat-elevation-z1">
          <h3 class="section-title">Breakdown por Categoria</h3>
          <table mat-table [dataSource]="breakdown()">
            <ng-container matColumnDef="categoryName">
              <th mat-header-cell *matHeaderCellDef>Categoria</th>
              <td mat-cell *matCellDef="let row">{{ row.categoryName }}</td>
            </ng-container>
            <ng-container matColumnDef="count">
              <th mat-header-cell *matHeaderCellDef>Transações</th>
              <td mat-cell *matCellDef="let row">{{ row.count }}</td>
            </ng-container>
            <ng-container matColumnDef="total">
              <th mat-header-cell *matHeaderCellDef>Total</th>
              <td mat-cell *matCellDef="let row">
                {{ row.total | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
              </td>
            </ng-container>
            <ng-container matColumnDef="percentage">
              <th mat-header-cell *matHeaderCellDef>% Despesas</th>
              <td mat-cell *matCellDef="let row">{{ row.percentage | number:'1.1-1' }}%</td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="breakdownColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: breakdownColumns;"></tr>
          </table>
        </div>
      }
    </section>

    <!-- Bloco 3: Transações da fatura (read-only, inclui CANCELLED) -->
    <section class="transactions-section">
      <h3 class="section-title">Transações da Fatura</h3>
      <div class="table-container mat-elevation-z1">
        <table mat-table [dataSource]="transactions()">
          <ng-container matColumnDef="description">
            <th mat-header-cell *matHeaderCellDef>Descrição</th>
            <td mat-cell *matCellDef="let row">{{ row.description }}</td>
          </ng-container>
          <ng-container matColumnDef="amount">
            <th mat-header-cell *matHeaderCellDef>Valor</th>
            <td mat-cell *matCellDef="let row">
              <span [class]="'amount ' + row.type.toLowerCase()">
                {{ row.amount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
              </span>
            </td>
          </ng-container>
          <ng-container matColumnDef="date">
            <th mat-header-cell *matHeaderCellDef>Data</th>
            <td mat-cell *matCellDef="let row">{{ row.date | date:'dd/MM/yyyy' }}</td>
          </ng-container>
          <ng-container matColumnDef="type">
            <th mat-header-cell *matHeaderCellDef>Tipo</th>
            <td mat-cell *matCellDef="let row">
              <span [class]="'type-badge type-' + row.type.toLowerCase()">
                {{ typeLabel(row.type) }}
              </span>
            </td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let row">
              <span [class]="'status-badge status-' + row.status.toLowerCase()">
                {{ transactionStatusLabel(row.status) }}
              </span>
            </td>
          </ng-container>
          <ng-container matColumnDef="installmentLabel">
            <th mat-header-cell *matHeaderCellDef>Parcela</th>
            <td mat-cell *matCellDef="let row">{{ row.installmentLabel ?? '—' }}</td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="transactionColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: transactionColumns;"></tr>
          <tr class="mat-row" *matNoDataRow>
            <td class="mat-cell empty-state" [attr.colspan]="transactionColumns.length">
              <p>Nenhuma transação nesta fatura.</p>
            </td>
          </tr>
        </table>
      </div>
    </section>

  } @else {
    <div class="loading-state">
      <p>Carregando fatura...</p>
    </div>
  }
</div>
```

- [ ] **Step 5: Criar `invoice-detail.scss`**

Criar `frontend/src/app/features/invoice/invoice-detail/invoice-detail.scss`:

```scss
.page-container {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

.invoice-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 24px;
  border-radius: 8px;
  margin-bottom: 24px;

  .account-name { margin: 0 0 4px; color: #666; font-size: 14px; }
  h1 { margin: 0 0 8px; }

  .header-dates {
    display: flex;
    gap: 8px;
    color: #666;
    font-size: 14px;
    .separator { color: #ccc; }
  }

  .header-actions {
    display: flex;
    align-items: center;
    gap: 12px;
  }
}

.summary-section { margin-bottom: 32px; }

.summary-cards {
  display: flex;
  gap: 16px;
  margin-bottom: 24px;
}

.summary-card {
  flex: 1;

  .card-label { margin: 0 0 8px; color: #666; font-size: 13px; }
  .card-value {
    margin: 0;
    font-size: 22px;
    font-weight: 600;
    &.negative { color: #c62828; }
  }
}

.income-card  mat-card-content .card-value { color: #2e7d32; }
.expense-card mat-card-content .card-value { color: #c62828; }

.breakdown-container, .table-container {
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 24px;
}

.section-title { margin: 0 0 16px; font-size: 16px; font-weight: 500; }
.transactions-section { margin-bottom: 24px; }

table { width: 100%; }

.status-chip {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}
.status-open   { background: #e3f2fd; color: #1565c0; }
.status-closed { background: #fff3e0; color: #e65100; }
.status-paid   { background: #e8f5e9; color: #2e7d32; }

.amount {
  font-weight: 500;
  &.income  { color: #2e7d32; }
  &.expense { color: #c62828; }
}

.type-badge, .status-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}
.type-income  { background: #e8f5e9; color: #2e7d32; }
.type-expense { background: #ffebee; color: #c62828; }

.status-pending   { background: #fff8e1; color: #f57f17; }
.status-paid      { background: #e8f5e9; color: #2e7d32; }
.status-cancelled { background: #f5f5f5; color: #9e9e9e; }

.empty-state { text-align: center; padding: 24px; color: #999; }
.loading-state { display: flex; justify-content: center; padding: 48px; color: #666; }
```

- [ ] **Step 6: Executar todos os testes e confirmar que passam**

```bash
cd /home/sergio/fintech-core/frontend && npm test 2>&1 | grep -E "FAIL|PASS|invoice" | head -30
```

Esperado: todos os testes de `invoice-detail.spec.ts` (6 testes), `invoice-detail.utils.spec.ts` (7 testes) e `invoice-list.spec.ts` (5 testes) passando. Nenhum teste existente regressivo.

- [ ] **Step 7: Verificar compilação TypeScript completa**

```bash
cd /home/sergio/fintech-core/frontend && npx tsc --noEmit
```

Esperado: sem erros.

- [ ] **Step 8: Commit final**

```bash
cd /home/sergio/fintech-core && git add frontend/src/app/features/invoice/invoice-detail/
git commit -m "feat: implementa InvoiceDetailComponent com resumo financeiro e breakdown por categoria"
```
