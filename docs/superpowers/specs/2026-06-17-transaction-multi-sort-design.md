# Design: Multi-Sort na Lista de Transações

**Data:** 2026-06-17  
**Status:** Aprovado  
**Escopo:** Frontend only — `features/transaction/transaction-list/`

---

## Objetivo

Permitir que o usuário ordene a lista de transações por múltiplos cabeçalhos com prioridade definida. Click simples define o critério primário; Shift+click adiciona critérios secundários. Funciona tanto na view plana quanto dentro de grupos (por período ou por fatura).

---

## Estado

### Tipos (adicionados em `transaction-list.utils.ts`)

```ts
export type SortCol = 'description' | 'amount' | 'date' | 'type' | 'status' | 'category' | 'account';
export type SortDir = 'asc' | 'desc';
export type SortCriterion = { col: SortCol; dir: SortDir };
```

### Signal no componente (`transaction-list.ts`)

```ts
sortCriteria = signal<SortCriterion[]>([{ col: 'date', dir: 'desc' }]);
```

- Default: data decrescente — espelha a ordem que o backend já retorna.
- Persiste apenas na sessão (sem localStorage; não foi requisitado).

---

## Modelo de Interação

| Ação | Comportamento |
|------|---------------|
| Click em header (coluna é critério primário) | Inverte direção (asc ↔ desc) |
| Click em header (coluna é secundária ou ausente) | Substitui todos os critérios por `[{col, dir:'asc'}]` |
| Shift+Click em header (coluna está na lista) | Inverte direção daquela coluna, mantém posição |
| Shift+Click em header (coluna ausente) | Acrescenta ao final: `[...criteria, {col, dir:'asc'}]` |

Sem reset explícito — clicar em "Data" sem shift sempre volta ao sort primário por data.

---

## Sort Function

### Assinatura (em `transaction-list.utils.ts`)

```ts
export function sortTransactions(
  transactions: TransactionResponseDTO[],
  criteria: SortCriterion[]
): TransactionResponseDTO[]
```

### Comparador multi-chave

Itera `criteria` em ordem; o primeiro critério que retornar `!= 0` determina a posição.

| Coluna | Lógica de comparação |
|--------|---------------------|
| `date` | `effectiveSortDate`: installment+invoice → `invoiceDueDate`; demais → `date` (string ISO, `localeCompare`) |
| `amount` | Numérico (`a.amount - b.amount`) |
| `description` | `localeCompare` case-insensitive |
| `category` | `localeCompare` em `categoryName`, null vai ao final |
| `account` | `localeCompare` em `accountName`, null vai ao final |
| `type` | Ordem fixa: `INCOME(0) < EXPENSE(1)`, transferências por último |
| `status` | Ordem fixa: `PENDING(0) < PAID(1) < CANCELLED(2)` |

O sort é estável (preserve order para empates) — garantido pela implementação com índice de desempate.

### Integração com grupos

`sortTransactions` é chamado em cada bucket **antes** de `buildFlatRows`:
- View plana: aplicado em `buildFlatRows`
- Group by period: aplicado em `buildFlatRows` de cada `PeriodGroup`
- Group by invoice: aplicado em `buildFlatRows` de cada bucket de fatura

`sortTransferPairsTogether` continua sendo chamado **dentro** de `buildFlatRows` — garante que pares de transferência fiquem adjacentes independente do sort externo.

### Assinatura atualizada de `buildDisplayRows`

```ts
export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>,
  groupByPeriod?: boolean,
  groupByInvoice?: boolean,
  sortCriteria?: SortCriterion[]   // novo parâmetro opcional
): DisplayRow[]
```

O parâmetro é opcional — sem critério explícito, mantém a ordem recebida (backward compatible).

---

## Visual dos Headers

Cada uma das 7 colunas de dado recebe um `<button class="sort-header">` dentro do `<th>`:

```html
<th mat-header-cell *matHeaderCellDef>
  <button class="sort-header" (click)="onSortClick('date', $event)">
    Data
    @if (getSortInfo('date'); as info) {
      @if (sortCriteria().length > 1) {
        <span class="sort-badge">{{ info.priority }}</span>
      }
      <mat-icon class="sort-arrow">
        {{ info.dir === 'asc' ? 'arrow_upward' : 'arrow_downward' }}
      </mat-icon>
    }
  </button>
</th>
```

- `getSortInfo(col)` → `{ priority: number, dir: SortDir } | null`
- Badge numérico (①, ②, …) exibido somente quando há 2+ critérios ativos
- Com 1 critério: só a seta, sem número (menos ruído visual)
- Estilo: cursor pointer, underline/destaque sutil no hover, sem borda pesada

### Métodos auxiliares no componente

```ts
onSortClick(col: SortCol, event: MouseEvent): void  // lógica de click/shift+click
getSortInfo(col: SortCol): { priority: number; dir: SortDir } | null
```

---

## Arquivos Afetados

| Arquivo | Mudança |
|---------|---------|
| `transaction-list.utils.ts` | Adiciona `SortCol`, `SortDir`, `SortCriterion`; `sortTransactions()`; novo param em `buildDisplayRows` |
| `transaction-list.ts` | Adiciona `sortCriteria` signal; `onSortClick()`; `getSortInfo()`; atualiza `displayRows` computed |
| `transaction-list.html` | Headers das 7 colunas viram botões com indicadores de sort |
| `transaction-list.scss` | Estilos de `.sort-header`, `.sort-badge`, `.sort-arrow` |

---

## Fora do Escopo

- Persistência no localStorage (não solicitado)
- Reset explícito por terceiro clique (YAGNI — click em "Data" já volta ao default)
- Paginação ou sort server-side
- Mudanças no backend ou na spec OpenAPI
