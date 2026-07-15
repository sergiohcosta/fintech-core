# Fase 3 — Cluster C: double-entry e agregados do dashboard

> Campanha de saneamento (auditoria 2026-07). Escopo: **#138, #145, #151**. Decisões do dev:
> (1) fazer a migração V22 `paid_invoice_id` agora; (2) #151 é bug → filtrar `active=true`.
> Skill: `fintech-core-bug-backlog-campaign` (fase 3).

## #138 — update/delete aceita perna de transferência (quebra double-entry)

**Causa-raiz:** `TransactionService.update`/`delete` não têm guard para `transferId != null`. No
`delete`, a perna cai no ramo `installmentGroup == null` e é apagada sozinha, deixando a irmã
órfã (dinheiro criado/sumido). Só `deleteTransfer` remove o par.

**Reprodução (`TransactionServiceTest`, unit):** (a) `update` de `amount` numa perna passa hoje;
(b) `delete` SINGLE de uma perna apaga só uma. Após o fix, ambos lançam.

**Solução (rejeitar):** guard no início de `update` e `delete`: `transferId != null` →
`IllegalStateException` (mapeado a 400/409 pelo `GlobalExceptionHandler`), mensagem apontando
`DELETE /api/transfers/{transferId}`. Double-entry é invariante: as pernas nascem juntas
(`createTransfer`) e morrem juntas (`deleteTransfer`); mutação unilateral corrompe o saldo.

**Frontend (defesa em profundidade):** ocultar/desabilitar editar+excluir individual em linhas
com `transferId` na lista de transações, apontando para a exclusão de transferência.

## #145 — transferências e pagamento de cartão inflam income/expense

**Causa-raiz:** `sumByTenantAndTypeAndPeriod` e `countByTenantAndPeriod` (agregados de
income/expense/contagem do dashboard) não excluem nem transferências nem o EXPENSE de pagamento
de fatura. Transferência infla income+expense em igual valor; o pagamento de cartão é contado no
mês do pagamento **além** das compras no mês do `dueDate` → despesa 2× (dupla contagem).

**Duas correções independentes:**

1. **Transferências (sem migration):** `AND t.transferId IS NULL` nas duas queries de agregação.
   O padrão correto já existe no próprio arquivo (`findUnplannedByCycle` exclui transferId).

2. **Pagamento de fatura (migração V22 — decisão aprovada):** hoje NADA identifica a transação
   de pagamento (cerca: proibido detectar por `description`). Solução:
   - **Migration V22** — coluna FK nullable `paid_invoice_id` em `transactions` + índice único
     parcial `(paid_invoice_id) WHERE paid_invoice_id IS NOT NULL`. O índice garante **1
     pagamento por fatura** no schema (reforça o #139). Seeds aplicados são imutáveis e rodam
     antes; a coluna nasce nullable e os dados antigos ficam NULL (semanticamente correto — não
     eram pagamentos).
   - `Transaction.paidInvoice` (ManyToOne LAZY) preenchido em `InvoiceService.pay` no EXPENSE de
     pagamento.
   - Agregados do dashboard excluem também `AND t.paidInvoice IS NULL`.

   **Escopo do exclude:** APENAS os agregados de período (`sumByTenantAndTypeAndPeriod`,
   `countByTenantAndPeriod`). **NÃO** excluir do `sumNetLiquidBalanceByTenant` — o pagamento é
   saída real de caixa da conta de origem e deve rebaixar o `totalAccountBalance`.

## #151 — totalAccountBalance inclui contas arquivadas (veredicto: bug)

**Causa-raiz:** `sumNetLiquidBalanceByTenant` não filtra `account.active`; já
`AccountRepository.sumLiquidBalanceByTenant` (openingBalance do ciclo) filtra → dois "saldos
líquidos" divergentes para o mesmo tenant.

**Solução (decisão do dev):** `AND t.account.active = true` em `sumNetLiquidBalanceByTenant`.
Arquivar é o soft delete da conta; um "disponível agora" que soma conta arquivada contradiz
`countInLiquidBalance`. Critério de pronto: o saldo do dashboard bate com o saldo-base do ciclo
para o mesmo tenant/instante.

**Reprodução (`DashboardServiceTest` ou repo integração):** conta com 1.000 PAID, arquivar
(`active=false`), dashboard ainda mostra 1.000 hoje.

## Ordem e critério de pronto

1. `#138` (unit repro → guards + frontend).
2. `#151` (repro → filtro active).
3. `#145` (repro transferências → exclude; migration V22 → paidInvoice → exclude pagamento).

- Perna de transferência imutável isoladamente (400/409 coberto por teste); UI oculta ações.
- Dashboard sem income/expense fictícios de transferência nem pagamento de cartão duplicado.
- `totalAccountBalance` exclui contas arquivadas e bate com o opening do ciclo.
- Migration V22 aplicada; índice único parcial criado; `zero paid_invoice_id` órfão.
- `./scripts/test-summary.sh` verde (backend + frontend).

## Dataset (regra inviolável — mudança de schema)

Migration V22 adiciona coluna nullable `paid_invoice_id`. O seed `V13` não cria pagamentos de
fatura → nenhum INSERT existente precisa do campo (NULL correto). `seed_base.sql`: idem, salvo
se algum teste de integração passar a exigir uma transação de pagamento marcada (avaliar na
execução). `docs/http/seed-dataset.http`: sem endpoint novo (pay já existe).
