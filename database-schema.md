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
| V22 | `paid_invoice_id UUID` nullable (FK → `invoices`) em `transactions` + índice único parcial `(paid_invoice_id) WHERE paid_invoice_id IS NOT NULL` — marcador do pagamento de fatura (#145); dashboard exclui dos agregados e o índice garante 1 pagamento por fatura (reforça #139) |
| V23 | `import_batches` + `staged_transactions` (fundação da extração/conciliação, Fase 0) + `posting_date DATE` nullable em `transactions`. Staging SEPARADO do núcleo: o dado extraído (probabilístico, com confidence) só é promovido a `transactions` no commit. `staged_transactions.fields` é JSONB; `staged_transactions.tenant_id` é **denormalizado** (defesa nº1 contra vazamento). `posting_date` ainda não consumido (Fase 5) |
| V24 | seed `dev` — importação Família Costa: 1 `import_batches` COMMITTED + 2 `staged_transactions` CONFIRMED com `promoted_transaction_id` → transações Salário/Aluguel jun/2026 do V13 (resolvidas por chave natural, pois o V13 usa `gen_random_uuid()`) |
| V25 | `failure_reason VARCHAR(500)` nullable em `import_batches` — motivo legível da recusa/falha de extração (#193). Sem ela o batch `FAILED` era opaco e o frontend só sabia oferecer o formulário manual; com o guarda-corpo de imagem multi-transação há causas distintas que pedem ações distintas do usuário |

> V10 não existe (seed renomeado para V13 para ficar acima do schema base).

## Constraints Relevantes

- `invoices`: `UNIQUE(account_id, reference_year, reference_month)` — idempotência do `getOrCreate`.
- `budget_cycles`: `UNIQUE(tenant_id) WHERE status='OPEN'` — no máximo um ciclo aberto por tenant.
- `budget_cycles`: `status IN (OPEN, ENDED, CLOSED)` — `ENDED` = período encerrado, ajustes ainda permitidos.
- `budget_items`: `source IN (MANUAL, RECURRING, INSTALLMENT)`, `status IN (PENDING, REALIZED, SKIPPED)`; `recurrence_rule_id` FK nullable → `recurrence_rules`.
- `recurrence_rules`: `type IN (INCOME, EXPENSE)`, `status IN (ACTIVE, CANCELLED)`; índice `(tenant_id, status)`.
- `recurrence_exceptions`: `UNIQUE(rule_id, occurrence_date)` — idempotência do "Pular" (EXDATE).
- `transactions`: índice único parcial `(recurrence_rule_id, recurrence_occurrence) WHERE recurrence_rule_id IS NOT NULL` — impede confirmar a mesma ocorrência duas vezes.
- `transactions`: índice único parcial `(paid_invoice_id) WHERE paid_invoice_id IS NOT NULL` — no máximo um pagamento por fatura (#145/#139).
- `import_batches`: `import_mode IN (NEW_TRANSACTIONS, RECONCILIATION)`, `source_type IN (IMAGE, PDF_TEXT, PDF_SCANNED, CSV, OFX, AUDIO)`, `status IN (PENDING, EXTRACTED, REVIEWED, COMMITTED, FAILED)`; índice `(tenant_id, status)`.
- `staged_transactions`: `status IN (PENDING, CONFIRMED, DISCARDED)`; FK `batch_id → import_batches ON DELETE CASCADE`; `tenant_id` denormalizado (FK → tenants); `promoted_transaction_id` FK nullable → `transactions`; índice `(batch_id)`.

**Invariante:** migrations aplicadas são imutáveis. Correção sempre via nova versão.
