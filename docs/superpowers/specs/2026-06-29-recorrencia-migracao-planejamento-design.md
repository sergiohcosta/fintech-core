# Design: Motor de Recorrência — Sub-projeto 2: Migração do Planejamento

> **Status:** aprovado em brainstorming, pronto para plano de implementação.
> **Fonte do produto:** `2026-06-25-motor-de-recorrencia.md` (PRD original) e
> `2026-06-25-motor-de-recorrencia-nucleo-design.md` (sub-projeto 1 — já implementado).
> Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto

O sub-projeto 1 entregou o motor de recorrência completo: `RecurrenceRule` + `RecurrenceProjectionService` + CRUD + fantasmas na lista de transações.

O planejamento mensal (`BudgetCycle`) ainda usa `RecurringBudgetItem` — uma abstração anterior, mais simples (só dia do mês, sem RRULE). Ao abrir um ciclo, `BudgetCycleService.populateRecurringItems()` lê `recurring_budget_items` e cria `BudgetItem` com `source=RECURRING`.

Este sub-projeto substitui essa fonte pela `RecurrenceRule`, eliminando a redundância. Após a entrega, `RecurringBudgetItem` deixa de existir.

## 2. Escopo

**Dentro:**
- Migration SQL big bang: converte `recurring_budget_items` → `recurrence_rules`, migra FKs em `budget_items`, remove tabela antiga.
- `BudgetCycleService.populateRecurringItems()` passa a usar `RecurrenceProjectionService`.
- `BudgetItem` ganha `recurrenceRule` + `recurrenceOccurrenceDate`; perde `recurringItem`.
- Aba "Recorrentes" do planejamento (frontend) rewired para endpoints de `RecurrenceRule` com formulário simplificado (sem editor RRULE exposto).
- Endpoint novo `PATCH /api/recurrence-rules/{id}/reactivate` (mantém UX de reativar).
- Atualização do `openapi.yaml`, `seed_base.sql` e `seed-dataset.http`.

**Fora:**
- Pausa/retomada, edição "desta em diante", capping 31→28, detach pontual → sub-projeto 3.
- Simulador "E se...?" → sub-projeto 4.
- Link automático entre "Realizar" no planejamento e "confirmar" no motor (usa `recurrenceRuleId` + `recurrenceOccurrenceDate` gravados no `BudgetItem`, mas a lógica de materialização é do sub-projeto 3).

## 3. Decisões arquiteturais

| Decisão | Escolha | Alternativa descartada |
|---|---|---|
| Estratégia de migração | Big bang: SQL converte + remove tabela na mesma migration | Convivência controlada (tabela órfã) — deixaria débito técnico |
| Aba "Recorrentes" do planejamento | Mantida, rewired para RecurrenceRule com form simplificado | Remover e redirecionar para feature Recorrências (UX pior para o contexto de orçamento) |
| `BudgetItem` → qual ocorrência | `recurrence_rule_id` + `recurrence_occurrence_date` | Só `recurrence_rule_id` — impossibilitaria link fino com o motor no #3 |
| RRULE no form simplificado | Montado pelo frontend (`FREQ=MONTHLY;BYMONTHDAY={dia}`) | Endpoint simplificado novo no backend — manteria abstração morta |

## 4. Schema / Migrations

### V21 — Migração big bang

```sql
-- 1. Cria RecurrenceRule equivalente para cada RecurringBudgetItem
INSERT INTO recurrence_rules (
    id, tenant_id, description, base_amount, type,
    category_id, account_id, rrule, start_date, status,
    created_by, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    tenant_id,
    description,
    amount,
    type,
    category_id,
    account_id,
    'FREQ=MONTHLY;BYMONTHDAY=' || day_of_month,
    CURRENT_DATE,
    CASE WHEN active THEN 'ACTIVE' ELSE 'CANCELLED' END,
    created_by,
    created_at,
    updated_at
FROM recurring_budget_items;

-- 2. Adiciona colunas novas em budget_items
ALTER TABLE budget_items
    ADD COLUMN recurrence_rule_id UUID REFERENCES recurrence_rules(id),
    ADD COLUMN recurrence_occurrence_date DATE;

-- 3. Mapeia BudgetItems existentes para as novas RecurrenceRules
--    Chave de ligação: recurring_item_id → tenant + description + type + amount
UPDATE budget_items bi
SET recurrence_rule_id = rr.id
FROM recurring_budget_items rbi
JOIN recurrence_rules rr
    ON rr.tenant_id = rbi.tenant_id
   AND rr.description = rbi.description
   AND rr.type = rbi.type
   AND rr.base_amount = rbi.amount
WHERE bi.recurring_item_id = rbi.id;

-- 4. Remove coluna legada e tabela
ALTER TABLE budget_items DROP COLUMN recurring_item_id;
DROP TABLE recurring_budget_items;

-- 5. Índice
CREATE INDEX idx_budget_items_recurrence_rule ON budget_items(recurrence_rule_id);
```

**Nota sobre seeds:** V13 e V16 inserem em `recurring_budget_items`. O Flyway executa V21 depois deles (ordem crescente), então os dados são inseridos pelos seeds e migrados por V21 na sequência — sem conflito.

### V22 — Seed dev (sem dados novos de schema)

Não insere em `recurring_budget_items` (tabela removida por V21). Apenas opcional: inserir `recurrence_occurrence_date` nos `BudgetItem` do seed se necessário para testes manuais. Em geral V21 já resolve.

## 5. Backend

### 5.1 Entidade `BudgetItem` (alterada)

```java
// Remove:
@ManyToOne @JoinColumn(name = "recurring_item_id")
private RecurringBudgetItem recurringItem;

// Adiciona:
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "recurrence_rule_id")
private RecurrenceRule recurrenceRule;

@Column(name = "recurrence_occurrence_date")
private LocalDate recurrenceOccurrenceDate;
```

### 5.2 `BudgetCycleService.populateRecurringItems()` (reescrito)

```java
private void populateRecurringItems(BudgetCycle cycle, Tenant tenant, User user,
                                    LocalDate startDate, LocalDate endDate) {
    List<ProjectedOccurrence> projected =
        recurrenceProjectionService.project(tenant, startDate, endDate);

    List<BudgetItem> items = projected.stream()
        .map(p -> BudgetItem.builder()
            .cycle(cycle)
            .tenant(tenant)
            .description(p.description())
            .amount(p.amount())
            .type(p.type())
            .category(p.category())
            .account(p.account())
            .expectedDate(p.occurrenceDate())
            .source(BudgetItemSource.RECURRING)
            .recurrenceRule(ruleRepository.getReferenceById(p.ruleId()))
            .recurrenceOccurrenceDate(p.occurrenceDate())
            .createdBy(user)
            .build())
        .toList();

    itemRepository.saveAll(items);
}
```

`RecurringBudgetItemRepository` é removido da injeção. `RecurrenceRuleRepository` e `RecurrenceProjectionService` são injetados.

### 5.3 Artefatos removidos

| Artefato | Ação |
|---|---|
| `RecurringBudgetItem.java` | removido |
| `RecurringBudgetItemService.java` | removido |
| `RecurringBudgetItemController.java` | removido |
| `RecurringBudgetItemRepository.java` | removido |
| `RecurringBudgetItemRequest.java` | removido |
| `RecurringBudgetItemResponseDTO.java` | removido |
| `BudgetCycleService` — import + field `recurringRepository` | removido |

Endpoints `/api/recurring-budget-items/**` removidos do `openapi.yaml` e do `SecurityConfigurations`.

### 5.4 Endpoint novo: reativar regra

```
PATCH /api/recurrence-rules/{id}/reactivate
```

- Altera `status = ACTIVE` na regra (desde que `CANCELLED`).
- Escopado por tenant.
- Sem body; retorna `RecurrenceRuleResponseDTO`.
- Adicionado em `RecurrenceRuleService`, `RecurrenceRulesController` e `openapi.yaml`.

### 5.5 `BudgetItemResponse` DTO (campos adicionados)

```java
UUID recurrenceRuleId;             // nullable
LocalDate recurrenceOccurrenceDate; // nullable
```

## 6. API — alterações no openapi.yaml

| Operação | Mudança |
|---|---|
| `DELETE /api/recurring-budget-items/**` | Remove todos os paths |
| `PATCH /api/recurrence-rules/{id}/reactivate` | Adiciona |
| `BudgetItemResponse` schema | Adiciona `recurrenceRuleId` + `recurrenceOccurrenceDate` |

Fluxo pós-edição:
```bash
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
./mvnw generate-sources         # backend
npm run api:generate            # frontend
```

## 7. Frontend

### 7.1 `planning.service.ts` (alterado)

Remove métodos que chamam `/api/recurring-budget-items`. Substitui pelos equivalentes de `RecurrenceRule` (já disponíveis via Orval desde o sub-projeto 1):

| Antes | Depois |
|---|---|
| `listRecurring()` → GET `/recurring-budget-items` | `listRecurring()` → GET `/recurrence-rules` |
| `createRecurring(req)` → POST `/recurring-budget-items` | `createRecurring(req)` → POST `/recurrence-rules` |
| `updateRecurring(id, req)` → PUT `/recurring-budget-items/{id}` | `updateRecurring(id, req)` → PATCH `/recurrence-rules/{id}` |
| `deleteRecurring(id)` → DELETE `/recurring-budget-items/{id}` | `deleteRecurring(id)` → DELETE `/recurrence-rules/{id}` |
| `reactivateRecurring(id)` → PATCH `/recurring-budget-items/{id}/reactivate` | `reactivateRecurring(id)` → PATCH `/recurrence-rules/{id}/reactivate` |

### 7.2 `recurring-item-form` (alterado)

O formulário não expõe a string RRULE. Monta internamente antes de submeter.

**Campos visíveis:**

| Campo | Tipo | Notas |
|---|---|---|
| Descrição | text | obrigatório |
| Tipo | select (Receita/Despesa) | obrigatório |
| Valor | number | obrigatório |
| Dia do mês | number 1–28 | obrigatório; gera `FREQ=MONTHLY;BYMONTHDAY={dia}` |
| Conta | select | obrigatório (novo — `RecurrenceRule` exige `accountId`) |
| Categoria | select | opcional |
| Data de início | date | obrigatório; default: 1º do próximo mês |

**Ao salvar (criar):**
```ts
const rrule = `FREQ=MONTHLY;BYMONTHDAY=${form.dayOfMonth}`;
const payload: RecurrenceRuleCreateDTO = {
  description: form.description,
  baseAmount: form.amount,
  type: form.type,
  accountId: form.accountId,
  categoryId: form.categoryId ?? null,
  rrule,
  startDate: form.startDate,
};
```

**Ao editar:** chama PATCH com `{ description, baseAmount }` (campos editáveis da `RecurrenceRule`). Dia do mês e conta não são editáveis via PATCH (escopo do sub-projeto 1). Se o usuário precisar alterar o dia, cancela e recria — comportamento aceitável para o MVP.

### 7.3 `recurring-item-list` (alterado)

Troca `RecurringBudgetItemResponse` por `RecurrenceRuleResponse`. Adaptações:

- **Coluna "Dia":** extrai do `rrule` via regex `/BYMONTHDAY=(-?\d+)/`; exibe `-1` como "último dia".
- **Coluna "Valor":** lê `baseAmount` (antes era `amount`).
- **Filtro ativo/inativo:** `status === 'ACTIVE'` (antes era `active === true`).
- **Botão Reativar:** chama `reactivateRecurring(id)` → PATCH `/reactivate`.

## 8. Testes

### 8.1 Integração (Testcontainers + `seed_base.sql`)

| Cenário | Verificação |
|---|---|
| Abrir ciclo com regra ACTIVE no período | `BudgetItem` criado com `recurrenceRuleId` + `recurrenceOccurrenceDate` preenchidos |
| Abrir ciclo com regra CANCELLED | Nenhum item RECURRING criado |
| Abrir ciclo com regra com `UNTIL` vencido | Nenhum item RECURRING criado |
| Abrir ciclo sem regras | Nenhum item RECURRING criado (regressão) |
| PATCH `/api/recurrence-rules/{id}/reactivate` | Status volta para ACTIVE |
| Reativar regra já ACTIVE | 422 (transição de estado inválida) |
| `GET /api/recurring-budget-items` | 404 (endpoint removido) |

### 8.2 `seed_base.sql`

Remove `INSERT INTO recurring_budget_items`. Substitui por `INSERT INTO recurrence_rules` mínimo para os testes de integração de planejamento.

## 9. Dataset dev

- V21 migra os dados de `recurring_budget_items` do seed existente (V13/V16) automaticamente.
- `docs/http/seed-dataset.http`: substituir chamadas a `/api/recurring-budget-items` por `/api/recurrence-rules`.
- V22 seed dev: opcional; só necessário se `recurrence_occurrence_date` precisar de dados explícitos nos `BudgetItem` do seed para testes manuais.

## 10. Mapa de reconciliação com o PRD

| PRD / sub-projeto 1 | Este sub-projeto |
|---|---|
| "Coexistência temporária com `RecurringBudgetItem` até o #2" | Eliminada: big bang migration em V21 |
| `BudgetItem.recurringItem` | Substituído por `recurrenceRule` + `recurrenceOccurrenceDate` |
| `populateRecurringItems` lendo templates simples | Reescrito para usar `RecurrenceProjectionService` |
| Aba "Recorrentes" gerenciando `RecurringBudgetItem` | Rewired para `RecurrenceRule` com form simplificado |
| Reativar regra | Novo endpoint `PATCH /reactivate` (trivial, necessário para UX) |
