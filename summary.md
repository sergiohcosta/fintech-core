# Referência de Contratos de API & Regras de Negócio

> Fonte de verdade para domínio, endpoints e regras implementadas. Spec-Driven Development.
> Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## Segurança

```
Público:   POST /auth/{login,register,accept-invite} · GET /invites/{token} · /openapi.yaml · /swagger-ui/** · /actuator/health
ADMIN:     POST /invites · GET /api/members
Demais:    authenticated (JWT obrigatório)
```
**Invariante:** toda query de negócio filtra pelo `tenant` autenticado. Senha só via BCrypt, nunca em DTO. JWT assina `sub = email`. `SecurityFilter` valida em toda requisição.

## Auth & Convites

| Método | Rota | Auth | Descrição |
|--------|------|------|-----------|
| POST | `/auth/register` | público | Cria Tenant + User(ADMIN) + JWT |
| POST | `/auth/login` | público | Valida credenciais + JWT |
| POST | `/auth/accept-invite` | público | Valida token + cria User(MEMBER) + JWT |
| POST | `/invites` | ADMIN | Cria convite (email + token + expiresAt) |
| GET | `/invites/{token}` | público | Retorna { email, tenantName } |

## Contas (`/api/accounts`)

GET (lista ativas) · POST · GET/{id} (inclui `balance`) · PUT/{id} (PATCH semântico, inclui `creditCardDetails`) · DELETE/{id} (arquiva, `active=false`)

**Liquidez vs Patrimônio** — dois flags distinguem "disponível agora" de "patrimônio total":

| Campo | Pergunta | Default CHECKING/CASH | Default INVESTMENT/CREDIT_CARD |
|-------|----------|----------------------|-------------------------------|
| `countInLiquidBalance` | disponível imediato? | `true` | `false` |
| `countInNetWorth` | integra patrimônio? | `true` | `true` |

- `countInLiquidBalance` → `sumNetLiquidBalanceByTenant()` → `totalAccountBalance` do Dashboard.
- `countInNetWorth` → armazenado, ainda não consumido (futura tela de Patrimônio).
- Frontend auto-ajusta `countInLiquidBalance` ao trocar tipo (override permitido).
- `balance`: `SUM(CASE WHEN type=INCOME THEN amount ELSE -amount END) WHERE account=:account AND status=PAID`.

## Categorias (`/api/categories`)

GET `?includeArchived=` (árvore) · POST (com `parentId?`) · GET/{id} · PUT/{id} (propaga icon/color a descendentes) · DELETE/{id} · POST/{id}/archive `?targetCategoryId=`

- Soft delete `deleted_at` em cascata na subárvore. `DELETE` → 409 `{ transactionCount }` se subárvore tem transações.
- `archive` com `targetCategoryId`: reassocia transações antes do soft delete.
- Herança: filho sem icon/color herda do pai. Validação anti-circular (descendente não pode virar pai).
- `TransactionResponseDTO`: `categoryArchived` (nome taxado) · `categoryPath` (ex: `"Pets → Ração"`, montado em `@Transactional`) · `categoryIcon`.

## Transações (`/api/transactions`)

GET (filtros) · GET/{id} · POST (1..N, parcelamento gera N) · PUT/{id} (com `propagate`) · DELETE/{id} `?scope=`

**Filtros (opcionais, combináveis):** `invoiceId` · `accountIds` (plural — singular `accountId` é ignorado) · `status` · `type` · `startDate`+`endDate` (juntos ou 400).

**Regra de data (filtro/sort) — `effectiveSortDate`:**
- Parcela de cartão (`installmentGroup != null AND invoice != null`) → `invoice.dueDate`
- Demais (incl. avulsa de cartão) → `transaction.date`
- Sort descendente, computado em memória. Frontend exibe a mesma regra na coluna "Data".

**Criação parcelada** (conta CREDIT_CARD, `totalInstallments=N`):
```
para i=0..N-1:
  invoiceMonth = resolveInvoiceMonth(date, closingDay).plusMonths(i)
  Transaction { date=dataCompra, installmentNumber=i+1, amount=total/N, invoice=faturaDoMês(i) }
```
`resolveInvoiceMonth`: `day <= closingDay` → mês corrente; senão → mês seguinte.

**DELETE `?scope=`:** SINGLE · THIS_AND_NEXT (próximas PENDING) · ALL (todas PENDING do grupo). Protege PAID. Retorna `{ deleted, skippedPaid }`.

**PUT `propagate: string[]`:** aplica campos às parcelas futuras `PENDING` (`installmentNumber >` atual). PAID nunca revertido.

## Transferências (`/api/transfers`)

POST (cria par EXPENSE origem + INCOME destino) · DELETE/{transferId} (remove ambos).
**Double-entry:** 2 Transactions com mesmo `transferId`. Não há entidade Transfer.

## Faturas (`/api/invoices`)

GET `?accountId=` · GET/{id} · POST/{id}/close · POST/{id}/pay `{ sourceAccountId }`

**Ciclo:** `OPEN → [close] CLOSED → [pay] PAID`
- **close:** só muda status. Novas transações ainda aceitas (cobranças atrasadas).
- **pay** (`@Transactional` única): PENDING→PAID via `@Modifying` batch; se total>0 cria EXPENSE na origem (`date=now()`, `description="Pagamento fatura {acc} {MM}/{yyyy}"`); fatura→PAID. Fecha o ciclo de caixa do cartão (`countInLiquidBalance=false`).
- **Validações pay:** origem do tenant (404), origem ≠ CREDIT_CARD (422), fatura CLOSED (422).
- **Lazy create** (`getOrCreate`): automático na 1ª transação do período. `UNIQUE(account, year, month)`. Race condition resolvida com `@Transactional(REQUIRES_NEW)` + retry (ADR-001 #83).
- **dueDate:** `dueDay >= closingDay` → mesmo mês; senão → mês seguinte.

## Grupos de Parcelamento (`/api/installment-groups`)

GET · GET/{id} · DELETE/{id} (remove PENDING do grupo) · PATCH/{id} (metadados).

## Dashboard (`/api/dashboard/summary?period=YYYY-MM`)

Resposta: `{ period, income, expense, balance, transactionCount, totalAccountBalance }` (todos excluindo CANCELLED).

**Regra de período — LEFT JOIN obrigatório:**
```sql
LEFT JOIN t.invoice inv
WHERE (inv IS NOT NULL AND inv.dueDate BETWEEN :start AND :end)
   OR (inv IS NULL    AND t.date       BETWEEN :start AND :end)
AND t.status <> CANCELLED
```
`t.invoice.dueDate` direto no WHERE gera INNER JOIN implícito no Hibernate e exclui transações sem fatura.

`totalAccountBalance`: `SUM(±amount) WHERE status=PAID AND account.countInLiquidBalance=true` (sem filtro de período).

## Planejamento Mensal (`/api/{budget-cycles,budget-items,recurring-budget-items}` + `PATCH /api/tenant/settings`)

- `BudgetCycle`: datas calculadas por `startDay` (1 → mês calendário; N → dia N do mês anterior até N-1 do atual). Sincroniza parcelas de cartão ao abrir.
- `BudgetItem`: criação, update, link/unlink a transações (guard anti-duplicação).
- `RecurringBudgetItem`: templates; `deactivate` = soft-delete (`active=false`).

## Logging Estruturado (MDC)

| Chave | Quando | Valor |
|-------|--------|-------|
| `requestId` | toda req | UUID (`RequestIdFilter`) + header `X-Request-ID` |
| `userId` / `tenantId` | pós-JWT | UUID do usuário/tenant (`SecurityFilter`) |

Dev: console legível. Prod: JSON `logstash` (`application-prod.properties`).

| Camada | Log |
|--------|-----|
| `SecurityFilter` | WARN em token inválido |
| `GlobalExceptionHandler` | ERROR + stack só em 5xx |
| `Service` | INFO em transições de estado de negócio (ex: fatura fechada/paga) |
| `Controller` / `RequestIdFilter` | nenhum |

Nunca logar dados sensíveis (senha, JWT, CPF). `tenantId`/`userId` já estão no MDC.

## Frontend — Padrões

- **Estado:** `signal/computed/effect`. Bridge com FormControl: `toSignal(control.valueChanges, { initialValue })` — `computed()` não reage a `FormControl.value` direto.
- **Reatividade segura:** `untracked()` ao chamar loaders dentro de handlers que leem signals (evita loop).
- **Tabelas agrupadas:** `mat-table` única com múltiplos `*matRowDef` + `when` predicates (`period-header`/`invoice-header`); primeiro `true` vence; `[attr.colspan]` para linha full-width.
- **Testes:** lógica pura em arquivos sem imports Angular (ex: `transaction-list.utils.ts`, `amount-math.ts`, `installment-preview.ts`) — testável no Vitest sem `TestBed`.

## Armadilhas Conhecidas (Codegen)

- `auth/auth.service.ts` é regenerado pelo Orval — deletar manualmente antes de usar.
- Sem `required:` em schemas de resposta → Orval gera campos opcionais → `!` assertions.
- `springdoc` deve ser `2.8.9` (incompatível com 2.6.0 no Spring Boot 4.0.1).
