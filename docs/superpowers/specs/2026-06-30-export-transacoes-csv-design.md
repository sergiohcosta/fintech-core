# Spec: Export de Transações em CSV

**Data:** 2026-06-30  
**Status:** aprovado

---

## Contexto

A lista de transações (`/transactions`) já aplica filtros no backend e no frontend. O objetivo é permitir que o usuário exporte as transações visíveis (filtro ativo) para um arquivo CSV, sem novo endpoint de backend e sem dependências externas.

---

## Decisões

- **Formato:** CSV com separador `;` (padrão pt-BR — Excel abre sem configuração)
- **Escopo:** `filteredTransactions().filter(t => !isGhost(t))` — dados já em memória, somente transações reais (sem fantasmas de recorrência projetados)
- **Encoding:** UTF-8 com BOM (`﻿`) — garante acentos no Excel/Windows
- **Geração:** 100% frontend, função pura em `transaction-list.utils.ts`
- **Abordagem descartada — backend endpoint:** dados já estão em memória; endpoint seria trabalho duplo sem ganho
- **Abordagem descartada — service injetável:** só um consumidor hoje; extrair quando houver o segundo

---

## Colunas

| Cabeçalho CSV | Campo do DTO | Transformação |
|---|---|---|
| Data | `date` | DD/MM/YYYY |
| Descrição | `description` | — |
| Valor | `amount` | vírgula decimal (ex: `1234,56`) |
| Tipo | `type` | Receita / Despesa / Transferência |
| Status | `status` | Pago / Pendente / Cancelado |
| Categoria | `categoryPath` ?? `categoryName` | vazio se ausente |
| Conta | `accountName` | vazio se ausente |
| Parcela | `installmentNumber` + `totalInstallments` | `2/6` ou vazio |
| Fatura | `invoiceDueDate` | `ago/2026` ou vazio |

Campos com `;` ou `"` são escapados com aspas duplas (RFC 4180).

---

## Componentes afetados

### `transaction-list.utils.ts`

Nova função pura exportada:

```ts
export function exportToCsv(transactions: TransactionResponseDTO[]): string
```

- Gera string CSV completa (cabeçalho + linhas)
- Sem efeitos colaterais, sem imports Angular
- Testável no Vitest sem `TestBed`

### `transaction-list.ts`

Novo método no componente:

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

Nome do arquivo: `transacoes-YYYY-MM-DD.csv`.

### `transaction-list.html`

Botão adicionado na toolbar, ao lado do botão "Filtros":

```html
<button mat-icon-button (click)="exportCsv()" matTooltip="Exportar CSV">
  <mat-icon>download</mat-icon>
</button>
```

---

## Teste

Em `transaction-list.utils.spec.ts` (já existe), novo caso para `exportToCsv`:

```ts
it('exportToCsv gera cabeçalho e linha com campos corretos', () => {
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
```

---

## Fora de escopo

- Seleção de colunas pelo usuário
- Export XLSX / PDF
- Export de outras telas (invoices, planning)
- Indicador de loading (download é instantâneo — dados já em memória)
