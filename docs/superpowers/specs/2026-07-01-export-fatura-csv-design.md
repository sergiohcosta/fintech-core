# Spec: Export de Fatura em CSV

**Data:** 2026-07-01
**Status:** aprovado

---

## Contexto

O Detalhe da Fatura (`/invoices/:id`) já lista as transações da fatura em duas tabelas (Compras Parceladas + Demais Transações). O export de transações em `/transactions` (2026-06-30) já estabeleceu o padrão CSV pt-BR 100% frontend. O objetivo aqui é permitir exportar as transações de **uma fatura específica** para CSV, reaproveitando esse padrão.

---

## Decisões

- **Escopo:** botão apenas no Detalhe da Fatura (`/invoices/:id`). Lista de Faturas (`/invoices`) fica fora — exportaria resumo por fatura, feature diferente.
- **Linhas:** `installmentTxs()` + `regularTxs()` concatenados — mesmo conjunto e agrupamento (parceladas primeiro) já exibido nas duas tabelas da tela, **incluindo `CANCELLED`** (registro histórico, como já é mostrado hoje). O CSV espelha exatamente o que está na tela.
- **Botão sempre visível**, independente do status da fatura (`OPEN`/`CLOSED`/`PAID`).
- **Abordagem escolhida — extrair boilerplate genérico compartilhado:** a spec do export de transações descartou uma abstração compartilhada com a justificativa "só um consumidor hoje; extrair quando houver o segundo". Este é o segundo consumidor. Extrai-se `csvField` (escape RFC 4180) e o disparo de download (Blob + âncora sintética) para `frontend/src/app/core/csv.utils.ts`, reaproveitado pelos dois exports. O mapeamento de colunas (específico de cada domínio) permanece local em cada `*.utils.ts`.
- **Abordagem descartada — duplicar o boilerplate:** manteria `csvField`/download inline em cada feature; descartada agora que há um segundo consumidor real.
- **Abordagem descartada — backend endpoint:** dados já em memória no componente; endpoint seria trabalho duplo sem ganho (mesmo racional da spec anterior).

---

## Colunas

| Cabeçalho CSV | Campo do DTO | Transformação |
|---|---|---|
| Data | `date` | DD/MM/YYYY |
| Descrição | `description` | — |
| Valor | `amount` | vírgula decimal (ex: `1234,56`) |
| Tipo | `type` | Receita / Despesa |
| Status | `status` | Pago / Pendente / Cancelado |
| Categoria | `categoryPath` ?? `categoryName` | vazio se ausente |
| Parcela | `installmentLabel` | já vem pronto do backend (ex: `2/6`); vazio se `null` |

Sem colunas "Conta" nem "Fatura" (presentes no export de transações) — redundantes aqui, pois todas as linhas pertencem à mesma fatura/conta.

Campos com `;` ou `"` são escapados com aspas duplas (RFC 4180), via `csvField` compartilhado.

---

## Componentes afetados

### `core/csv.utils.ts` (novo)

Extraído de `transaction-list.ts` / `transaction-list.utils.ts`:

```ts
export function csvField(value: string | null | undefined): string {
  const v = value ?? '';
  if (v.includes(';') || v.includes('"') || v.includes('\n')) {
    return '"' + v.replace(/"/g, '""') + '"';
  }
  return v;
}

export function triggerCsvDownload(csv: string, filename: string): void {
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = Object.assign(document.createElement('a'), { href: url, download: filename });
  a.click();
  URL.revokeObjectURL(url);
}
```

Sem imports Angular — mesma convenção de arquivos `.utils.ts` do projeto (funções puras/utilitárias testáveis sem `TestBed`; `triggerCsvDownload` tem efeito colateral em DOM e não é unit-testada, mesmo padrão do que já existia inline).

### `transaction-list.utils.ts` (modificado)

Remove a definição local de `csvField`; passa a importar de `../../../core/csv.utils`. `exportToCsv` inalterado.

### `transaction-list.ts` (modificado)

`exportCsv()` passa a chamar `triggerCsvDownload(csv, filename)` em vez de montar o Blob/âncora inline.

### `invoice-detail.utils.ts` (modificado)

Nova função pura exportada:

```ts
export function exportInvoiceToCsv(transactions: TransactionResponseDTO[]): string
```

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

### `invoice-detail.ts` (modificado)

Novo método no componente:

```ts
exportCsv(): void {
  const inv = this.invoice();
  if (!inv) return;
  const csv = exportInvoiceToCsv([...this.installmentTxs(), ...this.regularTxs()]);
  const mes = String(inv.referenceMonth).padStart(2, '0');
  triggerCsvDownload(csv, `fatura-${inv.accountName.replace(/\s+/g, '-')}-${inv.referenceYear}-${mes}.csv`);
}
```

Nome do arquivo: `fatura-{accountName sem espaços}-{referenceYear}-{referenceMonth com 2 dígitos}.csv`.

### `invoice-detail.html` (modificado)

Botão adicionado em `.header-actions`, ao lado do chip de status (antes do botão Fechar/Pagar):

```html
<div class="header-actions">
  <span [class]="statusChipClass(inv.status)">{{ statusLabel(inv.status) }}</span>
  <button mat-icon-button (click)="exportCsv()" matTooltip="Exportar CSV">
    <mat-icon>download</mat-icon>
  </button>
  @if (inv.status === InvoiceStatus.OPEN) {
    <button mat-flat-button color="accent" (click)="onClose()">...</button>
  }
  @if (inv.status === InvoiceStatus.CLOSED) {
    <button mat-flat-button color="primary" (click)="onPay()">...</button>
  }
</div>
```

---

## Teste

Em `invoice-detail.utils.spec.ts`, novos casos para `exportInvoiceToCsv`:

- Cabeçalho e linha com campos corretos (data DD/MM/YYYY, valor com vírgula, tipo/status traduzidos)
- Usa `categoryName` quando `categoryPath` é `null`
- `Parcela` vazia quando `installmentLabel` é `null`
- `Parcela` presente quando `installmentLabel` tem valor (ex: `"2/6"`)
- Escapa campo com `;` em aspas duplas (RFC 4180)
- Transação `CANCELLED` aparece na linha com `Status=Cancelado`

`csvField` já é coberta indiretamente pelos testes existentes de `exportToCsv` em `transaction-list.utils.spec.ts` (que passam a exercitar a versão importada de `core/csv.utils.ts`).

---

## Fora de escopo

- Export na Lista de Faturas (resumo por fatura)
- Breakdown por categoria no CSV
- Seleção de colunas pelo usuário
- Export XLSX / PDF
- Indicador de loading (download é instantâneo — dados já em memória)
