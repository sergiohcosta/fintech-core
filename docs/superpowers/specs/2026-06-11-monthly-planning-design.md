# Planejamento Mensal (Budget Cycles)

**Data:** 2026-06-11  
**Status:** Aprovado  
**Issues relacionadas:** a criar

---

## Visão Geral

Feature de planejamento mensal que permite ao tenant projetar receitas, despesas fixas e parcelas de cartão antes do ciclo financeiro começar, acompanhar a execução ao longo do mês e comparar previsto vs. realizado.

O sistema supera a planilha atual em dois pontos concretos:
1. Parcelas de cartão são populadas automaticamente a partir dos `InstallmentGroup` existentes
2. Templates recorrentes eliminam a redigitação mensal de itens fixos

---

## Decisões de Design

| Decisão | Escolha | Alternativas consideradas |
|---|---|---|
| Ciclo financeiro | Configurável por tenant (`budgetCycleStartDay`, 1–28) | Calendário fixo (1º ao 31) |
| Templates | Entidade própria `recurring_budget_items` | Copiar ciclo anterior; sem templates |
| Relação plano/transação | Híbrido: `BudgetItem` vincula opcionalmente a `Transaction` | Plano gera transações; previsão paralela independente |
| Parcelas no plano | Auto-populadas na abertura do ciclo (snapshot) | Sugestão confirmável; adição manual |
| Dia máximo do ciclo | 28 | 31 (ambíguo em fevereiro) |

---

## Modelo de Dados

### Migration V12

Quatro alterações no schema:

#### 1. Coluna `budget_cycle_start_day` em `tenants`

```sql
ALTER TABLE tenants ADD COLUMN budget_cycle_start_day INT NOT NULL DEFAULT 1;
```

Range válido: 1–28. Valor 1 = ciclo calendário (1º ao último dia do mês).

#### 2. Tabela `budget_cycles`

```sql
CREATE TABLE budget_cycles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    opening_balance NUMERIC(19,2) NOT NULL DEFAULT 0,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_cycle_status CHECK (status IN ('OPEN', 'CLOSED'))
);

CREATE UNIQUE INDEX uq_tenant_one_open_cycle ON budget_cycles(tenant_id) WHERE status = 'OPEN';
```

> O índice parcial `WHERE status = 'OPEN'` garante no banco que cada tenant tem no máximo um ciclo aberto, sem restringir a quantidade de ciclos fechados. A regra de negócio também é reforçada no `BudgetCycleService` com verificação explícita para mensagem de erro amigável.

#### 3. Tabela `recurring_budget_items`

```sql
CREATE TABLE recurring_budget_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    description VARCHAR(255) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    type VARCHAR(10) NOT NULL,
    category_id UUID REFERENCES categories(id),
    account_id UUID REFERENCES accounts(id),
    day_of_month INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_recurring_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT chk_recurring_day CHECK (day_of_month BETWEEN 1 AND 28)
);
```

#### 4. Tabela `budget_items`

```sql
CREATE TABLE budget_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id UUID NOT NULL REFERENCES budget_cycles(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    description VARCHAR(255) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    type VARCHAR(10) NOT NULL,
    category_id UUID REFERENCES categories(id),
    account_id UUID REFERENCES accounts(id),
    expected_date DATE NOT NULL,
    source VARCHAR(15) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    recurring_item_id UUID REFERENCES recurring_budget_items(id),
    transaction_id UUID REFERENCES transactions(id),
    installment_group_id UUID REFERENCES installment_groups(id),
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_item_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT chk_item_source CHECK (source IN ('MANUAL', 'RECURRING', 'INSTALLMENT')),
    CONSTRAINT chk_item_status CHECK (status IN ('PENDING', 'REALIZED', 'SKIPPED'))
);
```

### Entidades Java

**`BudgetCycle`** — campos: `id`, `tenant`, `startDate`, `endDate`, `openingBalance`, `status` (enum `BudgetCycleStatus`), `createdBy`, `createdAt`, `updatedAt`.

**`RecurringBudgetItem`** — campos: `id`, `tenant`, `description`, `amount`, `type` (enum `TransactionType`), `category`, `account`, `dayOfMonth`, `active`, `createdBy`, `createdAt`, `updatedAt`.

**`BudgetItem`** — campos: `id`, `cycle`, `tenant`, `description`, `amount`, `type`, `category`, `account`, `expectedDate`, `source` (enum `BudgetItemSource`), `status` (enum `BudgetItemStatus`), `recurringItem`, `transaction`, `installmentGroup`, `createdBy`, `createdAt`, `updatedAt`.

Novos enums:
- `BudgetCycleStatus`: `OPEN`, `CLOSED`
- `BudgetItemSource`: `MANUAL`, `RECURRING`, `INSTALLMENT`
- `BudgetItemStatus`: `PENDING`, `REALIZED`, `SKIPPED`

---

## APIs

### Configuração do Tenant

| Método | Endpoint | Descrição |
|---|---|---|
| `PATCH` | `/api/tenant/settings` | Atualiza `budgetCycleStartDay` (1–28) |

### Ciclos — `/api/budget-cycles`

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/api/budget-cycles` | Lista histórico (paginado, por tenant) |
| `GET` | `/api/budget-cycles/current` | Ciclo `OPEN` atual (404 se nenhum) |
| `GET` | `/api/budget-cycles/{id}` | Ciclo com itens + summary |
| `POST` | `/api/budget-cycles` | Abre novo ciclo |
| `POST` | `/api/budget-cycles/{id}/close` | Fecha ciclo |
| `POST` | `/api/budget-cycles/{id}/sync-installments` | Re-sincroniza parcelas do período |

**Request — `POST /api/budget-cycles`:**
```json
{ "referenceMonth": "2026-06" }
```

O backend calcula `startDate` e `endDate` a partir de `budgetCycleStartDay` do tenant:
- `startDay = 11`, `referenceMonth = 2026-06` → `startDate = 2026-05-11`, `endDate = 2026-06-10`
- `startDay = 1`, `referenceMonth = 2026-06` → `startDate = 2026-06-01`, `endDate = 2026-06-30`

`openingBalance` é calculado automaticamente: soma dos saldos reais das contas do tenant com `countInLiquidBalance = true`.

**Response — `GET /api/budget-cycles/{id}`:**
```json
{
  "id": "...",
  "startDate": "2026-05-11",
  "endDate": "2026-06-10",
  "openingBalance": 3200.00,
  "status": "OPEN",
  "createdBy": { "id": "...", "name": "Carlos" },
  "summary": {
    "plannedIncome": 8000.00,
    "plannedExpense": 5400.00,
    "projectedBalance": 5800.00,
    "realizedIncome": 8000.00,
    "realizedExpense": 2100.00,
    "currentBalance": 9100.00,
    "pendingCount": 7
  },
  "items": [...]
}
```

### Itens — `/api/budget-cycles/{cycleId}/items` e `/api/budget-items`

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/api/budget-cycles/{cycleId}/items` | Lista itens (filtrável por `type`, `status`, `source`) |
| `POST` | `/api/budget-cycles/{cycleId}/items` | Adiciona item manual |
| `PUT` | `/api/budget-items/{id}` | Edita item (bloqueado se `source = INSTALLMENT`) |
| `DELETE` | `/api/budget-items/{id}` | Remove item |
| `POST` | `/api/budget-items/{id}/link` | Vincula transação real `{ "transactionId": "..." }` |
| `DELETE` | `/api/budget-items/{id}/link` | Desvincula → status volta para `PENDING` |

### Templates — `/api/recurring-budget-items`

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/api/recurring-budget-items` | Lista templates ativos do tenant |
| `POST` | `/api/recurring-budget-items` | Cria template |
| `PUT` | `/api/recurring-budget-items/{id}` | Atualiza template |
| `DELETE` | `/api/recurring-budget-items/{id}` | Desativa (`active = false`) |

---

## Lógica de Abertura de Ciclo

Executada em `@Transactional` única no `BudgetCycleService.open()`:

1. Validar que não existe ciclo `OPEN` para o tenant
2. Validar que o período não sobrepõe ciclo existente
3. Calcular `startDate` / `endDate` a partir de `referenceMonth` + `budgetCycleStartDay`
4. Calcular `openingBalance`: `AccountRepository.sumLiquidBalanceByTenant(tenantId)`
5. Criar e persistir o `BudgetCycle`
6. Buscar `RecurringBudgetItem` ativos do tenant → criar `BudgetItem` com `source = RECURRING`, `expectedDate` calculado assim: se `dayOfMonth >= budgetCycleStartDay`, a data cai no primeiro mês calendário do ciclo (`startDate.withDayOfMonth(dayOfMonth)`); caso contrário, cai no segundo mês (`startDate.plusMonths(1).withDayOfMonth(dayOfMonth)`). Exemplo: `startDay = 11`, ciclo mai–jun, `dayOfMonth = 5` → `expectedDate = 2026-06-05`; `dayOfMonth = 15` → `expectedDate = 2026-05-15`.
7. Buscar parcelas do período: `Transaction` com `installmentGroup != null` e `invoice.dueDate` entre `startDate` e `endDate` → criar `BudgetItem` com `source = INSTALLMENT` agrupado por `installmentGroup` — um item por grupo, `amount` = soma das parcelas do grupo no período, `expectedDate` = `invoice.dueDate` da fatura correspondente.
8. Retornar ciclo populado

**Snapshot imutável:** parcelas geradas na abertura não se atualizam automaticamente. Novas parcelas criadas após a abertura do ciclo precisam de `POST /api/budget-cycles/{id}/sync-installments` para aparecer.

---

## Regras de Negócio

| Regra | Onde validar |
|---|---|
| Máximo um ciclo `OPEN` por tenant | `BudgetCycleService` + índice no banco |
| Períodos não podem se sobrepor | `BudgetCycleService` antes de inserir |
| Itens `INSTALLMENT` são read-only (sem PUT) | `BudgetItemService.update()` → 422 |
| Vincular transação já vinculada a outro item | `BudgetItemService.link()` → 422 |
| `budgetCycleStartDay` entre 1 e 28 | Bean Validation + constraint SQL |
| Ao vincular: `status → REALIZED` | `BudgetItemService.link()` |
| Ao desvincular: `status → PENDING`, `transactionId → null` | `BudgetItemService.unlink()` |

---

## Frontend

### Estrutura

```
features/planning/
├── budget-cycle-current/       ← tela principal (ciclo aberto)
├── budget-cycle-detail/        ← ciclo histórico (read-mostly)
├── budget-cycle-list/          ← histórico de ciclos fechados
├── budget-item-form/           ← dialog add/edit item manual
├── link-transaction-dialog/    ← dialog para vincular transação real
└── recurring-item-list/        ← gestão de templates
    └── recurring-item-form/    ← dialog add/edit template
```

### Rotas

```
/planning               → redireciona para /planning/current
/planning/current       → BudgetCycleCurrentComponent
/planning/cycles        → BudgetCycleListComponent
/planning/cycles/:id    → BudgetCycleDetailComponent
/planning/recurring     → RecurringItemListComponent
```

### `BudgetCycleCurrentComponent`

**Bloco 1 — Cabeçalho:** período, status chip, saldo de abertura, botão "Fechar ciclo".

**Bloco 2 — Cards de resumo** (via `computed()` a partir dos itens em memória):
- Receitas: previsto / realizado
- Despesas: previsto / realizado
- Saldo: projetado / atual

**Bloco 3 — Lista de itens** em três seções expansíveis:
- Receitas (INCOME)
- Despesas fixas (EXPENSE com source MANUAL ou RECURRING)
- Parcelas do cartão (EXPENSE com source INSTALLMENT — read-only)

Estado com Signals:
```typescript
items = signal<BudgetItem[]>([]);
incomeItems = computed(() => items().filter(i => i.type === 'INCOME'));
expenseItems = computed(() => items().filter(i => i.type === 'EXPENSE' && i.source !== 'INSTALLMENT'));
installmentItems = computed(() => items().filter(i => i.source === 'INSTALLMENT'));
summary = computed(() => buildSummary(items(), cycle().openingBalance));
```

**Empty state (sem ciclo aberto):** card com botão "Abrir ciclo de [mês atual]" → dialog com `referenceMonth` pré-preenchido.

### `LinkTransactionDialogComponent`

Busca transações do período com mesmo `type` do item. Ao selecionar, chama `POST /api/budget-items/{id}/link` e atualiza o item no signal local via `items.update(...)`.

### `RecurringItemListComponent`

`mat-table` com colunas: descrição, valor, tipo, dia do mês, conta, categoria. FAB para adicionar. Ações por linha: editar / desativar.

---

## Tratamento de Erros

| Situação | HTTP | Mensagem |
|---|---|---|
| Ciclo `OPEN` já existe | 409 | "Já existe um ciclo aberto para este tenant" |
| Período sobrepõe ciclo existente | 409 | "O período solicitado conflita com um ciclo já existente" |
| Editar item `INSTALLMENT` | 422 | "Itens de parcela não podem ser editados manualmente" |
| Fechar ciclo já `CLOSED` | 422 | "O ciclo já está fechado" |
| Transação já vinculada a outro item | 422 | "Esta transação já está vinculada a outro item do plano" |
| `budgetCycleStartDay` fora de 1–28 | 400 | "Dia de início deve estar entre 1 e 28" |

---

## Testes

### Backend

**`BudgetCycleServiceTest` (unitário):**
- Cálculo de `startDate`/`endDate` para `startDay = 1`, `startDay = 11`, `startDay = 28`, virada de ano
- Rejeição ao abrir segundo ciclo `OPEN`
- Rejeição de períodos sobrepostos
- `openingBalance` calculado corretamente a partir de contas `countInLiquidBalance`

**`BudgetItemServiceTest` (unitário):**
- `update()` lança 422 para item `INSTALLMENT`
- `link()` muda status para `REALIZED` e preenche `transactionId`
- `link()` lança 422 se transação já vinculada a outro item
- `unlink()` volta status para `PENDING` e limpa `transactionId`

**Integração — Testcontainers:**
- Abertura popula recorrentes ativos + parcelas do período
- Parcelas fora do período não são incluídas
- Tenant isolation: itens de tenant A não aparecem para tenant B
- Fechar ciclo não afeta transações reais

### Frontend

**Funções puras (sem TestBed):**
- `buildSummary(items, openingBalance)` — todos os cenários de summary
- Cálculo de datas do ciclo (utilitário de serviço)

**`BudgetCycleCurrentComponent`:**
- Empty state quando não há ciclo aberto
- Três grupos de itens renderizados corretamente
- Summary reativo ao vincular/desvincular item

---

## Impacto em Código Existente

| Arquivo | Mudança |
|---|---|
| `Tenant.java` | + campo `budgetCycleStartDay` (int, default 1) |
| `TenantResponseDTO` / spec OpenAPI | + campo `budgetCycleStartDay` |
| `AccountRepository` | + query `sumLiquidBalanceByTenant()` |
| `shell.component.html` | + item "Planejamento" no sidenav |
| `app.routes.ts` | + lazy route `/planning` |

Nenhuma entidade existente tem sua estrutura alterada além de `Tenant`.

---

## Fora de Escopo (Esta Versão)

- Notificações de itens `PENDING` próximos à data prevista
- Planejamento por categoria (orçamento de categoria vs. realizado)
- Importação de plano via CSV
- Compartilhamento de plano entre membros do tenant
