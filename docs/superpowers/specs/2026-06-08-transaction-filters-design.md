# Design: Filtros e Agrupamento na Listagem de Transações

**Issue:** #41
**Data:** 2026-06-08
**Status:** Aprovado

---

## Resumo

Adicionar filtros (conta, status, tipo, período) e agrupamento por período à listagem de transações. Filtragem acontece no backend via query params; agrupamento é puramente de apresentação no frontend.

---

## Decisões de Design

| Decisão | Escolha | Justificativa |
|---|---|---|
| Onde filtrar | Backend (query params) | Abordagem correta para produção; ensina JPQL dinâmico e evolução de spec |
| Layout dos filtros | Painel colapsável acima da tabela | Limpo por padrão, acessível sob demanda |
| Seletor de período | Mês + date range integrados | Mês preenche start/end; edição manual exibe "Personalizado" |
| Agrupamento | Toggle — cards `mat-expansion-panel` por mês | Mês corrente expandido; demais colapsados; toggle liga/desliga |
| Reatividade | Filtros disparam nova chamada ao backend | Fonte de verdade é o servidor, não derivação local |

---

## Backend

### 1. OpenAPI spec (`api-spec/openapi.yaml`)

Adicionar 5 query params opcionais ao `GET /api/transactions`:

```yaml
parameters:
  - name: accountId
    in: query
    required: false
    schema:
      type: string
      format: uuid
  - name: status
    in: query
    required: false
    schema:
      $ref: '#/components/schemas/TransactionStatus'
  - name: type
    in: query
    required: false
    schema:
      $ref: '#/components/schemas/TransactionType'
  - name: startDate
    in: query
    required: false
    schema:
      type: string
      format: date
  - name: endDate
    in: query
    required: false
    schema:
      type: string
      format: date
```

Após a atualização: `./mvnw compile` regenera `TransactionsApi`; `npm run api:generate` regenera o `transactions.service.ts` via Orval.

### 2. TransactionRepository

Novo método `findAllByTenantWithFilters` substituindo `findAllByTenantWithDetails` na listagem geral. Padrão `(:param IS NULL OR condição)` torna cada filtro verdadeiramente opcional — quando `null`, a cláusula é um no-op.

```java
@Query("""
  SELECT t FROM Transaction t
  LEFT JOIN FETCH t.installmentGroup
  LEFT JOIN FETCH t.category
  LEFT JOIN FETCH t.account
  LEFT JOIN FETCH t.invoice inv
  WHERE t.tenant = :tenant
    AND (:accountId IS NULL OR t.account.id = :accountId)
    AND (:status    IS NULL OR t.status = :status)
    AND (:type      IS NULL OR t.type = :type)
    AND (
      :startDate IS NULL OR :endDate IS NULL
      OR (t.installmentGroup IS NOT NULL AND inv IS NOT NULL AND inv.dueDate BETWEEN :startDate AND :endDate)
      OR ((t.installmentGroup IS NULL OR inv IS NULL)        AND t.date    BETWEEN :startDate AND :endDate)
    )
  ORDER BY t.date DESC
""")
List<Transaction> findAllByTenantWithFilters(
    @Param("tenant")    Tenant tenant,
    @Param("accountId") UUID accountId,
    @Param("status")    TransactionStatus status,
    @Param("type")      TransactionType type,
    @Param("startDate") LocalDate startDate,
    @Param("endDate")   LocalDate endDate
);
```

**Lógica de data (alinhada com a regra de exibição do frontend):**
- Parcela de cartão (`installmentGroup IS NOT NULL AND inv IS NOT NULL`) → filtra por `inv.dueDate`
- Demais (incluindo transações avulsas de cartão) → filtra por `t.date`

> Nota: as queries do Dashboard usam a versão simplificada (`inv IS NOT NULL → dueDate`). Para a listagem, a regra estrita é necessária porque o usuário espera que o filtro de período corresponda à data visível na coluna "Data".
- O `LEFT JOIN FETCH` explícito evita o INNER JOIN implícito do Hibernate que causou o bug do dashboard (issue resolvida anteriormente)

### 3. TransactionService

`findAll(user, invoiceId)` passa a aceitar os 5 novos parâmetros. Quando `invoiceId != null`, mantém o comportamento atual (filtra pela fatura). Caso contrário, chama `findAllByTenantWithFilters`.

### 4. TransactionController

Adicionar `@RequestParam(required = false)` para cada novo parâmetro e delegar ao service. A assinatura do método implementa a interface gerada pelo OpenAPI Generator.

---

## Frontend

### Estado dos filtros

```ts
// transaction-list/transaction-filters/transaction-filters.types.ts
export interface TransactionFilters {
  accountId: string | null;
  status: 'PENDING' | 'PAID' | 'CANCELLED' | null;
  type: 'INCOME' | 'EXPENSE' | null;
  startDate: string | null;  // 'YYYY-MM-DD'
  endDate: string | null;    // 'YYYY-MM-DD'
  groupByPeriod: boolean;
}

export const DEFAULT_FILTERS: TransactionFilters = {
  accountId: null,
  status: null,
  type: null,
  startDate: null,
  endDate: null,
  groupByPeriod: false,
};
```

### Componente de filtros (`TransactionFiltersComponent`)

Novo componente standalone em `features/transaction/transaction-list/transaction-filters/`.

**Responsabilidades:**
- Recebe lista de contas via `input<AccountResponseDTO[]>()`
- Emite `filterChange = output<TransactionFilters>()` a cada mudança
- Gerencia seletor de mês + date range integrados via `effect()`

**Seletor de período:**
- Botões `◀` / `▶` navegam mês a mês; label mostra "Junho 2026" ou "Personalizado"
- Ao clicar no mês: `startDate = primeiro dia do mês`, `endDate = último dia do mês`
- Ao editar `startDate`/`endDate` manualmente: label muda para "Personalizado"
- `effect()` detecta mudanças nos campos de data e atualiza o label

**Toggle de agrupamento:** `mat-slide-toggle` "Agrupar por período" embutido no mesmo painel.

### Chips de filtros ativos

`computed` sobre `filters()` no `TransactionList` → array de chips descritivos. Cada chip tem ✕ para limpar aquele filtro individualmente. Exibidos abaixo do painel quando painel está colapsado.

### Integração no `TransactionList`

```ts
filters = signal<TransactionFilters>(DEFAULT_FILTERS);

// untracked() evita rastrear qualquer signal lido dentro de loadTransactions,
// garantindo que o effect só reaja a mudanças em filters().
filterEffect = effect(() => {
  const f = this.filters();
  untracked(() => this.loadTransactions(f));
});
```

`loadTransactions()` monta os query params a partir do signal e chama `service.listTransactions({...params})`.

### Visualização agrupada

Quando `filters().groupByPeriod === true`, o template renderiza `mat-expansion-panel` em vez da tabela. A lógica de agrupamento fica em `transaction-list.utils.ts`:

```ts
// Agrupa por mês efetivo (mesma regra do effectiveSortDate)
export function groupByEffectiveMonth(
  transactions: TransactionResponseDTO[]
): Map<string, TransactionResponseDTO[]>
```

**Chave do grupo:** `'YYYY-MM'` derivado de:
- `installmentGroupId && invoiceDueDate` → mês do `invoiceDueDate`
- demais → mês do `date`

**Header de cada card:** mês/ano · `▲ receitas · ▼ despesas · = saldo líquido`

**Estado inicial:** mês corrente (`YYYY-MM` igual ao mês atual) expandido; demais colapsados. Implementado via `[expanded]="isCurrentMonth(key)"` no `mat-expansion-panel`.

**Transações dentro do card:** reutilizam as mesmas colunas e lógica da tabela existente.

### Carregamento de contas

`TransactionList` injeta `AccountsService` e carrega contas em `ngOnInit()` via `forkJoin` com `loadTransactions()`. Lista alimenta o `mat-select` de contas no painel de filtros.

---

## Testes

### Backend

- `TransactionControllerTest`: verificar 200 com cada filtro aplicado individualmente e em combinação
- Verificar que filtros de outro tenant não retornam dados (tenant isolation)
- Verificar comportamento sem filtros (todos os params `null`) retorna tudo

### Frontend (Vitest)

Funções puras em `transaction-list.utils.ts` — testáveis sem TestBed:
- `groupByEffectiveMonth()`: parcelas de cartão agrupam por mês do `invoiceDueDate`; demais por `date`
- Lógica de derivação de label do período ("Personalizado" vs nome do mês)
- Cálculo de totais por grupo (receitas, despesas, saldo)

---

## Fluxo completo

```
Usuário altera filtro
  → filters signal atualizado
  → effect() dispara loadTransactions()
  → GET /api/transactions?accountId=...&status=...&startDate=...&endDate=...
  → Backend: findAllByTenantWithFilters() executa JPQL com cláusulas opcionais
  → Frontend: transactions signal atualizado
  → Se groupByPeriod: computed groupedData() recalcula grupos
  → Template re-renderiza (Zoneless — sem Zone.js, reatividade por Signals)
```

---

## Arquivos afetados

| Arquivo | Operação |
|---|---|
| `api-spec/openapi.yaml` | Adicionar 5 query params |
| `TransactionRepository.java` | Novo método `findAllByTenantWithFilters` |
| `TransactionService.java` | Atualizar `findAll()` com novos params |
| `TransactionController.java` | Novos `@RequestParam`, implementar interface regenerada |
| `transaction-list.utils.ts` | Adicionar `groupByEffectiveMonth()` e helpers de cálculo |
| `transaction-list/transaction-filters/` | Novo componente standalone (4 arquivos) |
| `transaction-list.ts` | Integrar filtros, chips, agrupamento |
| `transaction-list.html` | Painel de filtros + chips + view agrupada |
