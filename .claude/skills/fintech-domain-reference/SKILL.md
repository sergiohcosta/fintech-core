---
name: fintech-domain-reference
description: >
  Teoria de domínio financeiro do fintech-core, ancorada no código real. Use quando a dúvida
  envolver saldo/balance (por que PENDING não conta), totalAccountBalance do dashboard,
  liquidez vs patrimônio (countInLiquidBalance/countInNetWorth), fatura/invoice de cartão de
  crédito (closingDay, dueDay, resolveInvoiceMonth, OPEN→CLOSED→PAID, fechar/pagar fatura),
  data efetiva (effectiveSortDate), parcelamento/installments (InstallmentGroup, escopos de
  delete, propagate, centavos), transferências double-entry (transferId), recorrência/RRULE
  (regra vs transação, fantasma, EXDATE, confirmar/pular), planejamento/budget Modelo A
  (openingBalance, currentBalance, availableToSpend, dailyAllowance) ou multi-tenant
  (tenant_id, schema compartilhado, RLS). Referência de leitura — não ensina a rodar nem a mudar nada.
---

# Referência de Domínio Financeiro — fintech-core

Este é o pacote de teoria de domínio que um engenheiro pleno normalmente não tem, escrito
**exatamente como o conceito está implementado neste repositório**. Cada afirmação aponta para a
classe ou query real. Não é livro-texto de finanças: se o código divergir da teoria clássica,
o que vale aqui é o código.

## Quando NÃO usar

| Necessidade | Skill correta |
|---|---|
| Saber a decisão/invariante arquitetural e o porquê (ex.: enunciado do isolamento de tenant) | `fintech-core-architecture-contract` |
| Provar um cálculo ou invariante com queries/experimentos (saldo, centavos, race, RRULE) | `fintech-core-proof-and-analysis-toolkit` |
| Atacar os bugs abertos citados aqui (#136, #145…) | `fintech-core-bug-backlog-campaign` |
| Fazer/promover uma mudança de comportamento | `fintech-core-change-control` |
| Detalhe do candidato RLS (#116) | `fintech-core-research-frontier` |

## Glossário (definição única)

- **Tenant** — o cliente lógico (família ou empresa) dentro da instância compartilhada; toda entidade de negócio pertence a exatamente um.
- **Fatura** (*invoice*) — agregado mensal de transações de um cartão de crédito, identificado por `(account, referenceYear, referenceMonth)`.
- **Parcela** — uma das N transações geradas por uma compra parcelada, ligadas por um `InstallmentGroup` (o grupo que guarda descrição, total e N).
- **Fantasma** — ocorrência futura de uma regra de recorrência, projetada em memória e nunca persistida (`projected=true`, `id=null`).
- **Materializar** — transformar um fantasma em transação real (fato imutável no banco).
- **EXDATE** — data de exceção de uma regra de recorrência (ocorrência "pulada"), gravada em `recurrence_exceptions`.
- **Ciclo** (de planejamento) — janela `[startDate, endDate]` de um `BudgetCycle`, dentro da qual itens planejados e transações avulsas são comparados com o caixa.
- **Avulsa** — transação do período do ciclo sem `BudgetItem` vinculado (retornada por `findUnplannedByCycle`; transferências e CANCELLED excluídas).

---

## 1. Semântica de saldo: só PAID conta

Não existe coluna de saldo nem tabela de snapshot — **saldo é sempre derivado por soma**. A
fórmula canônica (em `AccountRepository.calculateBalance`) é:

```
saldo(conta) = SUM(CASE WHEN type = INCOME THEN amount ELSE -amount END)
               WHERE account = :conta AND status = PAID
```

`PENDING` não conta porque representa compromisso, não caixa: uma parcela de cartão pendente
ainda não saiu de nenhuma conta; ela só vira caixa quando o pagamento da fatura a converte em
PAID (seção 3). `CANCELLED` nunca conta em nada.

Existem **três "saldos" diferentes** e eles não são intercambiáveis:

| Conceito | Escopo | Query real | Filtros |
|---|---|---|---|
| Saldo da conta (`balance` no `GET /api/accounts/{id}`) | uma conta | `AccountRepository.calculateBalance` | `status = PAID` |
| `totalAccountBalance` do dashboard | tenant inteiro, **sem filtro de período** | `TransactionRepository.sumNetLiquidBalanceByTenant` | `status = PAID` e `account.countInLiquidBalance = true` |
| Patrimônio (net worth) | tenant | **não existe consumidor ainda** (2026-07-04) | flag `countInNetWorth` só é armazenada; futura tela de Patrimônio Total |

Detalhe fino verificado: `sumNetLiquidBalanceByTenant` **não** filtra `account.active`, enquanto
o saldo de abertura do planejamento (`AccountRepository.sumLiquidBalanceByTenant`, seção 8)
filtra `active = true` além do corte de data. São queries irmãs, não idênticas.

## 2. Liquidez vs patrimônio: os dois flags de `Account`

Cada conta tem dois booleanos que respondem perguntas diferentes:

| Flag | Pergunta que responde | Default CHECKING / CASH | Default INVESTMENT / CREDIT_CARD |
|---|---|---|---|
| `countInLiquidBalance` | "isto é dinheiro disponível **agora**?" | `true` | `false` |
| `countInNetWorth` | "isto integra o **patrimônio total**?" | `true` | `true` |

Os defaults vivem em dois lugares coerentes:

- **Backend** (`AccountService.create`): `liquidDefault = (type == CHECKING || type == CASH)`;
  `countInNetWorth` default `true` para qualquer tipo. Ambos podem ser sobrescritos pelo DTO.
- **Frontend** (`features/account/account-form/account-form.ts`): ao trocar o tipo no
  formulário, `countInLiquidBalance` é auto-ajustado (`type === 'CHECKING' || type === 'CASH'`),
  com override manual permitido.

Intuição: um investimento é patrimônio mas não paga o mercado amanhã; um cartão de crédito não é
nem uma coisa nem outra no sentido positivo — ele entra no patrimônio como passivo (transações
EXPENSE negativas na soma) e fica **fora** da liquidez pelo mecanismo da seção 3.

## 3. Ciclo do cartão de crédito

Contas `CREDIT_CARD` têm `CreditCardDetails` com `closingDay` (dia de fechamento) e `dueDay`
(dia de vencimento). A fatura é criada **lazy** na primeira transação do período
(`InvoiceService.getOrCreate`, protegida por `UNIQUE(account, reference_year, reference_month)`
e retry em `REQUIRES_NEW` contra race condition).

### A qual fatura pertence uma compra — `resolveInvoiceMonth`

Código real (`TransactionService.resolveInvoiceMonth`, semântica pós-migration V17):

```java
return purchaseDate.getDayOfMonth() <= closingDay
        ? YearMonth.from(purchaseDate).minusMonths(1)   // encerra a fatura do mês ANTERIOR
        : YearMonth.from(purchaseDate);                 // inicia a fatura do mês corrente
```

A fatura de `referenceMonth = M` **fecha no mês M+1**: `closingDate = dia closingDay de M+1`
(`InvoiceService.createNewInvoice`). Exemplo do próprio código, `closingDay = 2`: compra em
03/06 → fatura de **junho** (que fecha em 02/07); compra em 02/07 → ainda fatura de **junho**.

> Atenção: descrições antigas ("day <= closingDay → mês corrente") refletem a semântica
> pré-V17. O código acima é a verdade desde a correção das faturas legadas (V17).

### Vencimento — `dueDate`

Relativo à `closingDate`:

```
dueDay >= closingDay  →  dueDate = mesmo mês da closingDate, dia dueDay
dueDay <  closingDay  →  dueDate = mês seguinte à closingDate, dia dueDay
```

### Máquina de estados da fatura: `OPEN → CLOSED → PAID`

| Transição | Endpoint | O que faz de fato (`InvoiceService`) |
|---|---|---|
| `close` (OPEN→CLOSED) | `POST /api/invoices/{id}/close` | **Só muda o status.** Zero side effects — a fatura fechada ainda aceita transações novas (cobranças atrasadas). Exige status OPEN. |
| `pay` (CLOSED→PAID) | `POST /api/invoices/{id}/pay { sourceAccountId }` | Numa única `@Transactional`: (1) se total > 0, cria **uma** transação EXPENSE `PAID` na conta de origem, `date = hoje`, descrição `"Pagamento fatura {conta} {MM}/{yyyy}"`; (2) converte em batch (`updateStatusByInvoiceAndStatus`) todas as transações PENDING da fatura para PAID; (3) fatura → PAID. Exige status CLOSED, origem do tenant (404 se não), origem ≠ CREDIT_CARD (não se paga cartão com cartão). |

### Por que cartão tem `countInLiquidBalance = false`

É isso que fecha o ciclo de caixa **sem dupla contagem**. As despesas do cartão vivem na conta
CREDIT_CARD (fora da soma líquida). Quando a fatura é paga, nasce **uma** EXPENSE na conta
corrente de origem — essa única transação é o impacto real no caixa. Se o cartão contasse na
liquidez, cada compra apareceria duas vezes: na parcela (PAID pós-pagamento) e no débito do
pagamento da fatura.

## 4. Regra da data efetiva (`effectiveSortDate`) — casa única

"Quando" uma transação acontece, para fins de filtro de período e ordenação, não é sempre
`transaction.date`:

```
parcela de cartão (installmentGroup != null E invoice != null)  →  invoice.dueDate
todas as demais (inclusive compra avulsa no cartão)             →  transaction.date
```

Racional: a parcela 5/10 de uma compra de janeiro só pesa no bolso no vencimento da fatura dela;
já a compra avulsa de cartão tem uma data única relevante — a da compra.

Onde a regra vive (replicada, deve mudar em conjunto):

| Lugar | Forma |
|---|---|
| `TransactionService.effectiveSortDateDto` | ordenação descendente **em memória** (JPQL não computa a data derivada); desempate por `createdAt` |
| `TransactionRepository.findAllByTenantWithFilters` | mesma regra em JPQL para o **filtro** `startDate/endDate` |
| Queries de dashboard (`sumByTenantAndTypeAndPeriod`, `countByTenantAndPeriod`) | variante `LEFT JOIN t.invoice` (o LEFT é obrigatório — ver debugging-playbook) |
| `frontend .../transaction-timeline/timeline-shared.ts` → `effectiveSortDate()` | réplica TypeScript: `installmentGroupId && invoiceDueDate ? invoiceDueDate : date` |

Fantasmas de recorrência não têm `installmentGroup`, então caem em `date` (= data da ocorrência).
A ausência de uma coluna materializada dessa data é o bloqueio da paginação server-side
(issue #85, `effective_date`, aberta em 2026-07-04).

## 5. Parcelamento

Compra parcelada = `POST /api/transactions` com conta CREDIT_CARD e `totalInstallments = N > 1`.
`TransactionService.create` então:

1. Cria um `InstallmentGroup` (descrição, `totalAmount`, N, conta, categoria).
2. Divide o valor: `installmentAmount = amount / N` com `RoundingMode.HALF_EVEN`, 2 casas —
   **todas as parcelas iguais**. Consequência: `N × parcela ≠ total` quando a divisão não é
   exata (ex.: 100/3 → 3 × 33,33 = 99,99). É o bug aberto **#136** ("divisão de parcelas
   perde/ganha centavos", aberto em 2026-07-04); a soma das parcelas pode divergir do
   `totalAmount` do grupo em até alguns centavos.
3. Para cada `i` em `0..N-1`: fatura = `resolveInvoiceMonth(dataCompra, closingDay).plusMonths(i)`
   via `getOrCreate`; `date` = data da compra **igual em todas as parcelas** (a posição temporal
   vem da fatura — seção 4); `installmentNumber = i+1`.
   Em conta que não é cartão, parcelamento também existe: sem fatura, com `date = dataCompra.plusMonths(i)`.

**Delete por escopo** (`DELETE /api/transactions/{id}?scope=`, enum `DeleteInstallmentScope`):

| Escopo | Candidatas | Proteção |
|---|---|---|
| `SINGLE` | só a transação alvo | nenhuma (apaga mesmo PAID) |
| `THIS_AND_NEXT` | parcelas com `installmentNumber >=` o da alvo | filtra fora as PAID |
| `ALL` | todas as parcelas do grupo | filtra fora as PAID |

Resposta `{ deleted, skippedPaid }` — parcela paga é fato de caixa consumado, nunca apagada em massa.

**Propagate** (`PUT /api/transactions/{id}` com `propagate: string[]`): aplica os campos
listados (`description`, `amount`, `categoryId`, `accountId`, `status`) às parcelas **futuras e
PENDING** do grupo (`installmentNumber >` atual, `findFuturePendingInGroup`). PAID nunca é revertido.

## 6. Transferências double-entry

`POST /api/transfers` não cria entidade Transfer — cria **duas transações espelhadas** ligadas
por um mesmo `transferId` (UUID): uma EXPENSE na origem e uma INCOME no destino, ambas já
`PAID` com mesmo valor e data (`TransactionService.createTransfer`). `DELETE /api/transfers/{transferId}`
remove as duas pernas. É a contabilidade de partidas dobradas mínima: o efeito líquido no caixa
do tenant é zero e cada conta reflete seu lado.

Consequência conhecida: as somas do dashboard (`sumByTenantAndTypeAndPeriod`) excluem apenas
CANCELLED e **não excluem `transferId IS NOT NULL`**, então uma transferência infla `income` e
`expense` do período em valores iguais (saldo correto, brutos errados) — e o pagamento de
fatura tem efeito análogo. Bug aberto **#145** (2026-07-04). Já o planejamento acertou:
`findUnplannedByCycle` exclui `transferId IS NOT NULL` explicitamente.

## 7. Recorrência (RRULE): regra é definição, transação é fato

- **`RecurrenceRule`** é atemporal: uma string RRULE (RFC 5545, expandida pela lib
  `org.dmfs:lib-recur` via `RecurrenceExpander`) + `baseAmount`, `startDate`, conta, categoria,
  status `ACTIVE | CANCELLED`.
- **`Transaction`** é o fato imutável, gravado **somente** após confirmação. Nada é
  materializado antecipadamente.

**Projeção fantasma** (`RecurrenceProjectionService.project`), sempre sobre janela `[from, to]`
— nunca expansão "infinita":

```
fantasma(janela) = expand(rrule) − {ocorrências já materializadas} − {EXDATE}
```

keyed por `(ruleId, data da ocorrência)`; custa 3 queries batched + expansão em memória.
`GET /api/transactions?includeProjected=true` mescla reais + fantasmas (exige `startDate`/`endDate`;
filtro por `invoiceId` não projeta). Fantasma sai com `projected=true`, `id=null`, status PENDING.

**Subconjunto RRULE suportado** (`RecurrenceExpander.isSupported` + `@ValidRrule` → 400):
`FREQ=MONTHLY|YEARLY`, `INTERVAL`, `BYMONTHDAY` (1..31 e `-1` = último dia — resolve fevereiro
28/29 e abril 30 nativamente), `UNTIL`, `COUNT`. Proibidos: `BYDAY`, `BYSETPOS`, `BYWEEKNO`,
`BYYEARDAY`, `BYHOUR`, `BYMINUTE`, `BYSECOND` e FREQ diário/semanal. A validação varre as
**chaves cruas** da string porque o parser lax da lib-recur descarta silenciosamente partes
inválidas no contexto (checar `hasPart` deixaria regra ruim passar).

**Confirmar** (`POST /api/recurrence-rules/{id}/occurrences/{date}/confirm`): materializa via
`TransactionService.materializeFromRule` — reusa o caminho normal, inclusive
`resolveInvoiceMonth`/`getOrCreate` se a conta for cartão. Body opcional `{amount?, date?}` é
override pontual; a regra continua projetando o `baseAmount` no slot canônico
(`recurrenceOccurrence`). Idempotência em duas camadas: guard
`existsByRecurrenceRuleIdAndRecurrenceOccurrence` + índice único parcial
`(recurrence_rule_id, recurrence_occurrence)` no banco → **409** na segunda confirmação.

**Pular** (`.../skip`): grava EXDATE em `recurrence_exceptions`
(`UNIQUE(rule_id, occurrence_date)` → idempotente). O fantasma some naquele mês e volta no seguinte.

Parcelamento de cartão **não** usa recorrência — permanece em `InstallmentGroup` + `Invoice`.

## 8. Matemática do ciclo de planejamento (Modelo A)

Fonte única de cálculo: `BudgetSummaryService` (o DTO é mapeador burro). Racional de design:
separar **caixa real** de **projeção**, com conservadorismo simétrico.

| Grandeza | Fórmula (código real) | Filosofia |
|---|---|---|
| `openingBalance` | `AccountRepository.sumLiquidBalanceByTenant`: `SUM(±amount)` com `status = PAID`, `countInLiquidBalance = true`, `active = true` e **`t.date < startDate`** | Corte de data estrito: só o que já era caixa antes do ciclo. Sem ele, transações PAID do período entrariam no opening **e** em realizados/avulsas (dupla contagem). `<` e não `<=` porque o dia `startDate` pertence ao ciclo. |
| `currentBalance` | `opening + realizadosPAID + avulsasPAID(receita) − realizadosPAID − avulsasPAID(despesa)` — realizado = item `REALIZED` **cuja transação vinculada está PAID** | Caixa real **agora**. Item REALIZED com transação PENDING não conta. |
| `availableToSpend` | `projectedBalance + avulsasTotal(receita) − avulsasTotal(despesa)`, onde `projectedBalance = opening + planejadoReceita − planejadoDespesa` (itens ativos = todos exceto `SKIPPED`) | Projeção conservadora-simétrica, **independente de PAID/PENDING**: receita só ajuda quando lançada no sistema; despesa pesa assim que existe. |
| `dailyAllowance` | `availableToSpend / diasRestantes` com `RoundingMode.FLOOR`, 2 casas; `0` se `availableToSpend <= 0` ou `diasRestantes <= 0`; `null` se o ciclo não está `OPEN` | FLOOR (não HALF_UP): errar para baixo no "quanto posso gastar por dia". |

`unplannedIncome/Expense` no DTO são os **totais** (PAID+PENDING) das avulsas, para exibição.
Datas do ciclo derivam de `tenants.budget_cycle_start_day` (dia 1 → mês calendário; dia N →
de N do mês anterior até N−1 do atual). Ao abrir um ciclo, itens `RECURRING` vêm da projeção
on-the-fly das regras ativas (seção 7) e itens `INSTALLMENT` das parcelas de cartão do período
(`findInstallmentsByTenantAndInvoiceMonth`, que filtra por `referenceYear/Month` da fatura, não
por `dueDate`, para não perder cartões com `dueDay` fora do período).

## 9. Modelo de multi-tenancy

O projeto usa **schema compartilhado com discriminador**: uma única base, `tenant_id UUID NOT NULL`
+ FK em toda tabela de negócio desde a V1, e **escopo aplicado na camada de aplicação** — todo
repository de negócio tem variantes `...ByTenant`/`...AndTenant` (`findByIdAndTenant`,
`findAllByTenantWithFilters`, `sumNetLiquidBalanceByTenant`…), e todo service resolve o tenant a
partir do usuário autenticado. Vazamento de tenant é o bug mais grave possível aqui (invariante:
ver architecture-contract; como **provar** o isolamento: proof-toolkit).

Alternativas clássicas e onde o projeto está:

| Modelo | Trade-off | Status aqui |
|---|---|---|
| Schema compartilhado + FK + escopo em query (atual) | mais barato e simples; segurança depende de disciplina em **cada** query | implementado |
| Schema-per-tenant | isolamento físico forte; migrations × N, conexões × N | não adotado |
| Postgres Row-Level Security (RLS) | defesa em profundidade no banco (uma query sem filtro deixa de vazar) | candidato avaliado na issue **#116** (aberta em 2026-07-04) — detalhes em `fintech-core-research-frontier` |

## 10. Enums do domínio (código verificado em `domain/enums/`)

| Enum | Valores | Significado de cada estado |
|---|---|---|
| `AccountType` | CHECKING, CREDIT_CARD, INVESTMENT, CASH | corrente, cartão de crédito (única com faturas/`CreditCardDetails`), investimento, dinheiro vivo |
| `CardBrand` | VISA, MASTERCARD, ELO, AMEX, HIPERCARD | bandeira do cartão (metadado) |
| `TransactionType` | INCOME, EXPENSE | sinal na soma de saldo: `+` / `−` |
| `TransactionStatus` | PENDING, PAID, CANCELLED | compromisso (fora do caixa) / caixa consumado (única que soma em saldo) / anulada (fora de tudo) |
| `InvoiceStatus` | OPEN, CLOSED, PAID | aceita compras / fechada mas ainda aceita atrasadas / paga (transações convertidas + débito na origem) |
| `DeleteInstallmentScope` | SINGLE, THIS_AND_NEXT, ALL | seção 5 |
| `UserRole` | ADMIN, MEMBER | admin do tenant (convites, membros, settings) / membro comum |
| `InvitationStatus` | PENDING, ACCEPTED, EXPIRED | convite aguardando / aceito (virou User MEMBER) / expirado |
| `BudgetCycleStatus` | OPEN, ENDED, CLOSED | ciclo corrente / período encerrado mas ajustes permitidos (V15) / fechado definitivo. Máximo **um** OPEN por tenant (unique parcial) |
| `BudgetItemSource` | MANUAL, RECURRING, INSTALLMENT | criado à mão / vindo da projeção de `RecurrenceRule` / vindo de parcela de cartão |
| `BudgetItemStatus` | PENDING, REALIZED, SKIPPED | aguardando / vinculado a transação (caixa só se ela for PAID — seção 8) / ignorado no ciclo (fora de todas as somas) |
| `RecurrenceStatus` | ACTIVE, CANCELLED | regra projeta fantasmas / regra não projeta (reativável via `PATCH .../reactivate`) |

> Nota: `InvitationStatus` e o estado `ENDED` de `BudgetCycleStatus` existem no código mas
> ainda não constam na tabela de `domain.md` (verificado em 2026-07-04) — o código é a verdade.

---

## Proveniência e manutenção

Fatos extraídos em **2026-07-04** dos arquivos (todos sob `/home/sergio/fintech-core/`):
`backend/src/main/java/com/fintech/api/repository/{TransactionRepository,AccountRepository}.java`,
`service/{TransactionService,InvoiceService,BudgetSummaryService,BudgetCycleService,AccountService}.java`,
`service/recurrence/{RecurrenceProjectionService,RecurrenceExpander}.java`, `domain/enums/*.java`,
`frontend/src/app/features/transaction/transaction-timeline/timeline-shared.ts`,
`frontend/src/app/features/account/account-form/account-form.ts`, `summary.md`, `domain.md`,
`database-schema.md` e issues GitHub #85, #116, #136, #145 (todas OPEN em 2026-07-04).

Re-verificação de uma linha por fato volátil:

```bash
grep -n "HALF_EVEN" backend/src/main/java/com/fintech/api/service/TransactionService.java   # divisão de parcelas (#136)
grep -n "minusMonths" backend/src/main/java/com/fintech/api/service/TransactionService.java # resolveInvoiceMonth pós-V17
grep -n "dueDay >= closingDay" backend/src/main/java/com/fintech/api/service/InvoiceService.java
grep -rn "countInNetWorth" backend/src/main/java --include=*.java                            # ainda sem consumidor além do CRUD?
grep -n "transferId" backend/src/main/java/com/fintech/api/repository/TransactionRepository.java  # dashboard ainda não exclui (#145)?
gh issue view 136 --json state -q .state ; gh issue view 145 --json state -q .state ; gh issue view 116 --json state -q .state
cat backend/src/main/java/com/fintech/api/domain/enums/BudgetCycleStatus.java               # ENDED ainda presente
```

Se `resolveInvoiceMonth`, a divisão de parcelas, o Modelo A ou os flags de conta mudarem,
esta skill deve ser atualizada na mesma entrega (roteie pela `fintech-core-change-control`).
