# Migrations Flyway

Schema em `db/migration/`. Seed em `db/seed/` (perfil `dev`): `V13` (dados gerais) e `V16` (planejamento) — cada seed roda após as migrations das tabelas que popula.

| Versão | Schema |
|--------|--------|
| V1 | `tenants`, `users` |
| V2 | `credit_card_details` |
| V3 | `categories`, `transactions` |
| V4 | coluna `status` + campos de parcelamento legado em `transactions` |
| V5 | `accounts` (`count_in_liquid_balance`, `count_in_net_worth`) |
| V6 | `invitations` |
| V7 | `deleted_at TIMESTAMP` em `categories` (soft delete) |
| V8 | `installment_groups` + FK nullable `installment_group_id` em `transactions` |
| V9 | `invoices` + FK nullable `invoice_id` em `transactions` |
| V11 | `taxonomy_code VARCHAR(50)` nullable em `categories` (código semântico cross-tenant; NULL = criada pelo usuário) |
| V12 | `budget_cycle_start_day` em `tenants` + `budget_cycles`, `recurring_budget_items`, `budget_items` |
| V13 | seed `dev` — dataset Família Costa (contas, categorias, transações, faturas) |
| V14 | `reference_month` + colunas de snapshot (`snapshot_*`) em `budget_cycles` |
| V15 | status `ENDED` em `budget_cycles` (constraint `OPEN, ENDED, CLOSED`) |
| V16 | seed `dev` — planejamento mensal (budget cycles/items) Família Costa |
| V17 | correção de `reference_year`/`reference_month` de faturas legadas (bug `resolveInvoiceMonth`) |
| V18 | seed `dev` — corrige `opening_balance` do ciclo seed jun/2026 (1200 → 18123.10) para refletir o cálculo date-bounded |
| V19 | `recurrence_rules`, `recurrence_exceptions` (EXDATE) + colunas `recurrence_rule_id`/`recurrence_occurrence` em `transactions` (motor de recorrência — núcleo) |
| V20 | seed `dev` — recorrência Família Costa (regra Netflix mensal + 1 EXDATE de exemplo) |
| V21 | migra `recurring_budget_items` → `recurrence_rules` (big bang): INSERT SELECT + colunas `recurrence_rule_id`/`recurrence_occurrence_date` em `budget_items` + DROP TABLE `recurring_budget_items` |

> V10 não existe (seed renomeado para V13 para ficar acima do schema base).

## Constraints Relevantes

- `invoices`: `UNIQUE(account_id, reference_year, reference_month)` — idempotência do `getOrCreate`.
- `budget_cycles`: `UNIQUE(tenant_id) WHERE status='OPEN'` — no máximo um ciclo aberto por tenant.
- `budget_cycles`: `status IN (OPEN, ENDED, CLOSED)` — `ENDED` = período encerrado, ajustes ainda permitidos.
- `budget_items`: `source IN (MANUAL, RECURRING, INSTALLMENT)`, `status IN (PENDING, REALIZED, SKIPPED)`; `recurrence_rule_id` FK nullable → `recurrence_rules`.
- `recurrence_rules`: `type IN (INCOME, EXPENSE)`, `status IN (ACTIVE, CANCELLED)`; índice `(tenant_id, status)`.
- `recurrence_exceptions`: `UNIQUE(rule_id, occurrence_date)` — idempotência do "Pular" (EXDATE).
- `transactions`: índice único parcial `(recurrence_rule_id, recurrence_occurrence) WHERE recurrence_rule_id IS NOT NULL` — impede confirmar a mesma ocorrência duas vezes.

**Invariante:** migrations aplicadas são imutáveis. Correção sempre via nova versão.
