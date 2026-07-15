# Design: Motor de Recorrência — Núcleo (Sub-projeto 1)

> **Status:** aprovado em brainstorming, pronto para plano de implementação.
> **Fonte do produto:** `2026-06-25-motor-de-recorrencia.md` (PRD original).
> Esta spec reconcilia o PRD com o domínio existente e recorta o **primeiro** dos quatro
> sub-projetos. Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto e decomposição

O PRD "Motor de Recorrência Unificado" foi escrito como greenfield, mas o projeto já tem
em produção três peças que ele redescreve: `RecurringBudgetItem` (regra mensal de
planejamento), `InstallmentGroup` (parcelamento de cartão materializado) e `BudgetItem`
com `status ∈ {PENDING, REALIZED, SKIPPED}` (a semântica fantasma → confirmar/pular).

Reconciliado, o PRD se quebra em quatro sub-projetos independentes, cada um com seu
ciclo spec → plano → implementação:

| # | Sub-projeto | Entrega |
|---|-------------|---------|
| **1** | **Núcleo: Regra + projeção na lista** | *(esta spec)* |
| 2 | Migração do Planejamento | `RecurringBudgetItem` → `recurrence_rules`; ciclo passa a consumir a projeção |
| 3 | Mutabilidade avançada | pausa/retomada, edição "desta em diante", capping fim-de-mês (FR-01), detach pontual |
| 4 | Simulador "E se...?" | `scenario_id`, projeção híbrida, adotar cenário — PRD próprio no futuro |

**Decisão arquitetural transversal (Caminho B):** o motor nasce como tabela nova
`recurrence_rules`, fonte única; o planejamento será migrado para consumi-la no #2 (não
nesta spec). Aceitamos a coexistência temporária com `RecurringBudgetItem` até o #2.

## 2. Escopo desta spec

**Dentro:**
- Modelo da Regra (`recurrence_rules`) e das exceções (`recurrence_exceptions`).
- Projeção *on-the-fly* das "linhas fantasma" — nada materializado antes da confirmação.
- Renderização das fantasmas **apenas na lista de transações** (mês atual/futuro).
- Ações **Confirmar** (materializa `Transaction`) e **Pular** (grava EXDATE).
- CRUD completo de recorrências (toggle no form de transação + feature "Recorrências").

**Fora (sub-projetos posteriores):**
- Migração do planejamento (#2); pausa/retomada, edição "desta em diante", capping
  31→28 do FR-01, detach pontual (#3); fantasma na timeline e simulador (#4).
- Parcelamento de cartão **permanece** em `InstallmentGroup` + `Invoice`. Parcela de
  cartão já é materializada e amarrada a fatura; trazê-la pro motor de projeção quebraria
  o ciclo de faturas. O motor cobre só os arquétipos `infinite` e `until_date`.

## 3. Princípios

1. **Regra vs. Transação.** A Regra é a definição atemporal (RRULE). A Transação é o fato
   imutável, gravado só após confirmação do usuário.
2. **Projeção pura.** Transações futuras **não** são gravadas antecipadamente. A fantasma
   de um mês é recalculada a cada leitura:
   `fantasma(janela) = expand(rrule, janela) − {ocorrências já materializadas} − {EXDATE}`.
   Tudo keyed pela **data da ocorrência**.
3. **Padrão da indústria para recorrência (RFC 5545 / RRULE).** Não inventamos DSL de
   recorrência nem escrevemos a matemática de datas (ano bissexto, último dia do mês):
   armazenamos um `rrule` e delegamos a expansão a uma biblioteca madura. EXDATE do RFC =
   nosso "Pular"; `UNTIL` = fim por data; `COUNT` = fim por nº de ocorrências.
4. **Superfície controlada.** Guardamos o `rrule` completo, mas **validamos a entrada** a
   um subconjunto financeiro — para não herdar a superfície de um app de agenda.
5. **Reuso na materialização.** Confirmar uma ocorrência delega ao caminho de criação de
   `Transaction` que já existe — se a conta for cartão, a fatura é resolvida de graça.

## 4. Modelo de dados

### 4.1 Migrations

- **V19** — schema: `recurrence_rules`, `recurrence_exceptions`, colunas em `transactions`.
- **V20** — seed `dev` (perfil): dados de recorrência da Família Costa.
  *(Seed precisa de versão maior que a migration das tabelas que popula.)*

### 4.2 `recurrence_rules` (a Regra / Blueprint, tenant-scoped)

| Coluna | Tipo | Notas |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID FK NOT NULL | **isolamento de tenant** — invariante central |
| `description` | VARCHAR(255) NOT NULL | ex.: "Netflix" |
| `base_amount` | NUMERIC NOT NULL | valor padrão projetado |
| `type` | VARCHAR(10) NOT NULL | `INCOME \| EXPENSE` (reusa `TransactionType`) |
| `category_id` | UUID FK NULL | |
| `account_id` | UUID FK NOT NULL | toda materialização precisa de conta |
| `rrule` | TEXT NOT NULL | string RFC 5545 (sem `DTSTART`; vide `start_date`) |
| `start_date` | DATE NOT NULL | âncora (`DTSTART`) da expansão |
| `status` | VARCHAR(10) NOT NULL | `ACTIVE \| CANCELLED` (`paused`/`completed` ficam no #3) |
| `created_by` | UUID FK NULL | autoria |
| `created_at` / `updated_at` | TIMESTAMP | |

**Colapsadas do PRD para dentro do `rrule`:** `frequency`, `interval`, `anchor_day`,
`end_date`, `total_installments`.

### 4.3 `recurrence_exceptions` (EXDATE — esparsa)

| Coluna | Tipo | Notas |
|--------|------|-------|
| `id` | UUID PK | |
| `rule_id` | UUID FK NOT NULL | |
| `occurrence_date` | DATE NOT NULL | a ocorrência pulada |
| | | `UNIQUE(rule_id, occurrence_date)` |

Só ganha linha quando o usuário **pula** de fato — uma regra nunca pulada tem zero linhas.
Índice `(rule_id, occurrence_date)`; consulta de projeção é batched (`rule_id IN (...)`).

### 4.4 Acréscimos a `transactions`

| Coluna | Tipo | Notas |
|--------|------|-------|
| `recurrence_rule_id` | UUID FK NULL | preenchido = originada de regra |
| `recurrence_occurrence` | DATE NULL | o "slot" da regra que esta transação satisfaz |
| | | `UNIQUE(recurrence_rule_id, recurrence_occurrence)` parcial — guarda contra confirmar a mesma ocorrência 2x |

`recurrence_occurrence` é distinto de `date`: `date` é a data efetiva que o usuário atesta;
`recurrence_occurrence` é a data canônica da regra (pino de re-projeção).

**Não modelado (cortes do PRD):** `is_detached_from_rule` é **derivável**
(`amount != rule.base_amount`); `installment_number` é de `InstallmentGroup`, fora daqui.

## 5. RRULE — subconjunto suportado

| Aceito | Exemplo |
|--------|---------|
| `FREQ=MONTHLY \| YEARLY` | `FREQ=MONTHLY` |
| `INTERVAL=N` | `INTERVAL=3` (trimestral) |
| `BYMONTHDAY` (1..31 **e** `-1` = último dia) | `BYMONTHDAY=15`, `BYMONTHDAY=-1` |
| `UNTIL` (fim por data) | `UNTIL=20271231T000000Z` |
| `COUNT` (fim por nº) | `COUNT=12` |

**Rejeitado pela validação de entrada (400):** `FREQ=SECONDLY/MINUTELY/HOURLY/DAILY/WEEKLY`,
`BYDAY`, `BYSETPOS`, `BYWEEKNO`, `BYYEARDAY`. Mantém a lógica de orçamento pensando em mês.

**Reframe do FR-01 (capping fim-de-mês):** no RFC 5545 estrito, `BYMONTHDAY=31` **pula** os
meses sem dia 31 (fev/abr/jun...). O clamp "31 → 28 de fevereiro" do PRD é **não-padrão** e
fica para o #3. Nesta spec, "fim do mês" se escreve `BYMONTHDAY=-1` (último dia), que o
expander resolve nativamente (28/29 fev, 30 abr, 31 mar).

## 6. Backend

### 6.1 Componentes

- **`RecurrenceRule`, `RecurrenceException`** — entidades JPA em `domain/recurrence/`.
- **`RecurrenceStatus`** (enum `ACTIVE, CANCELLED`) em `domain/enums/`.
- **`RecurrenceRuleRepository`** — `findByTenantAndStatus(...)`, escopado por tenant.
- **`RecurrenceExceptionRepository`** — `findByRuleIdInAndOccurrenceDateBetween(...)` (batched, sem N+1).
- **`RruleValidator`** — parseia o `rrule` e rejeita o que está fora do subconjunto (§5).
- **`RecurrenceProjectionService`** — o coração (§6.2).
- **`RecurrenceRuleService`** — CRUD tenant-scoped.
- **`TransactionService`** (alterado) — merge de projeção, `confirm`, `skip` (§6.3).
- Controllers + DTOs (§7).

Biblioteca de expansão: **`lib-recur`** (`com.github.dmfs`, Maven) — leve, expande RRULE
sem arrastar stack iCal. *(Não escrevemos o gerador de datas à mão.)*

### 6.2 `RecurrenceProjectionService.project(tenant, from, to, filtros)`

1. Carrega regras `ACTIVE` do tenant, respeitando filtros de conta/tipo da query de lista.
2. Para cada regra, expande o `rrule` (ancorado em `start_date`) na janela
   `[max(start_date, from) .. to]` via `lib-recur`.
3. Carrega, batched: transações com `recurrence_rule_id` na janela (datas de ocorrência já
   materializadas) **e** EXDATE na janela.
4. Uma ocorrência é fantasma sse sua data ∉ materializadas ∧ ∉ EXDATE.
5. Mapeia para `ProjectedOccurrence` (`ruleId`, `occurrenceDate`, `description`,
   `amount = base_amount`, `type`, `category`, `account`, `projected = true`).

A projeção **sempre** recebe janela — nunca expande "infinito". Custo: 3 queries indexadas
+ dezenas de objetos em memória por tenant; barato por construção (exceções esparsas).

### 6.3 Alterações em `TransactionService`

- **`list(...)` com `includeProjected`:** quando ligado e o período é atual/futuro, chama
  `RecurrenceProjectionService` e mescla as fantasmas ao resultado, ordenando tudo por
  `effectiveSortDate` (fantasma usa `occurrenceDate`). `includeProjected=false` (default)
  mantém o comportamento atual intacto.
- **`confirm(ruleId, occurrenceDate, amountOverride?, dateOverride?)`:** monta um
  `TransactionCreateDTO` a partir da regra (`account`, `category`, `type`,
  `amount = override ?? base_amount`, `date = override ?? occurrenceDate`), seta
  `recurrence_rule_id` + `recurrence_occurrence` e **delega ao caminho de criação de
  transação existente** (fatura de cartão resolvida via `resolveInvoiceMonth`/`getOrCreate`).
  A unique parcial impede confirmar a mesma ocorrência 2x.
- **`skip(ruleId, occurrenceDate):`** insere linha em `recurrence_exceptions`.

## 7. API

| Método | Rota | Auth | Descrição |
|--------|------|------|-----------|
| GET | `/api/transactions?includeProjected=true` | auth | mescla reais + fantasmas (default `false`, retrocompatível) |
| POST | `/api/recurrence-rules` | auth | cria regra (valida RRULE) |
| GET | `/api/recurrence-rules` | auth | lista regras do tenant |
| GET | `/api/recurrence-rules/{id}` | auth | detalhe |
| PATCH | `/api/recurrence-rules/{id}` | auth | edita `description` + `base_amount` |
| DELETE | `/api/recurrence-rules/{id}` | auth | cancela (`status = CANCELLED`) |
| POST | `/api/recurrence-rules/{id}/occurrences/{date}/confirm` | auth | materializa (body: `amount?`, `date?`) |
| POST | `/api/recurrence-rules/{id}/occurrences/{date}/skip` | auth | grava EXDATE |

Todos `authenticated` + escopados por tenant no service (sem role específica — coerente com
budget items, acessíveis a MEMBER). Spec-first: editar `api-spec/openapi.yaml` primeiro.

**DTOs:** `RecurrenceRuleCreateDTO` (Bean Validation + `@ValidRrule` custom),
`RecurrenceRuleResponseDTO`, `ConfirmOccurrenceDTO` (`amount?`, `date?`).
`TransactionResponseDTO` ganha `projected: boolean`, `recurrenceRuleId?`, `occurrenceDate?`.

## 8. Frontend

- **Editor de RRULE** (componente standalone, usa **`rrule.js`**): frequência
  (mensal/anual), intervalo, dia do mês (incl. "último dia"), fim (nunca / até data / após N
  ocorrências). Emite o `rrule` string + preview das próximas ocorrências (signals).
- **Form de transação:** toggle "repetir" → embute o editor; ao salvar, cria a regra e
  materializa a 1ª ocorrência (a própria transação criada).
- **Lista de transações:** `includeProjected=true` no mês atual/futuro; fantasma
  visualmente distinta (itálico + opacidade reduzida + ícone — FR-04); ações por linha
  **Confirmar** (dialog para ajustar valor/data antes do INSERT) e **Pular**.
- **Feature "Recorrências"** (rota lazy `recurrences/`): listar, criar, editar (`description`
  + `base_amount`), cancelar.
- Estado em signals; RxJS só para o HTTP. Cliente API regenerado via Orval.

## 9. Tratamento de erro

| Situação | Resposta |
|----------|----------|
| RRULE fora do subconjunto | 400 (`@ValidRrule` → `GlobalExceptionHandler`) |
| Confirmar ocorrência já materializada | 409 (unique → `BusinessException`) |
| Regra/ocorrência de outro tenant | 404 (`EntityNotFoundException`) |
| Conta da regra é cartão | fatura resolvida pela lógica existente (sem caminho novo) |

## 10. Testes

Cobertura alvo: lógica de negócio (projeção, validação, confirm/skip), não boilerplate.

- **Expander/validador RRULE (unit):** subconjunto aceito/rejeitado; **Teste A reframado** —
  `BYMONTHDAY=-1` → 28/29 fev, 30 abr, 31 mar (clamp 31→28 do FR-01 é do #3).
- **Projeção (unit/integração):**
  - **Teste B (inflação):** regra R$100/mês; confirma agosto a R$150 → fantasma de setembro
    volta a R$100 (lê a regra, não um flag).
  - **Teste C (pular):** pula o mês vigente → EXDATE; mês seguinte volta a exibir fantasma.
  - fantasma = expand − materializadas − EXDATE; janela respeitada.
- **Confirm/skip (integração, Testcontainers + `seed_base.sql`):** INSERT com
  `rule_id`+`occurrence`; unique impede duplicidade; skip grava EXDATE.
- **Isolamento de tenant:** projeção e CRUD nunca cruzam tenants.

## 11. Dataset de testes (regra inviolável)

- **Seed V20 (perfil `dev`):** ≥1 regra da Família Costa (ex.: "Netflix" mensal infinita,
  `FREQ=MONTHLY;BYMONTHDAY=15`) com UUID predefinido na série correspondente; opcionalmente
  1 EXDATE de exemplo.
- **`seed_base.sql`:** 1 regra mínima para os testes de integração.
- **`docs/http/seed-dataset.http`:** requests para `includeProjected`, criar regra, confirmar
  e pular.

## 12. Dependências novas

| Dependência | Onde | Substitui |
|-------------|------|-----------|
| `lib-recur` (`com.github.dmfs`) | backend (Maven) | matemática de expansão RRULE no servidor |
| `rrule.js` | frontend (npm) | editor/preview de RRULE no cliente |

## 13. Mapa de reconciliação PRD → esta spec

| PRD | Reconciliação |
|-----|---------------|
| `recurrence_rules` com `frequency/interval/anchor_day/end_date/total_installments` | colapsado em `rrule` TEXT |
| `user_id` | **`tenant_id`** (invariante de isolamento) |
| `is_detached_from_rule` (boolean) | removido — derivável (`amount != base_amount`) |
| `type=installments` / `installment_number` | fora — fica em `InstallmentGroup` + `Invoice` |
| FR-01 capping 31→28 | reframado p/ `BYMONTHDAY=-1`; clamp não-padrão adiado p/ #3 |
| FR-02 detach pontual | parcialmente coberto (confirm com `amount?`); detach formal no #3 |
| FR-03 pausa/retomada | #3 |
| FR-04 linhas fantasma + confirmar/pular | **esta spec** |
| Seção 2.2 "Regra B invisível" | #3 (e provavelmente desnecessária — passado já é imutável via `transactions`) |
| Seção 6 simulador "E se...?" | #4 (PRD próprio) |
