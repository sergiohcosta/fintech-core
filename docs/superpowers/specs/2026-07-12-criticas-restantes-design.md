# Críticas restantes — #148 (frontend) + #152 (planejamento)

> Campanha de saneamento (auditoria 2026-07). Escopo: as 2 críticas fora dos clusters de dinheiro
> e segurança. Subsistemas **isolados** (frontend puro × backend de planejamento) → uma worktree,
> um PR cumulativo, zero risco de conflito. Skill: `fintech-core-bug-backlog-campaign` (fases 5/4).

## #148 — parsing de valor com `.` e data com `toISOString`

**Causa-raiz (`transaction-form.ts`):**
1. `onAmountInput`: `raw.replace(/[^\d,]/g, '')` remove SEMPRE o `.`. Digitar `1234.56` (ponto
   decimal, comum em teclado/colagem) → `123456` → R$ 123.456,00 silencioso.
2. `toDateString`: `date.toISOString().split('T')[0]`. `toISOString` converte para UTC; em fuso de
   offset **positivo**, a meia-noite local vira D−1 → data gravada com um dia a menos.

**Reprodução:** util pura nova (`transaction-form.utils.ts`) com testes de tabela:
- `parseAmountInput`: `"1234.56"→1234.56`, `"1.234,56"→1234.56`, `"1234,56"→1234.56`,
  `"1.234"→1234` (milhar), `"1234"→1234`, `"R$ 1.234,56"→1234.56`.
- `formatLocalDate`: uma `Date` de meia-noite local → `yyyy-MM-dd` do dia LOCAL (nunca D−1).

**Solução:**
- `parseAmountInput(raw)`: remove tudo exceto `\d . ,`. Se há `,` → `,` é decimal, `.` são
  milhares (remove `.`, troca `,`→`.`). Senão → `.` é decimal **apenas** se houver um único `.`
  seguido de 1–2 dígitos no fim (`1234.56`); caso contrário é milhar (`1.234`→`1234`).
  **Derivação:** desambiguação pt-BR × ponto-decimal pela posição/quantidade de dígitos — o caso
  ambíguo `1.234` resolve como milhar (comportamento pt-BR), `1234.56` como decimal.
- `formatLocalDate(date)`: `getFullYear/getMonth/getDate` + `padStart` — sem passar por UTC.
- `transaction-form.ts`: `onAmountInput` usa `parseAmountInput`; `toDateString` delega a
  `formatLocalDate`. Lógica pura fora do componente (padrão do projeto — testável sem TestBed).

**Cerca:** o modo fórmula (`raw.startsWith('=')`, avaliado no blur) permanece intocado.

## #152 — `syncInstallments` apaga itens INSTALLMENT REALIZED

**Causa-raiz (`BudgetCycleService.syncInstallments`):** deleta TODOS os itens `source=INSTALLMENT`
(`deleteAll(toRemove)`) — inclusive REALIZED e vinculados a transação — e recria tudo PENDING via
`populateInstallmentItems`. Um realizado (parcela já vinculada a transação PAID) vira PENDING e
perde o vínculo → `realizedExpense` muda retroativamente. **REALIZED é fato consumado do ciclo;
sincronização é reconciliação de PREVISTOS** — apagar fato numa reconciliação é corrupção de
histórico (mesmo princípio das migrations imutáveis).

**Reprodução (`BudgetCycleServiceTest`, integração):** realizar uma parcela (vincular tx PAID a um
item INSTALLMENT), sincronizar → hoje o realizado vira PENDING e o vínculo se perde.

**Solução (sync aditivo):**
- Remover apenas itens INSTALLMENT `status=PENDING` **e** `transaction=null` (projeções
  descartáveis).
- Preservar REALIZED e vinculados; coletar os `InstallmentGroup` já cobertos por eles.
- `populateInstallmentItems` ganha `Set<UUID> skipGroupIds` e **pula** grupos já cobertos (sem
  duplicata). O caller de abertura (`open`) passa conjunto vazio (comportamento inalterado).

**Cerca:** não mexer no cálculo do `total` da parcela nem na `expectedDate` (vencimento da fatura).

## Ordem e critério de pronto

1. `#152` (backend, integração repro → sync aditivo).
2. `#148` (frontend, util pura repro → parse/format + fiação no componente).

- `soma`/vínculo de REALIZED preservados após sync; só previstos PENDING regenerados; sem duplicata.
- Tabela de parsing passa; data formatada sem UTC.
- `./scripts/test-summary.sh` verde (backend + frontend).

## Dataset

Sem mudança de schema/seed. `dataset.md` não exige atualização.
