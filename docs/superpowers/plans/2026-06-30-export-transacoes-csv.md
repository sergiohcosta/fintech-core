# Export de Transações em CSV — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar botão "Exportar CSV" na lista de transações que baixa as transações visíveis (filtro ativo, somente reais) em arquivo `.csv` compatível com Excel pt-BR.

**Architecture:** Função pura `exportToCsv` adicionada em `transaction-list.utils.ts` (sem imports Angular, testável no Vitest sem TestBed). O componente `TransactionList` chama a função com `filteredTransactions().filter(!isGhost)`, gera um `Blob` e dispara o download via `<a>` sintético.

**Tech Stack:** Angular 21 (Zoneless, Signals), Vitest 4.x, TypeScript 5.9 strict. Zero dependências externas.

## Global Constraints

- Separador CSV: `;` (padrão pt-BR — Excel abre sem configuração)
- Encoding: UTF-8 com BOM (`﻿`) — garante acentos no Excel/Windows
- Escape RFC 4180: campos com `;`, `"` ou quebra de linha envolvidos em aspas duplas; `"` interno → `""`
- Somente transações reais: `!isGhost(t)` — fantasmas de recorrência não entram no CSV
- Colunas (nesta ordem): Data, Descrição, Valor, Tipo, Status, Categoria, Conta, Parcela, Fatura
- Sem novos endpoints de backend, sem novas libs npm, sem novos arquivos

---

### Task 1: Função pura `exportToCsv` + teste

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.spec.ts`

**Interfaces:**
- Produz: `export function exportToCsv(transactions: TransactionResponseDTO[]): string`
  - Parâmetro: array de `TransactionResponseDTO` (importado de `../../../core/api/fintechSaaSAPI.schemas`)
  - Retorno: string CSV completa (cabeçalho + linhas, separador `\n`, sem trailing newline)

---

- [ ] **Step 1: Escrever o teste com falha**

Em `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.spec.ts`, adicionar após os `describe` existentes:

```ts
describe('exportToCsv', () => {
  it('gera cabeçalho e linha com campos corretos', () => {
    const csv = exportToCsv([{
      id: '1', description: 'Netflix', amount: 45.9, date: '2026-06-15',
      type: 'EXPENSE', status: 'PAID', categoryPath: 'Lazer → Streaming',
      accountName: 'Nubank', installmentNumber: null, totalInstallments: null,
      invoiceDueDate: null, projected: false,
    } as TransactionResponseDTO]);

    const [header, row] = csv.split('\n');
    expect(header).toBe('Data;Descrição;Valor;Tipo;Status;Categoria;Conta;Parcela;Fatura');
    expect(row).toBe('15/06/2026;Netflix;45,90;Despesa;Pago;Lazer → Streaming;Nubank;;');
  });

  it('usa categoryName quando categoryPath é null', () => {
    const csv = exportToCsv([{
      id: '2', description: 'Mercado', amount: 200, date: '2026-06-01',
      type: 'EXPENSE', status: 'PENDING', categoryPath: null, categoryName: 'Alimentação',
      accountName: 'Nubank', installmentNumber: null, totalInstallments: null,
      invoiceDueDate: null, projected: false,
    } as TransactionResponseDTO]);

    const row = csv.split('\n')[1];
    expect(row).toContain('Alimentação');
  });

  it('formata parcela como N/Total quando disponível', () => {
    const csv = exportToCsv([{
      id: '3', description: 'TV', amount: 300, date: '2026-06-01',
      type: 'EXPENSE', status: 'PENDING', categoryPath: null, categoryName: null,
      accountName: null, installmentNumber: 2, totalInstallments: 6,
      invoiceDueDate: null, projected: false,
    } as TransactionResponseDTO]);

    const row = csv.split('\n')[1];
    expect(row).toContain('2/6');
  });

  it('formata fatura como mmm/yyyy quando invoiceDueDate está presente', () => {
    const csv = exportToCsv([{
      id: '4', description: 'Compra', amount: 50, date: '2026-05-10',
      type: 'EXPENSE', status: 'PAID', categoryPath: null, categoryName: null,
      accountName: null, installmentNumber: null, totalInstallments: null,
      invoiceDueDate: '2026-08-10', projected: false,
    } as TransactionResponseDTO]);

    const row = csv.split('\n')[1];
    expect(row).toContain('ago/2026');
  });

  it('escapa campo com ponto-e-vírgula em aspas duplas (RFC 4180)', () => {
    const csv = exportToCsv([{
      id: '5', description: 'A; B', amount: 10, date: '2026-06-01',
      type: 'INCOME', status: 'PAID', categoryPath: null, categoryName: null,
      accountName: null, installmentNumber: null, totalInstallments: null,
      invoiceDueDate: null, projected: false,
    } as TransactionResponseDTO]);

    const row = csv.split('\n')[1];
    expect(row).toContain('"A; B"');
  });

  it('mapeia transferId para Tipo=Transferência', () => {
    const csv = exportToCsv([{
      id: '6', description: 'TED', amount: 500, date: '2026-06-01',
      type: 'EXPENSE', status: 'PAID', categoryPath: null, categoryName: null,
      accountName: null, installmentNumber: null, totalInstallments: null,
      invoiceDueDate: null, projected: false, transferId: 'tr-uuid',
    } as TransactionResponseDTO]);

    const row = csv.split('\n')[1];
    expect(row).toContain('Transferência');
  });
});
```

Adicionar `exportToCsv` no import do topo do arquivo:
```ts
import { sortTransactions, applySort, getSortInfo, isGhost, exportToCsv } from './transaction-list.utils';
```

- [ ] **Step 2: Rodar o teste para confirmar que falha**

```bash
cd frontend && npx vitest run transaction-list.utils.spec.ts 2>&1 | tail -20
```

Esperado: erro `exportToCsv is not a function` ou similar. O teste deve falhar porque a função ainda não existe.

- [ ] **Step 3: Implementar `exportToCsv` em `transaction-list.utils.ts`**

Adicionar ao final do arquivo `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`:

```ts
const MONTH_ABBR_PT = ['jan','fev','mar','abr','mai','jun','jul','ago','set','out','nov','dez'];

function csvField(value: string | null | undefined): string {
  const v = value ?? '';
  if (v.includes(';') || v.includes('"') || v.includes('\n')) {
    return '"' + v.replace(/"/g, '""') + '"';
  }
  return v;
}

export function exportToCsv(transactions: TransactionResponseDTO[]): string {
  const TYPE_LABEL: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' };
  const STATUS_LABEL: Record<string, string> = { PAID: 'Pago', PENDING: 'Pendente', CANCELLED: 'Cancelado' };

  const header = 'Data;Descrição;Valor;Tipo;Status;Categoria;Conta;Parcela;Fatura';

  const rows = transactions.map(t => {
    const [y, m, d] = (t.date ?? '').split('-');
    const data     = d && m && y ? `${d}/${m}/${y}` : '';
    const valor    = (t.amount ?? 0).toFixed(2).replace('.', ',');
    const tipo     = t.transferId ? 'Transferência' : (TYPE_LABEL[t.type ?? ''] ?? t.type ?? '');
    const status   = STATUS_LABEL[t.status ?? ''] ?? t.status ?? '';
    const categoria = csvField(t.categoryPath ?? t.categoryName ?? '');
    const conta    = csvField(t.accountName ?? '');
    const parcela  = t.installmentNumber && t.totalInstallments
      ? `${t.installmentNumber}/${t.totalInstallments}`
      : '';
    const fatura   = t.invoiceDueDate
      ? (() => {
          const [fy, fm] = t.invoiceDueDate.split('-').map(Number);
          return `${MONTH_ABBR_PT[(fm ?? 1) - 1]}/${fy}`;
        })()
      : '';

    return [csvField(data), csvField(t.description), valor, tipo, status, categoria, conta, parcela, fatura].join(';');
  });

  return [header, ...rows].join('\n');
}
```

> **Atenção:** `csvField` já retorna o valor com aspas se necessário. `data`, `valor`, `tipo`, `status`, `parcela` e `fatura` não precisam de escape adicional (não contêm `;` por construção). Apenas `description`, `categoryPath`/`categoryName` e `accountName` podem ter caracteres problemáticos.

- [ ] **Step 4: Rodar os testes e confirmar que passam**

```bash
cd frontend && npx vitest run transaction-list.utils.spec.ts 2>&1 | tail -20
```

Esperado: todos os testes do arquivo passando (`✓ exportToCsv ...`).

- [ ] **Step 5: Commit**

```bash
cd frontend && git add src/app/features/transaction/transaction-list/transaction-list.utils.ts src/app/features/transaction/transaction-list/transaction-list.utils.spec.ts && git commit -m "feat(transacoes): adiciona função exportToCsv pura com testes"
```

---

### Task 2: Integração no componente — método `exportCsv()` + botão no template

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.html`

**Interfaces:**
- Consome: `exportToCsv(transactions: TransactionResponseDTO[]): string` (definida na Task 1)
- Consome: `isGhost` (já importado no componente)
- Consome: `filteredTransactions` (signal já existente no componente)

---

- [ ] **Step 1: Adicionar import de `exportToCsv` em `transaction-list.ts`**

Localizar a linha de import de `transaction-list.utils`:
```ts
import { buildDisplayRows, InstallmentGroupInfo, DisplayRow, InvoiceSummaryRow, resolveMonthKey, formatMonthLabel, SortCol, SortCriterion, applySort, getSortInfo, isGhost } from './transaction-list.utils';
```

Substituir por (adicionar `exportToCsv` ao final da lista):
```ts
import { buildDisplayRows, InstallmentGroupInfo, DisplayRow, InvoiceSummaryRow, resolveMonthKey, formatMonthLabel, SortCol, SortCriterion, applySort, getSortInfo, isGhost, exportToCsv } from './transaction-list.utils';
```

- [ ] **Step 2: Adicionar método `exportCsv()` no corpo da classe `TransactionList`**

Adicionar após o método `statusLabel` (linha ~407), antes do método `invoiceChipClass`:

```ts
exportCsv(): void {
  const reais = this.filteredTransactions().filter(t => !isGhost(t));
  const csv   = exportToCsv(reais);
  const blob  = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
  const url   = URL.createObjectURL(blob);
  const a     = Object.assign(document.createElement('a'), {
    href: url,
    download: `transacoes-${new Date().toISOString().slice(0, 10)}.csv`,
  });
  a.click();
  URL.revokeObjectURL(url);
}
```

- [ ] **Step 3: Adicionar botão no template `transaction-list.html`**

Localizar o bloco `<div class="header-actions">` e adicionar o botão `mat-icon-button` **antes** do botão de "Nova Transação":

```html
<div class="header-actions">
  <button mat-stroked-button (click)="toggleFilters()">
    <mat-icon>tune</mat-icon>
    Filtrar
    @if (activeFilterChips().length > 0) {
      <span class="filter-badge">{{ activeFilterChips().length }}</span>
    }
  </button>
  <a mat-stroked-button routerLink="/transactions/timeline">
    <mat-icon>timeline</mat-icon>
    Linha do tempo
  </a>
  <button mat-icon-button (click)="exportCsv()" matTooltip="Exportar CSV">
    <mat-icon>download</mat-icon>
  </button>
  <button mat-flat-button color="primary" routerLink="/transactions/new">
    <mat-icon>add</mat-icon>
    Nova Transação
  </button>
</div>
```

- [ ] **Step 4: Verificar compilação TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -30
```

Esperado: sem erros. Se aparecer erro de tipo, checar se `exportToCsv` foi importado corretamente.

- [ ] **Step 5: Rodar todos os testes do frontend**

```bash
cd frontend && npx vitest run 2>&1 | tail -20
```

Esperado: todos os testes passando, sem regressões.

- [ ] **Step 6: Commit**

```bash
cd frontend && git add src/app/features/transaction/transaction-list/transaction-list.ts src/app/features/transaction/transaction-list/transaction-list.html && git commit -m "feat(transacoes): adiciona botão de export CSV com filtro ativo"
```
