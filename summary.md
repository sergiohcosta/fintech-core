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

**Login (`/auth/login`):** resposta genérica (401) tanto para email inexistente quanto para senha incorreta ou usuário inativo (sem enumeração de usuários). Rate limit em memória: 5 tentativas falhas por email a cada 1 minuto → `429 Too Many Requests` (`LoginRateLimiter`). **Chave = email apenas** (#144) — não deriva de `X-Forwarded-For` (controlável pelo cliente sem trusted proxy, permitia bypass rotacionando o header). Mapa com teto (`security.rate-limit.max-keys`, default 100k) + sweep periódico (`@Scheduled`, `@EnableScheduling`) evitam DoS de memória por flood de emails. `User.isEnabled()` reflete o campo `active` — checado no login e em toda requisição autenticada (`SecurityFilter`).

**Export CSV (frontend):** `csvField` (`core/csv.utils.ts`) neutraliza CSV formula injection (#143) — prefixa `'` quando o valor começa com `= + - @` TAB/CR, além do quoting RFC 4180 (aspas para `; " \n \r`). Defesas distintas: quoting protege a estrutura do CSV, o apóstrofo impede a planilha de executar a fórmula.

**Política de senha (registro e aceite de convite):** mínimo 8 e máximo 72 caracteres, com letra maiúscula, minúscula e número (`TenantRegistrationDTO`, `AcceptInviteDTO`).

## Auth & Convites

| Método | Rota | Auth | Descrição |
|--------|------|------|-----------|
| POST | `/auth/register` | público | Cria Tenant + User(ADMIN); retorna `{ id, name }` (201, **sem JWT** — login em seguida) |
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
  Transaction { date=dataCompra, installmentNumber=i+1, amount=parcela(i), invoice=faturaDoMês(i) }
```
`resolveInvoiceMonth`: `day <= closingDay` → mês corrente; senão → mês seguinte.
`parcela(i)`: `total/N` truncado (DOWN, 2 casas) nas N−1 primeiras; a **última absorve o resíduo** (`total − (N−1)·parcela`) para que `soma(parcelas) == total` exatamente (#136).

**DELETE `?scope=`:** SINGLE · THIS_AND_NEXT (próximas PENDING) · ALL (todas PENDING do grupo). Protege PAID. Retorna `{ deleted, skippedPaid }`.

**Perna de transferência é imutável isoladamente (#138):** `PUT`/`DELETE` numa transação com `transferId != null` → **400** (`BusinessException`). Double-entry é invariante: as pernas nascem juntas (`createTransfer`) e morrem juntas (`DELETE /api/transfers/{transferId}`). Frontend desabilita editar/excluir individual nessas linhas.

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
- **Validação de slot (#146):** só confirma/pula regra `ACTIVE` (senão **422**), com `occurrence` ∈ expansão da RRULE no mês (`RecurrenceProjectionService.occursOn`); confirmar exige `occurrence` ∉ EXDATE. Sem isso, confirmar um não-slot convivia com o fantasma real → pagamento 2×.
- **Vínculo automático ao planejamento (#140):** após materializar, `RecurrenceRuleService.confirmOccurrence` chama `BudgetItemService.linkRecurringOccurrence` — vincula a transação ao item RECURRING PENDENTE do ciclo aberto (se houver), pelo caminho unificado do #141. Sem isso, a transação apareceria como avulsa e o resumo contaria item planejado + avulsa (dupla contagem). Orquestrado no planejamento — `TransactionService` não conhece o domínio de budget.

**Pular:** grava EXDATE em `recurrence_exceptions` (idempotente). A fantasma some no mês pulado e volta no seguinte. Valida slot/status como o confirmar (#146).

**RRULE — subconjunto suportado:** `FREQ=MONTHLY|YEARLY`, `INTERVAL`, `BYMONTHDAY` (1..31 e `-1`=último dia), `UNTIL`, `COUNT`. `@ValidRrule` rejeita o resto (`BYDAY`/`BYSETPOS`/`BYWEEKNO`/`BYYEARDAY`/`BYHOUR`/`BYMINUTE`, e `FREQ` diário/semanal) → **400**. "Fim do mês" = `BYMONTHDAY=-1` (resolve 28/29 fev, 30 abr nativamente). Validação varre as chaves do rrule (o parser lax do lib-recur descarta partes inválidas no contexto).

**Fora do núcleo (sub-projetos futuros):** pausa/retomada, edição "desta em diante", capping não-padrão 31→28, detach formal (#3); fantasma na timeline + simulador "E se...?" (#4). Parcelamento de cartão **permanece** em `InstallmentGroup`+`Invoice`. Spec: `docs/superpowers/specs/2026-06-25-motor-de-recorrencia-nucleo-design.md`.

## Transferências (`/api/transfers`)

POST (cria par EXPENSE origem + INCOME destino) · DELETE/{transferId} (remove ambos).
**Double-entry:** 2 Transactions com mesmo `transferId`. Não há entidade Transfer.

## Faturas (`/api/invoices`)

GET `?accountId=` · GET/{id} · POST/{id}/close · POST/{id}/pay `{ sourceAccountId }`

**`totalAmount` (lista e detalhe) — líquido, não bruto:** `SUM(CASE WHEN type=EXPENSE THEN amount ELSE -amount END) WHERE status<>CANCELLED` (`sumAmountByInvoice`/`findByAccountWithTotals`). INCOME (estorno/reembolso) abate o total em vez de somar — mesma convenção de sinal do dashboard/saldo de conta. É o valor usado como base do pagamento em `pay()`.

**Ciclo:** `OPEN → [close] CLOSED → [pay] PAID`
- **close:** só muda status. Novas transações ainda aceitas (cobranças atrasadas).
- **pay** (`@Transactional` única): **claim atômico** `UPDATE ... SET status=PAID WHERE id=:id AND status=CLOSED` (`markAsPaidIfClosed`) ANTES de qualquer efeito — 0 linhas afetadas → `IllegalStateException` (pagamento concorrente já venceu, #139). Só o vencedor: PENDING→PAID via `@Modifying` batch; se total>0 cria EXPENSE na origem (`date=now()`, `description="Pagamento fatura {acc} {MM}/{yyyy}"`). Fecha o ciclo de caixa do cartão (`countInLiquidBalance=false`).
- **Validações pay:** origem do tenant (404), origem ≠ CREDIT_CARD (422), fatura CLOSED (422). Ordem: validações → claim atômico → efeitos.
- **Lazy create** (`getOrCreate`): automático na 1ª transação do período. `UNIQUE(account, year, month)`. Race condition resolvida com `@Transactional(REQUIRES_NEW)` + retry (ADR-001 #83).
- **dueDate:** `dueDay >= closingDay` → mesmo mês; senão → mês seguinte. Dia capado ao último do mês (`min(dia, lengthOfMonth)`) — closingDay/dueDay=31 em fevereiro não estoura `DateTimeException` (#137).

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

`income`/`expense`/`transactionCount` **excluem** transferências (`transferId IS NULL`) e pagamentos de fatura (`paidInvoice IS NULL`) — senão a transferência infla os dois lados e o pagamento de cartão conta a despesa 2× (compra no mês do `dueDate` + pagamento no mês do débito) (#145).

`totalAccountBalance`: `SUM(±amount) WHERE status=PAID AND account.countInLiquidBalance=true AND account.active=true` (sem filtro de período). Exclui contas arquivadas (#151) — consistente com o `openingBalance` do ciclo. **Não** exclui o pagamento de fatura: é saída real de caixa e deve rebaixar o saldo.

## Planejamento Mensal (`/api/{budget-cycles,budget-items}` + `PATCH /api/tenant/settings`)

- `BudgetCycle`: datas calculadas por `startDay` (1 → mês calendário; N → dia N do mês anterior até N-1 do atual). Ao abrir, popula itens recorrentes via `RecurrenceProjectionService` (projeção on-the-fly das `RecurrenceRule` ativas) e parcelas de cartão do período.
- `BudgetItem`: criação, update, link/unlink a transações. **`link` e `realize` compartilham os mesmos guards (#141):** ciclo OPEN, item PENDING, transação em no máximo um item (qualquer ciclo), compatibilidade de tipo e sync de `amount` com a transação. Itens `source=RECURRING` carregam `recurrenceRuleId` + `recurrenceOccurrenceDate` para rastreabilidade.
- **`syncInstallments` (aditivo, #152):** reconcilia só os PREVISTOS — remove apenas itens INSTALLMENT `status=PENDING` sem transação vinculada e regenera; itens REALIZED/vinculados são preservados (fato consumado não é apagado numa reconciliação), sem duplicar grupos já cobertos.

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

## Importação / Extração (`/api/imports`) — Fase 0 (fundação) + Fase 1 (MVP de imagem)

Pipeline de extração multi-mídia (roadmap `docs/roadmap-extracao-e-conciliacao.md`). A Fase 0 provou o contrato de staging ponta a ponta (sem extrator real); a Fase 1 liga o extrator de verdade para imagem e fecha o ciclo upload → revisão → commit. Spec: `docs/superpowers/specs/2026-07-24-extracao-fundacao-e-mvp-imagem-design.md`.

| Método | Rota | Auth | Descrição |
|--------|------|------|-----------|
| POST | `/api/imports` | authenticated | **(Fase 1)** multipart (`file` + `importMode`) — aciona o `VisionExtractor`, grava batch `EXTRACTED` + staged `PENDING`. Falha de extração → batch `FAILED` (fallback é o formulário manual de transação) |
| PATCH | `/api/imports/{id}/staged/{stagedId}` | authenticated | **(Fase 1)** edita campos de uma staged `PENDING` antes de lançar — grava confiança `1.0` (dado confirmado por humano) e re-deriva `requiresReview` |
| POST | `/api/imports/{id}/commit` | authenticated | **(Fase 1)** promove as staged listadas (`items: [{stagedId, accountId, categoryId?}]`) a `Transaction`, reusando o caminho de criação existente; marca cada staged `CONFIRMED` e o batch `COMMITTED` quando não sobra nenhuma `PENDING` |
| POST | `/api/imports/mock` | authenticated | (dev) cria batch a partir de um `NormalizedBatchDTO` mockado — prova o ponta a ponta sem extrator |
| GET | `/api/imports/{id}` | authenticated | detalhe do batch (404 se de outro tenant) |
| GET | `/api/imports/{id}/staged` | authenticated | lista as transações em staging do batch (404 se de outro tenant) |

**Extrator (Fase 1):** `TransactionExtractor` é a porta agnóstica de provider; `VisionExtractor` é a implementação sobre o `ChatClient` do Spring AI 2.0.0-M2 (`spring-ai-starter-model-ollama`, default Ollama do homelab, modelo `llama3.2-vision` — cabe nos 11GB da GPU do homelab (`num-ctx=4096` roda 100% na GPU; `spring.ai.retry.max-attempts=2` falha rápido no fallback em vez de prender o usuário). Troca de provider/modelo é troca de starter Maven + properties, sem tocar no código). Pede saída **estruturada e tipada** (`.entity(LlmReceiptExtractionDTO.class)` — um record plano, mais fácil pro modelo de visão preencher que o mapa aninhado do `NormalizedBatchDTO`) e **revalida a plausibilidade do nosso lado** antes de aceitar (guarda-corpo): schema íntegro não garante conteúdo são — o modelo pode alucinar um valor com formato válido. `amount` ausente ou ≤0 derruba a extração (`ExtractionException` → batch `FAILED`); data ilegível não derruba (confiança zerada, usuário completa na revisão). Mapeamento de direção: `debit`→`EXPENSE`, `credit`→`INCOME` (default `debit` se irreconhecível). `requires_review` **nunca** é decidido pelo extrator — quem deriva por threshold é sempre o `ImportService` (mesma regra da Fase 0).

**1 imagem = 1 transação — recusa explícita fora do escopo (#193):** `LlmReceiptExtractionDTO` é plano por design (Fase 1 = comprovante único). Print de **extrato completo** (várias transações na mesma imagem) é **detectado e recusado**, não descartado em silêncio: o modelo preenche `multipleTransactionsDetected` (true só quando vê uma LISTA de lançamentos — várias linhas com data e valor próprios) e o `VisionExtractor` lança `ExtractionException` → batch `FAILED` com motivo exibível. A checagem vem **antes** da validação de `amount`: num extrato o modelo escolhe uma linha arbitrária e devolve valor plausível, então a ordem inversa deixaria o caso passar. `null`/ausente **não** recusa (modelo que não preenche a flag extrai normal — ausência de sinal não é sinal). Suporte real a multi-transação por imagem: roadmap Fase 3, #194.

**`failureReason` no batch (V25, #193):** batch `FAILED` carrega o motivo legível em PT-BR — causas distintas pedem ações distintas ("envie um comprovante por vez" ≠ "imagem ilegível"), e recusar sem dizer por quê é meio erro silencioso. Só a mensagem da `ExtractionException` (redigida por nós) chega ao usuário; qualquer outra exceção vira texto genérico — mensagem de infra (host, driver, stack) nunca cruza a borda da API. Frontend exibe no card de falha, com fallback local para batch pré-V25.

**Commit (`POST .../commit`):** por item, valida sanidade dos valores **atuais** (originais ou já editados via PATCH) — `amount >= 0.01` e `transaction_date` parseável, senão 400 (`BusinessException`). Cria a `Transaction` reusando `TransactionService.create` (não reimplementa regra de fatura/parcela) com `status=null` → aplica o default `PENDING`, igual a um lançamento manual. Staged já não-`PENDING` (reenvio) → 400.

**Staging separado, não `DRAFT` em `transactions`:** o dado extraído é probabilístico (carrega `confidence`, `requires_review`) e nasce em `import_batches` + `staged_transactions`, sendo **promovido** a `Transaction` só no commit (Fase 1). `transactions` continua sendo, linha a linha, apenas fato confirmado — nenhuma query de negócio existente precisa passar a filtrar dado sujo.

**Isolamento de tenant (invariante nº1):** `ImportService` recebe `User` e filtra `user.getTenant()`. `staged_transactions.tenant_id` é **denormalizado** (também está no batch) — defesa nº1: toda leitura filtra o tenant direto na linha, sem depender de JOIN em `import_batches`. Recurso de outro tenant → **404** (não confirma existência).

**`requires_review` é DERIVADO no código, nunca pelo modelo:** `deriveRequiresReview` marca `true` se `overallConfidence < import.review.overall-threshold` (0.90) **ou** se a confiança do campo `amount` < `import.review.amount-threshold` (0.95); ausência de confiança conta como duvidoso. O produto controla a régua por properties, sem retreinar nada. O campo `requiresReview` do DTO de entrada é ignorado.

**`fields JSONB` (`@JdbcTypeCode(SqlTypes.JSON)`):** `{value, confidence}` por campo (amount, currency, transaction_date, posting_date, description, direction, payment_method), keyed pelo nome — mapa flexível (Hibernate 6 nativo, zero dependência nova). `posting_date DATE` nullable também entrou em `transactions` (V23), ainda não consumido (Fase 5).

**Seed (V24):** 1 batch COMMITTED + 2 staged CONFIRMED com `promoted_transaction_id` → transações do V13.

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
- **Testes:** lógica pura em arquivos sem imports Angular (ex: `transaction-list.utils.ts`, `amount-math.ts`, `installment-preview.ts`, `transaction-form.utils.ts`) — testável no Vitest sem `TestBed`. Parsing de valor (ponto-decimal e pt-BR) e formatação de data local (sem UTC) vivem em `transaction-form.utils.ts` (#148).

## Armadilhas Conhecidas (Codegen)

- `auth/auth.service.ts` é regenerado pelo Orval — deletar manualmente antes de usar.
- Sem `required:` em schemas de resposta → Orval gera campos opcionais → `!` assertions.
- `springdoc` deve ser `2.8.9` (incompatível com 2.6.0 no Spring Boot 4.0.1).
