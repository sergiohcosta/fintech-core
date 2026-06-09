# Design: Melhorias nos Filtros de Transações e Faturas

**Data:** 2026-06-09
**Issues:** #52, #62, #66, #64
**Escopo:** Frontend apenas

---

## Contexto

Quatro melhorias independentes na área de transações e faturas, todas no frontend Angular (Zoneless + Signals).

---

## Feature 1 — Auto-selecionar conta única em Faturas (#52)

### Problema
`InvoiceList` exige que o usuário selecione a conta mesmo quando o tenant possui apenas um cartão de crédito.

### Solução
No `ngOnInit()` de `InvoiceList`, após filtrar as contas `CREDIT_CARD`:
- Se `cc.length === 1` e não há `accountId` na query string → `this.selectedId.set(cc[0].id)`
- O `effect` existente já reage ao `selectedId` e carrega as faturas automaticamente

### Arquivo alterado
- `frontend/src/app/features/invoice/invoice-list/invoice-list.ts`

---

## Feature 2 — Persistência dos Filtros (#62)

### Problema
Os filtros de transações são perdidos ao navegar para outra tela e voltar.

### Solução
`TransactionList` gerencia persistência via `localStorage`.

**Chave:** `fintech.transaction.filters`

**O que persiste:** `accountIds`, `status`, `type`, `startDate`, `endDate`, `groupByPeriod`, `groupByInvoice`

**O que NÃO persiste:** `description` — busca textual é pontual; persistir causaria confusão ("por que estão faltando transações?")

**Fluxo:**
1. `ngOnInit()` tenta ler e parsear `localStorage`. Se inválido ou ausente, usa `DEFAULT_FILTERS`.
2. `TransactionFiltersComponent` recebe um input `initialFilters` e inicializa seus signals internos com esses valores.
3. `onFilterChange()` salva no `localStorage` após atualizar o signal interno (sem persistir `description`).

### Arquivos alterados
- `transaction-filters.types.ts` — nenhum (os tipos já cobrem)
- `transaction-filters.ts` — input `initialFilters`
- `transaction-list.ts` — leitura/escrita no localStorage

---

## Feature 3 — Filtro por Descrição (client-side) (#66)

### Problema
Não há como buscar transações por texto da descrição.

### Decisão de arquitetura
Client-side (não server-side): o projeto carrega todas as transações sem paginação, então o conjunto já está completo em memória. Filtro client-side dá resposta instantânea sem round-trip ao servidor e sem mudanças no backend.

### Modelo de dados
`TransactionFilters` e `DEFAULT_FILTERS` ganham:
```typescript
description: string | null;  // null = sem filtro
```

### Fluxo de dados
```
transactions (signal, server)
  → filteredTransactions (computed, client-side, aplica description)
    → buildDisplayRows (já existente)
      → displayRows (computed, renderizado na tabela)
```

`filteredTransactions` em `TransactionList`:
```typescript
filteredTransactions = computed(() => {
  const desc = this.filters().description?.toLowerCase().trim();
  if (!desc) return this.transactions();
  return this.transactions().filter(t =>
    t.description?.toLowerCase().includes(desc)
  );
});
```

### UI
- Campo de texto no painel de filtros com label "Buscar por descrição" e ícone `search`
- Chip ativo "Descrição: X" quando preenchido, com botão de remoção
- `onDescriptionChange()` emite filtro imediatamente (sem debounce — client-side é instantâneo)

### Arquivos alterados
- `transaction-filters.types.ts`
- `transaction-filters.ts` + `.html`
- `transaction-list.ts`

---

## Feature 4 — Agrupar Transações de Cartão por Fatura (#64)

### Problema
Transações de cartão aparecem na lista sem agrupamento por fatura, dificultando entender o impacto de cada fatura no orçamento.

### Modelo de dados

**`TransactionFilters`** ganha:
```typescript
groupByInvoice: boolean;
```

**`DisplayRow`** ganha nova variante:
```typescript
| {
    kind: 'invoice-header';
    invoiceId: string | null;   // null = "Avulsas"
    label: string;              // ex: "Fatura jun/2026"
    dueDate: string | null;
    totalAmount: number;
    status: string | null;
    transactionCount: number;
  }
```

### Lógica de agrupamento

Nova função `buildDisplayRowsGroupedByInvoice` em `transaction-list.utils.ts`:

1. Separa transações com `invoiceId` das sem `invoiceId`
2. Agrupa as que têm `invoiceId` por `invoiceId`
3. Para cada grupo, cria um `invoice-header` + chama `buildFlatRows` para as transações do grupo
4. Transações sem `invoiceId` vão ao final numa seção com `invoice-header` de `invoiceId: null` e `label: 'Avulsas'`
5. Grupos ordenados por `dueDate` desc (mais recente primeiro)

**Cálculo do `totalAmount`:** soma de `amount` das transações do grupo com `type === 'EXPENSE'`, subtraindo as de `type === 'INCOME'`.

**`buildDisplayRows`** atualizado para aceitar o novo flag:
```typescript
export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>,
  groupByPeriod = false,
  groupByInvoice = false
): DisplayRow[]
```

### Exclusividade mútua
`groupByPeriod` e `groupByInvoice` são mutuamente exclusivos. No `TransactionFiltersComponent`:
- Ativar `groupByInvoice` → força `groupByPeriod = false`
- Ativar `groupByPeriod` → força `groupByInvoice = false`

### UI
- Toggle "Agrupar por fatura" no rodapé do painel de filtros, ao lado de "Agrupar por período"
- `invoice-header` row: mesmo visual do `period-header`, com label da fatura, total e status chip (OPEN/CLOSED/PAID)
- Predicado de row: `isInvoiceHeader = (_, row) => row.kind === 'invoice-header'`
- `displayedColumns` da tabela não muda — o `invoice-header` usa `colspan` igual ao `period-header`

### Arquivos alterados
- `transaction-filters.types.ts`
- `transaction-filters.ts` + `.html`
- `transaction-list.utils.ts`
- `transaction-list.ts`
- `transaction-list.html`

---

## Resumo de arquivos

| Arquivo | Features |
|---|---|
| `transaction-filters.types.ts` | #62 (sem mudança), #66 (`description`), #64 (`groupByInvoice`) |
| `transaction-filters.ts` | #62 (`initialFilters` input), #66 (handler), #64 (toggle + exclusividade) |
| `transaction-filters.html` | #66 (campo texto), #64 (toggle) |
| `transaction-list.utils.ts` | #64 (`invoice-header`, `buildDisplayRowsGroupedByInvoice`) |
| `transaction-list.ts` | #62 (localStorage), #66 (`filteredTransactions`), #64 (novos flags) |
| `transaction-list.html` | #64 (row `invoice-header`) |
| `invoice-list.ts` | #52 (auto-select) |

---

## Ordem de implementação recomendada

1. **#52** — trivial, zero risco
2. **#66** — adiciona `description` ao tipo + filtro client-side
3. **#62** — persistência (depende do tipo final de `TransactionFilters` com `description`)
4. **#64** — maior complexidade, melhor fazer por último

---

## O que está fora do escopo

- Mudanças no backend
- Paginação de transações
- Testes automatizados (cobertos pela issue #57, que trata a infraestrutura de testes do frontend)
