# Referência de Contratos de API & Regras de Negócio

> Fonte de verdade para domínio, endpoints e regras implementadas. Spec-Driven Development.
> Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## Segurança

```
Público:   POST /auth/{login,register,accept-invite} · GET /invites/{token} · /openapi.yaml · /swagger-ui/** · /actuator/health
ADMIN:     POST /invites · GET /api/members · PATCH /api/tenant/settings
Demais:    authenticated (JWT obrigatório)
```
**Invariante:** toda query de negócio filtra pelo `tenant` autenticado. Senha só via BCrypt, nunca em DTO. JWT assina `sub = email`. `SecurityFilter` valida em toda requisição.

**Login (`/auth/login`):** resposta genérica (401) tanto para email inexistente quanto para senha incorreta ou usuário inativo (sem enumeração de usuários). Rate limit em memória: 5 tentativas falhas por email a cada 1 minuto → `429 Too Many Requests` (`LoginRateLimiter`). `User.isEnabled()` reflete o campo `active` — checado no login e em toda requisição autenticada (`SecurityFilter`).

**Política de senha (registro e aceite de convite):** mínimo 8 e máximo 72 caracteres, com letra maiúscula, minúscula e número (`TenantRegistrationDTO`, `AcceptInviteDTO`).

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

## Linha do Tempo (`/transactions/timeline`) — só frontend

Visualização alternativa das mesmas transações (consome `GET /api/transactions` — **nenhum endpoint novo**). Três views em tabs: **Calendário** (heatmap mensal), **Lista agrupada** (períodos relativos: Hoje/Ontem/Esta semana/Semana passada/Este mês/Mais antigos) e **Linha horizontal** (marcadores por dia-efetivo, colisão agrupada por data).

- **Filtros independentes** da lista principal, persistidos em `localStorage` (`fintech.timeline.filters`); `description` é filtro client-side e **nunca** é persistida.
- Reusa a regra `effectiveSortDate` do backend (parcela de cartão → `invoiceDueDate`; demais → `date`) — replicada em `timeline-shared.ts`.
- **"Ver lista"** navega para `/transactions` passando os filtros via `queryParams` (`accountIds,status,type,startDate,endDate,description`); a lista os aplica em `TransactionList.mergeFiltersFromQueryParams` (queryParams **vencem** o `localStorage`).
- Rota registrada **antes** de `transactions/:id` (senão `:id` capturaria a string `"timeline"`).
- Lógica pura testável sem `TestBed` (`*-utils.ts`); o shell é coberto por spec com `overrideComponent()`. Specs de componente exigem `ng test` (não `npx vitest` cru). Spec/design: `docs/superpowers/specs/2026-06-23-transaction-timeline-design.md`.

## Recorrência (`/api/recurrence-rules`) — Motor de Recorrência (núcleo)

GET (lista ativas) · POST (valida RRULE) · GET/{id} · PATCH/{id} (`description`+`baseAmount`) · DELETE/{id} (cancela: `status=CANCELLED`) · PATCH/{id}/reactivate (reativa: `CANCELLED→ACTIVE`) · POST/{id}/occurrences/{date}/confirm · POST/{id}/occurrences/{date}/skip.

**Regra vs. Transação.** A `RecurrenceRule` é a definição atemporal (string RRULE / RFC 5545, expandida pela lib `org.dmfs:lib-recur`). A `Transaction` é o fato imutável, gravado **só** após confirmação. Nada é materializado antecipadamente.

**Projeção on-the-fly (`RecurrenceProjectionService`):** `fantasma(janela) = expand(rrule) − {ocorrências já materializadas} − {EXDATE}`, keyed pela data da ocorrência. Sempre recebe janela (`[from,to]`) — nunca expande "infinito".

**`GET /api/transactions?includeProjected=true`:** mescla reais + fantasmas no período (default `false`, retrocompatível). Fantasma: `projected=true`, `id=null`, status `PENDING`, `recurrenceRuleId`+`occurrenceDate` preenchidos. Ordenação compartilha a regra `effectiveSortDate`. Filtro por `invoiceId` **não** projeta.

**Confirmar:** materializa a ocorrência reusando o caminho de criação de transação (`materializeFromRule` → se cartão, fatura resolvida por `resolveInvoiceMonth`/`getOrCreate`). Body opcional `{amount?, date?}` (override — ajuste pontual; a regra segue projetando o `baseAmount`). Índice único parcial `(recurrence_rule_id, recurrence_occurrence)` + guard → **409** ao confirmar a mesma ocorrência 2x.

**Pular:** grava EXDATE em `recurrence_exceptions` (idempotente). A fantasma some no mês pulado e volta no seguinte.

**RRULE — subconjunto suportado:** `FREQ=MONTHLY|YEARLY`, `INTERVAL`, `BYMONTHDAY` (1..31 e `-1`=último dia), `UNTIL`, `COUNT`. `@ValidRrule` rejeita o resto (`BYDAY`/`BYSETPOS`/`BYWEEKNO`/`BYYEARDAY`/`BYHOUR`/`BYMINUTE`, e `FREQ` diário/semanal) → **400**. "Fim do mês" = `BYMONTHDAY=-1` (resolve 28/29 fev, 30 abr nativamente). Validação varre as chaves do rrule (o parser lax do lib-recur descarta partes inválidas no contexto).

**Fora do núcleo (sub-projetos futuros):** pausa/retomada, edição "desta em diante", capping não-padrão 31→28, detach formal (#3); fantasma na timeline + simulador "E se...?" (#4). Parcelamento de cartão **permanece** em `InstallmentGroup`+`Invoice`. Spec: `docs/superpowers/specs/2026-06-25-motor-de-recorrencia-nucleo-design.md`.

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

## Planejamento Mensal (`/api/{budget-cycles,budget-items}` + `PATCH /api/tenant/settings`)

- `BudgetCycle`: datas calculadas por `startDay` (1 → mês calendário; N → dia N do mês anterior até N-1 do atual). Ao abrir, popula itens recorrentes via `RecurrenceProjectionService` (projeção on-the-fly das `RecurrenceRule` ativas) e parcelas de cartão do período.
- `BudgetItem`: criação, update, link/unlink a transações (guard anti-duplicação). Itens `source=RECURRING` carregam `recurrenceRuleId` + `recurrenceOccurrenceDate` para rastreabilidade.

**`openingBalance` (ao abrir):** `sumLiquidBalanceByTenant` = caixa líquido PAID **anterior** ao ciclo (`t.date < startDate`, contas `countInLiquidBalance=true`). O corte de data evita dupla contagem: transações dentro do período só entram via realizados/avulsas, nunca no opening.

**Resumo do ciclo (`BudgetSummaryService` — fonte única; o DTO é só mapeador):**
- `currentBalance` = **caixa real agora** = `opening + realizados + avulsas`, contando **só PAID** (realizado = item REALIZED cuja transação está PAID).
- `availableToSpend` = **projeção do que dá pra gastar** = `opening + toda receita − toda despesa` (itens ativos exceto SKIPPED + avulsas), **independente de PAID/PENDING**. Conservadorismo simétrico: receita só ajuda quando lançada; despesa pesa assim que existe no sistema. Equivale a `projectedBalance + (avulsas: receitas − despesas)`.
- `dailyAllowance` = `availableToSpend / dias restantes` (FLOOR 2 casas; 0 se ≤0 ou sem dias; null fora de OPEN).
- `unplannedIncome/Expense` no DTO = **total** das avulsas (PAID+PENDING), para exibição; a lista de avulsas inclui PENDING com badge.
- `BudgetItemResponse.transactionStatus`: status (PAID/PENDING) da transação vinculada — permite ao frontend distinguir realizado-em-caixa de realizado-pendente sem assumir `REALIZED = pago`.
- `BudgetItemResponse.recurrenceRuleId` + `recurrenceOccurrenceDate`: presentes quando `source=RECURRING`; permitem ao frontend navegar para a regra ou exibir o slot canônico.

**Frontend do Planejamento (ciclo atual):**
- Cada card (Receitas, Despesas, Saldo, Disponível) tem ícone de "olho" → modal de composição (fórmula + itens contribuintes), montado de `BudgetSummaryService` no frontend a partir dos dados já carregados.
- Cards atualizam em tempo real após mutações (refresh silencioso do ciclo, sem flash de loading).
- Lista de não planejados: coluna de status (Pago/Pendente), ação "vincular a item planejado" e "criar item planejado" (cria + vincula à transação de origem).
- Aba "Recorrentes" gerencia `RecurrenceRule` diretamente (CRUD completo, incluindo reativação de regras canceladas) — a tabela `recurring_budget_items` foi removida (V21).

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
