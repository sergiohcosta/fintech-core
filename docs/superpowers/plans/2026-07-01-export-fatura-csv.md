# Export de Fatura em CSV — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar botão "Exportar CSV" no Detalhe da Fatura (`/invoices/:id`) que baixa as transações daquela fatura (parceladas + demais) em arquivo `.csv` compatível com Excel pt-BR, reaproveitando (e extraindo para um módulo compartilhado) o boilerplate já usado pelo export de transações.

**Architecture:** `csvField` (escape RFC 4180) e `triggerCsvDownload` (Blob + âncora sintética) saem de `transaction-list.ts`/`.utils.ts` para um novo módulo compartilhado `core/csv.utils.ts` — o export de transações passa a consumir dali. Uma nova função pura `exportInvoiceToCsv` em `invoice-detail.utils.ts` monta o CSV específico da fatura (colunas menores, sem "Conta"/"Fatura" — redundantes num CSV já escopado a uma única fatura). O componente `InvoiceDetail` chama essa função com `[...installmentTxs(), ...regularTxs()]` e dispara o download via `triggerCsvDownload`.

**Tech Stack:** Angular 21 (Zoneless, Signals), Vitest 4.x, TypeScript 5.9 strict. Zero dependências externas.

## Global Constraints

- Separador CSV: `;` (padrão pt-BR)
- Encoding: UTF-8 com BOM (`﻿`) — via `triggerCsvDownload` compartilhado
- Escape RFC 4180 via `csvField` compartilhado (`core/csv.utils.ts`): campos com `;`, `"` ou quebra de linha envolvidos em aspas duplas; `"` interno → `""`
- Linhas: `installmentTxs()` + `regularTxs()` concatenados — mesmo conjunto e agrupamento (parceladas primeiro) já exibido nas duas tabelas da tela, **incluindo `CANCELLED`**
- Colunas (nesta ordem): Data, Descrição, Valor, Tipo, Status, Categoria, Parcela
- `Parcela` usa `installmentLabel` (já pronto no DTO, ex: `"2/6"`) — sem montagem manual
- Botão sempre visível, independente do status da fatura (`OPEN`/`CLOSED`/`PAID`)
- Sem novos endpoints de backend, sem novas libs npm

---

### Task 1: Extrair `core/csv.utils.ts` e refatorar o export de transações para usá-lo

**Files:**
- Create: `frontend/src/app/core/csv.utils.ts`
- Create: `frontend/src/app/core/csv.utils.spec.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`

**Interfaces:**
- Produces: `export function csvField(value: string | null | undefined): string`
- Produces: `export function triggerCsvDownload(csv: string, filename: string): void`
- Consumido por: `transaction-list.utils.ts` (`exportToCsv`, Task já existente), `transaction-list.ts` (`exportCsv()`), e pela Task 2/3 deste plano (`invoice-detail.utils.ts`, `invoice-detail.ts`)

---

- [ ] **Step 1: Escrever o teste com falha para `csvField`**

Criar `frontend/src/app/core/csv.utils.spec.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { csvField } from './csv.utils';

describe('csvField', () => {
  it('retorna string vazia para null/undefined', () => {
    expect(csvField(null)).toBe('');
    expect(csvField(undefined)).toBe('');
  });

  it('retorna o valor sem alteração quando não há caracteres especiais', () => {
    expect(csvField('Nubank')).toBe('Nubank');
  });

  it('envolve em aspas duplas quando contém ponto-e-vírgula', () => {
    expect(csvField('A; B')).toBe('"A; B"');
  });

  it('escapa aspas duplas internas duplicando-as', () => {
    expect(csvField('Diz "oi"')).toBe('"Diz ""oi"""');
  });

  it('envolve em aspas duplas quando contém quebra de linha', () => {
    expect(csvField('linha1\nlinha2')).toBe('"linha1\nlinha2"');
  });
});
```

- [ ] **Step 2: Rodar o teste para confirmar que falha**

```bash
cd frontend && npx vitest run src/app/core/csv.utils.spec.ts 2>&1 | tail -20
```

Esperado: erro do tipo `Cannot find module './csv.utils'` ou `Failed to resolve import`.

- [ ] **Step 3: Implementar `core/csv.utils.ts`**

Criar `frontend/src/app/core/csv.utils.ts`:

```ts
// Escapa campo CSV segundo RFC 4180: se contém `;`, `"` ou `\n`, envolve em aspas duplas e duplica aspas internas.
export function csvField(value: string | null | undefined): string {
  const v = value ?? '';
  if (v.includes(';') || v.includes('"') || v.includes('\n')) {
    return '"' + v.replace(/"/g, '""') + '"';
  }
  return v;
}

export function triggerCsvDownload(csv: string, filename: string): void {
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
  const url  = URL.createObjectURL(blob);
  const a    = Object.assign(document.createElement('a'), { href: url, download: filename });
  a.click();
  URL.revokeObjectURL(url);
}
```

- [ ] **Step 4: Rodar o teste e confirmar que passa**

```bash
cd frontend && npx vitest run src/app/core/csv.utils.spec.ts 2>&1 | tail -20
```

Esperado: todos os 5 casos passando.

- [ ] **Step 5: Refatorar `transaction-list.utils.ts` para importar `csvField`**

Em `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`, adicionar import logo após o import existente do topo (linha 1):

```ts
import { TransactionResponseDTO, InvoiceResponseDTO, InvoiceStatus } from '../../../core/api/fintechSaaSAPI.schemas';
import { csvField } from '../../../core/csv.utils';
```

Remover a definição local de `csvField` (perto do final do arquivo, logo antes de `export function exportToCsv`):

Substituir:
```ts
// Escapa campo CSV segundo RFC 4180: se contém `;`, `"` ou `\n`, envolve em aspas duplas e duplica aspas internas.
function csvField(value: string | null | undefined): string {
  const v = value ?? '';
  if (v.includes(';') || v.includes('"') || v.includes('\n')) {
    return '"' + v.replace(/"/g, '""') + '"';
  }
  return v;
}

export function exportToCsv(transactions: TransactionResponseDTO[]): string {
```

Por:
```ts
export function exportToCsv(transactions: TransactionResponseDTO[]): string {
```

- [ ] **Step 6: Rodar os testes de `transaction-list.utils` para confirmar que não há regressão**

```bash
cd frontend && npx vitest run transaction-list.utils.spec.ts 2>&1 | tail -20
```

Esperado: todos os testes existentes (incluindo os de `exportToCsv` e escaping) continuam passando — `csvField` agora vem de `core/csv.utils.ts`, comportamento idêntico.

- [ ] **Step 7: Refatorar `transaction-list.ts` para usar `triggerCsvDownload`**

Em `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`, adicionar import após a linha do `RecurrenceService` (linha 25):

```ts
import { RecurrenceService } from '../../../core/services/recurrence.service';
import { triggerCsvDownload } from '../../../core/csv.utils';
```

Substituir o corpo do método `exportCsv()`:

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

Por:

```ts
  exportCsv(): void {
    const reais = this.filteredTransactions().filter(t => !isGhost(t));
    const csv   = exportToCsv(reais);
    triggerCsvDownload(csv, `transacoes-${new Date().toISOString().slice(0, 10)}.csv`);
  }
```

- [ ] **Step 8: Verificar compilação TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -30
```

Esperado: sem erros.

- [ ] **Step 9: Commit**

```bash
cd frontend && git add src/app/core/csv.utils.ts src/app/core/csv.utils.spec.ts src/app/features/transaction/transaction-list/transaction-list.utils.ts src/app/features/transaction/transaction-list/transaction-list.ts && git commit -m "refactor(csv): extrai csvField e triggerCsvDownload para core/csv.utils.ts"
```

---

### Task 2: Função pura `exportInvoiceToCsv` + testes

**Files:**
- Modify: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.ts`
- Modify: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.spec.ts`

**Interfaces:**
- Consumes: `csvField(value: string | null | undefined): string` de `../../../core/csv.utils` (Task 1)
- Produces: `export function exportInvoiceToCsv(transactions: TransactionResponseDTO[]): string`
  - Parâmetro: array de `TransactionResponseDTO` (já importado no arquivo)
  - Retorno: string CSV completa (cabeçalho `Data;Descrição;Valor;Tipo;Status;Categoria;Parcela` + linhas, separador `\n`, sem trailing newline)

---

- [ ] **Step 1: Escrever os testes com falha**

Em `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.spec.ts`, atualizar o import do topo (linha 2):

```ts
import { computeBreakdown, exportInvoiceToCsv } from './invoice-detail.utils';
```

Adicionar ao final do arquivo, depois do `describe('computeBreakdown', ...)` existente (reaproveitando o helper `makeTransaction` já definido no topo do arquivo):

```ts
describe('exportInvoiceToCsv', () => {
  it('gera cabeçalho e linha com campos corretos', () => {
    const csv = exportInvoiceToCsv([
      makeTransaction({
        id: 't1', description: 'Netflix', amount: 45.9, date: '2026-06-15',
        type: 'EXPENSE', status: 'PAID', categoryPath: 'Lazer → Streaming', installmentLabel: null
      })
    ]);
    const [header, row] = csv.split('\n');
    expect(header).toBe('Data;Descrição;Valor;Tipo;Status;Categoria;Parcela');
    expect(row).toBe('15/06/2026;Netflix;45,90;Despesa;Pago;Lazer → Streaming;');
  });

  it('usa categoryName quando categoryPath é null', () => {
    const csv = exportInvoiceToCsv([
      makeTransaction({ id: 't2', categoryPath: null, categoryName: 'Alimentação' })
    ]);
    const cols = csv.split('\n')[1].split(';');
    expect(cols[5]).toBe('Alimentação');
  });

  it('deixa Categoria vazia quando categoryPath e categoryName são null', () => {
    const csv = exportInvoiceToCsv([
      makeTransaction({ id: 't3', categoryPath: null, categoryName: null })
    ]);
    const cols = csv.split('\n')[1].split(';');
    expect(cols[5]).toBe('');
  });

  it('usa installmentLabel na coluna Parcela quando presente', () => {
    const csv = exportInvoiceToCsv([
      makeTransaction({ id: 't4', installmentLabel: '2/6' })
    ]);
    const cols = csv.split('\n')[1].split(';');
    expect(cols[6]).toBe('2/6');
  });

  it('deixa Parcela vazia quando installmentLabel é null', () => {
    const csv = exportInvoiceToCsv([
      makeTransaction({ id: 't5', installmentLabel: null })
    ]);
    const cols = csv.split('\n')[1].split(';');
    expect(cols[6]).toBe('');
  });

  it('escapa Descrição com ponto-e-vírgula em aspas duplas (RFC 4180)', () => {
    const csv = exportInvoiceToCsv([
      makeTransaction({ id: 't6', description: 'A; B' })
    ]);
    const row = csv.split('\n')[1];
    expect(row).toContain('"A; B"');
  });

  it('inclui transação CANCELLED com Status=Cancelado', () => {
    const csv = exportInvoiceToCsv([
      makeTransaction({ id: 't7', status: 'CANCELLED' })
    ]);
    const cols = csv.split('\n')[1].split(';');
    expect(cols[4]).toBe('Cancelado');
  });

  it('gera uma linha por transação, preservando a ordem', () => {
    const csv = exportInvoiceToCsv([
      makeTransaction({ id: 't8', description: 'Primeira' }),
      makeTransaction({ id: 't9', description: 'Segunda' })
    ]);
    const lines = csv.split('\n');
    expect(lines).toHaveLength(3);
    expect(lines[1]).toContain('Primeira');
    expect(lines[2]).toContain('Segunda');
  });
});
```

- [ ] **Step 2: Rodar o teste para confirmar que falha**

```bash
cd frontend && npx vitest run invoice-detail.utils.spec.ts 2>&1 | tail -30
```

Esperado: erro do tipo `exportInvoiceToCsv is not a function` ou `does not provide an export named 'exportInvoiceToCsv'`.

- [ ] **Step 3: Implementar `exportInvoiceToCsv` em `invoice-detail.utils.ts`**

Em `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.ts`, adicionar import no topo (linha 1):

```ts
import { TransactionResponseDTO, TransactionStatus } from '../../../core/api/fintechSaaSAPI.schemas';
import { csvField } from '../../../core/csv.utils';
```

Adicionar ao final do arquivo:

```ts
const TYPE_LABEL: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' };
const STATUS_LABEL: Record<string, string> = { PAID: 'Pago', PENDING: 'Pendente', CANCELLED: 'Cancelado' };

export function exportInvoiceToCsv(transactions: TransactionResponseDTO[]): string {
  const header = 'Data;Descrição;Valor;Tipo;Status;Categoria;Parcela';

  const rows = transactions.map(t => {
    const [y, m, d] = (t.date ?? '').split('-');
    const data      = d && m && y ? `${d}/${m}/${y}` : '';
    const valor     = (t.amount ?? 0).toFixed(2).replace('.', ',');
    const tipo      = TYPE_LABEL[t.type ?? ''] ?? t.type ?? '';
    const status    = STATUS_LABEL[t.status ?? ''] ?? t.status ?? '';
    const categoria = csvField(t.categoryPath ?? t.categoryName ?? '');
    const parcela   = t.installmentLabel ?? '';

    return [csvField(data), csvField(t.description), valor, tipo, status, categoria, parcela].join(';');
  });

  return [header, ...rows].join('\n');
}
```

- [ ] **Step 4: Rodar os testes e confirmar que passam**

```bash
cd frontend && npx vitest run invoice-detail.utils.spec.ts 2>&1 | tail -30
```

Esperado: todos os testes do arquivo passando, incluindo os 8 novos casos de `exportInvoiceToCsv`.

- [ ] **Step 5: Commit**

```bash
cd frontend && git add src/app/features/invoice/invoice-detail/invoice-detail.utils.ts src/app/features/invoice/invoice-detail/invoice-detail.utils.spec.ts && git commit -m "feat(faturas): adiciona função exportInvoiceToCsv pura com testes"
```

---

### Task 3: Integração no componente — método `exportCsv()` + botão no template

**Files:**
- Modify: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.ts`
- Modify: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.html`

**Interfaces:**
- Consumes: `exportInvoiceToCsv(transactions: TransactionResponseDTO[]): string` (Task 2)
- Consumes: `triggerCsvDownload(csv: string, filename: string): void` (Task 1)
- Consumes: `invoice` (signal já existente no componente, tipo `InvoiceResponseDTO | null`)
- Consumes: `installmentTxs`, `regularTxs` (computed signals já existentes no componente, tipo `TransactionResponseDTO[]`)

---

- [ ] **Step 1: Adicionar imports em `invoice-detail.ts`**

Adicionar `MatTooltipModule` (necessário para `matTooltip` no botão) logo após o import de `MatDialogModule` (linha 9):

```ts
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
```

Registrar `MatTooltipModule` no array `imports` do `@Component` (o array atual é):

```ts
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatTableModule, MatButtonModule, MatIconModule,
    MatCardModule, MatSnackBarModule, MatDialogModule
  ],
```

Substituir por:

```ts
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatTableModule, MatButtonModule, MatIconModule,
    MatCardModule, MatSnackBarModule, MatDialogModule, MatTooltipModule
  ],
```

Atualizar o import de `invoice-detail.utils` (linha 19 original):

```ts
import { computeBreakdown, CategoryBreakdownRow } from './invoice-detail.utils';
```

Por:

```ts
import { computeBreakdown, CategoryBreakdownRow, exportInvoiceToCsv } from './invoice-detail.utils';
import { triggerCsvDownload } from '../../../core/csv.utils';
```

- [ ] **Step 2: Adicionar o método `exportCsv()` no corpo da classe `InvoiceDetail`**

Adicionar logo após o método `onPay()` (antes de `statusChipClass`):

```ts
  exportCsv(): void {
    const inv = this.invoice();
    if (!inv) return;
    const csv = exportInvoiceToCsv([...this.installmentTxs(), ...this.regularTxs()]);
    const mes = String(inv.referenceMonth).padStart(2, '0');
    triggerCsvDownload(csv, `fatura-${inv.accountName.replace(/\s+/g, '-')}-${inv.referenceYear}-${mes}.csv`);
  }
```

- [ ] **Step 3: Adicionar o botão no template `invoice-detail.html`**

No bloco `<div class="header-actions">` (dentro de `<header class="invoice-header ...">`), o conteúdo atual é:

```html
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
```

Substituir por (botão de export adicionado logo após o chip de status, antes dos botões condicionais):

```html
      <div class="header-actions">
        <span [class]="statusChipClass(inv.status)">{{ statusLabel(inv.status) }}</span>
        <button mat-icon-button (click)="exportCsv()" matTooltip="Exportar CSV">
          <mat-icon>download</mat-icon>
        </button>
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
```

- [ ] **Step 4: Verificar compilação TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -30
```

Esperado: sem erros. Se aparecer erro de template sobre `matTooltip` não reconhecido, confirmar que `MatTooltipModule` foi adicionado ao array `imports` do `@Component` (Step 1).

- [ ] **Step 5: Rodar a suíte completa do frontend via `ng test`**

`invoice-detail.spec.ts` usa `TestBed` (componente, não lógica pura) — por convenção do projeto, specs de componente exigem `ng test`, não `npx vitest` cru.

```bash
cd frontend && npm test -- --watch=false 2>&1 | tail -60
```

Esperado: todos os testes passando, sem regressões — incluindo os 5 testes existentes de `invoice-detail.spec.ts` (nenhum deles verifica ausência de outros botões além de "Fechar Fatura"/"Pagar Fatura", então o novo botão de export não deve quebrá-los).

- [ ] **Step 6: Commit**

```bash
cd frontend && git add src/app/features/invoice/invoice-detail/invoice-detail.ts src/app/features/invoice/invoice-detail/invoice-detail.html && git commit -m "feat(faturas): adiciona botão de export CSV na tela de detalhe"
```
