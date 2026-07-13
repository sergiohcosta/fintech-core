# Fase 4 — Cluster D: recorrência ↔ planejamento

> Campanha de saneamento (auditoria 2026-07). Escopo (altas): **#141, #140, #146**. Fundo comum:
> os contratos entre `RecurrenceRule`, `Transaction` e `BudgetItem` não são validados nas bordas.
> Decisão do dev (#140): orquestrar a vinculação no lado do planejamento. Skill:
> `fintech-core-bug-backlog-campaign` (fase 4). Ler antes:
> `docs/superpowers/specs/2026-06-25-motor-de-recorrencia-nucleo-design.md`.

**Ordem obrigatória:** #141 → #140 (a vinculação automática do #140 passa pelos guards
unificados do #141) → #146 (independente).

## #141 — `link()` sem os guards de `realize()`

**Causa-raiz:** `BudgetItemService.link` usa `findByTransactionAndCycleNot` (só bloqueia vínculo
em OUTRO ciclo — a mesma transação pode ir para 2+ itens do MESMO ciclo → dupla contagem), não
checa ciclo OPEN, não checa compatibilidade de tipo e não sincroniza `item.setAmount(tx)` —
tudo que `realize()` já faz. Dois caminhos de escrita para o mesmo estado com validações
diferentes é a definição do bug de invariante.

**Solução (unificar o caminho):** extrair dois privados e usar em `link` **e** `realize`:
- `requireOpenAndPending(item)`: ciclo OPEN + item PENDING.
- `attachExistingTransaction(item, tx)`: dupla-vinculação via `findByTransaction` (qualquer
  ciclo), compatibilidade de tipo (`tx.type == item.type`), `item.setAmount(tx.amount)`,
  `status = REALIZED`, `setTransaction(tx)`.

`link` = `requireOpenAndPending` + load tx (tenant-scoped) + `attachExistingTransaction`.
`realize` = `requireOpenAndPending` + guard INSTALLMENT + resolve tx (existente ou cria PAID) +
`attachExistingTransaction`. O caminho de criação (tx nova) passa nos guards trivialmente
(tipo = item.type; não vinculada ainda).

**Reprodução (`BudgetItemServiceTest`, unit — 4 testes):** (a) mesma tx em 2 itens do mesmo
ciclo → 2º `link` lança; (b) `link` com tipo incompatível lança; (c) `link` em ciclo CLOSED
lança; (d) `link` sincroniza `item.amount` com `tx.amount`.

## #140 — confirmar recorrência não vincula ao BudgetItem (dupla contagem)

**Causa-raiz:** `materializeFromRule` cria a transação com `recurrenceRule`+`recurrenceOccurrence`
mas não procura o `BudgetItem` RECURRING correspondente no ciclo aberto. A transação cai em
`findUnplannedByCycle` como avulsa e `BudgetSummaryService` soma o item planejado **E** a avulsa.

**Solução (decisão: orquestrar no planejamento):** `TransactionService` continua sem conhecer
planejamento. Novo `BudgetItemService.linkRecurringOccurrence(tenant, rule, occurrence, txId)`:
1. acha o ciclo OPEN do tenant; se não houver → retorna (nada a vincular);
2. acha o `BudgetItem` com `source=RECURRING`, `recurrenceRule=rule`,
   `recurrenceOccurrenceDate=occurrence` nesse ciclo; se não houver → retorna;
3. carrega a tx (tenant-scoped) e vincula pelo caminho unificado do #141.
`RecurrenceRuleService.confirmOccurrence` chama esse método **após** `materializeFromRule`.
A tx nasce PENDING — o modelo já suporta item REALIZED com tx PENDING (`transactionStatus`,
ver summary.md). Direção de dependência: `RecurrenceRuleService → BudgetItemService` (sem ciclo).

**Reprodução (`BudgetSummaryServiceTest` ou serviço):** regra vira item RECURRING no ciclo;
confirmar a ocorrência; `availableToSpend`/expense não caem 2× (item vinculado, não avulsa
duplicada).

## #146 — confirmar/pular não valida slot, EXDATE nem status

**Causa-raiz:** `confirmOccurrence`/`skipOccurrence` só checam duplicidade (confirm) e
idempotência (skip). Não validam: regra `ACTIVE`; `occurrence` ∈ expansão da RRULE; (confirm)
`occurrence` ∉ EXDATE. Confirmar um slot inexistente (14/07 numa regra `BYMONTHDAY=15`) convive
com o fantasma real de 15/07 → pagamento 2× (o índice único não protege: datas diferentes).

**Solução (antes de materializar/pular):**
- regra `ACTIVE` (senão `IllegalStateException` → 422);
- `occurrence` ∈ expansão da RRULE na janela do mês da data (reusa `RecurrenceProjectionService`
  — nunca expandir sem janela);
- confirm: `occurrence` ∉ `recurrence_exceptions` (EXDATE).

**Derivação:** a projeção subtrai materializadas por data EXATA — logo o conjunto de datas
confirmáveis tem que ser exatamente o conjunto projetável, ou a subtração nunca converge.

**Reprodução (`RecurrenceRuleServiceTest`, unit — 3 casos):** (a) confirmar data fora da
expansão → lança; (b) confirmar data em EXDATE → lança; (c) confirmar/pular em regra CANCELLED →
lança.

## Critério de pronto

- Guards unificados de `link`/`realize` (4 testes); tx em 2 itens do mesmo ciclo é rejeitada.
- Confirmar recorrência vincula o item RECURRING do ciclo → sem avulsa duplicada.
- Slot inexistente/EXDATE/regra CANCELLED rejeitados nos três casos.
- `./scripts/test-summary.sh` verde (backend + frontend).

## Dataset

Sem mudança de schema. Sem novo endpoint. `dataset.md` não exige atualização (comportamento de
serviço; o seed V20 já traz regra + EXDATE de exemplo).
