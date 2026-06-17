# Migrations Flyway

Schema em `db/migration/`. Seed em `db/seed/` (perfil `dev`, versão sempre maior que todo schema).

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
| V13 | seed `dev` — dataset Família Costa |

> V10 não existe (seed renomeado para V13 para ficar acima do schema).

## Constraints Relevantes

- `invoices`: `UNIQUE(account_id, reference_year, reference_month)` — idempotência do `getOrCreate`.
- `budget_cycles`: `UNIQUE(tenant_id) WHERE status='OPEN'` — no máximo um ciclo aberto por tenant.
- `recurring_budget_items`: `day_of_month BETWEEN 1 AND 28`.
- `budget_items`: `source IN (MANUAL, RECURRING, INSTALLMENT)`, `status IN (PENDING, REALIZED, SKIPPED)`.

**Invariante:** migrations aplicadas são imutáveis. Correção sempre via nova versão.
