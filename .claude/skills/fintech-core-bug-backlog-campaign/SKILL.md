---
name: fintech-core-bug-backlog-campaign
description: >-
  Campanha executável e gateada para sanear o backlog de bugs da auditoria de 2026-07 do
  fintech-core (issues #135 a #152) mais o bloqueio arquitetural effective_date (#85).
  Fases por cluster de causa-raiz, com teste de reprodução obrigatório antes do fix, gates
  observáveis e menu de soluções ranqueado. Use quando a tarefa for: "corrigir bug #1xx",
  "atacar o backlog de bugs", "campanha de saneamento", "bugs da auditoria", bug de fatura,
  bug de parcelamento, bug de planejamento, bug de recorrência, bug de dashboard, bug de
  segurança do login, CSV formula injection, pagamento duplicado de fatura, centavos de
  parcela, effective_date, paginação server-side, ou planejar em que ordem corrigir os bugs
  abertos. NÃO cobre bug novo sem issue neste intervalo (use fintech-core-debugging-playbook)
  nem o processo de mudança em si (use fintech-core-change-control).
---

# Campanha de Saneamento — Backlog de Bugs da Auditoria 2026-07

> **✅ PROGRESSO (2026-07-14): Fases 1–4 CONCLUÍDAS e mergeadas em `develop`.** Todas as 6 críticas
> e 7 altas fechadas: #135 #136 #137 #138 #139 #140 #141 #143 #144 #145 #146 #148 #151 #152.
> Migration V22 (`paid_invoice_id`) aplicada. PR cumulativo #133 `develop→main` aberto.
> **Restante:** Fase 5 (médias frontend #142 #147 #149 #150 + infra #153) e Fase 6 (#85 —
> agora desbloqueada: fases 1 e 3 mergeadas). Detalhe de cada fix: `git log`, specs em
> `docs/superpowers/specs/2026-07-12-*` e memória `project_status`.

> **Estado datado (2026-07-04):** issues #135–#152 abertas + #85 (estrutural). Todas foram
> conferidas contra o código em 2026-07-05 e **todos os bugs descritos existem no código atual**.
> Issues mudam — antes de iniciar qualquer fase, re-verifique (requer GitHub CLI autenticada:
> `gh auth status`):
>
> ```bash
> gh issue list -R sergiohcosta/fintech-core --state open --limit 50
> gh issue view <n> -R sergiohcosta/fintech-core
> ```
>
> Issue já fechada → pule o item e siga a fase. **PROIBIDO** `gh issue edit/close` — quem fecha
> issue é o desenvolvedor; ao concluir, apenas *sugira* o fechamento no relatório.

## Quando NÃO usar

- **Bug novo, fora deste backlog** (sintoma sem issue #135–#152) → `fintech-core-debugging-playbook`
  (triagem sintoma→causa) e depois `fintech-core-change-control` para o fix.
- **Dúvida de processo** (como criar worktree, migration, spec, commit) → `fintech-core-change-control`.
  Esta skill *referencia* o processo, não o redefine.
- **Teoria de domínio** (por que saldo só conta PAID, ciclo de fatura, Modelo A) → `fintech-domain-reference`.
- **Provar um invariante sem corrigir nada** (ex.: demonstrar a race de `getOrCreate`) →
  `fintech-core-proof-and-analysis-toolkit`.

## Regras da campanha (valem para todas as fases)

1. **Toda fase roteia pelo change-control.** Fix multi-arquivo → spec/plano SDD aprovado antes de
   codar; worktree própria por fase (`git worktree add -b fix/fase-N ...` a partir de `develop`
   atualizada); merge em `develop` só com suíte verde; PR cumulativo para `main`.
2. **Reproduzir antes de corrigir.** Cada bug entra com um teste que FALHA no código atual.
   Se o teste de reprodução passar de primeira → o bug não existe mais ou você reproduziu errado;
   releia a issue com `gh issue view` e o código antes de tocar em qualquer coisa.
3. **Baseline verde obrigatório** antes de abrir a fase (`./scripts/test-summary.sh`); falha
   pré-existente → pare e abra issue ANTES. Regra completa e racional: `fintech-core-validation-and-qa`.
4. **Cercas globais (caminhos errados já conhecidos):**
   - Migrations/seeds aplicados são imutáveis — schema muda só via nova migration (V22+).
     Regra completa: `fintech-core-change-control`.
   - **NÃO** "corrigir" número errado de dashboard/fatura só no frontend. O backend é a fonte;
     defesa em profundidade vale para dados tanto quanto para roles.
   - Como rodar a suíte sem cair nos gotchas (`npx vitest` cru, suíte backend >7 min):
     `fintech-core-validation-and-qa` (proibições) e `fintech-core-debugging-playbook` (sintomas).
   - **NÃO** deduzir semântica por `description` de transação (ex.: detectar pagamento de fatura
     por string). Semântica entra no schema, nunca em parsing de texto.
5. **Sucesso é mensurável.** Cada fase termina com os testes de reprodução (agora passando)
   commitados como regressão + suíte completa verde. "Parece ok" não fecha fase.

## Mapa de clusters (causa-raiz, ordenado por risco)

| Fase | Cluster | Issues | Risco |
|---|---|---|---|
| ~~1~~ ✅ | A — Aritmética e concorrência de dinheiro (fatura/parcelamento) | ~~#135 #136 #137 #139~~ | Perda financeira direta |
| ~~2~~ ✅ | B — Segurança | ~~#143 #144~~ | Execução de código no cliente / brute-force |
| ~~3~~ ✅ | C — Integridade double-entry e agregados do dashboard | ~~#138 #145 #151~~ | Saldos mentem |
| ~~4~~ ✅ | D — Recorrência ↔ Planejamento (contratos entre subsistemas) | ~~#140 #141 #146 #152~~ (feitos) · #147 #142 (médias, pendentes) | Dupla contagem, estados inválidos |
| 5 | E — Frontend/UX de erro | ~~#148~~ #149 #150 | Dados errados na entrada, sessão zumbi |
| 6 | F — Estrutural: `effective_date` (#85) | #85 | Bloqueio de paginação/relatórios |

Ordem obrigatória apenas para 1 e 2 (dinheiro e segurança primeiro). 3–5 podem ser paralelas em
worktrees distintas se forem agentes distintos. A fase 6 entra **depois** de 1 e 3 (mexem nos
mesmos arquivos: `TransactionService`, `InvoiceService`, `TransactionRepository`) — fazer antes
gera conflito de merge e retrabalho no backfill.

---

## Fase 1 — Cluster A: dinheiro (fatura e parcelamento)

**Pré-condições:** baseline verde; worktree própria; issues #135 #136 #137 #139 relidas via `gh`.
Todas mexem em `backend/src/main/java/com/fintech/api/service/InvoiceService.java` e/ou
`TransactionService.java` → **uma spec/plano SDD para a fase inteira**, aprovado antes de codar.

### 1.1 — #135 pagamento soma estorno como despesa

**Local confirmado:** `TransactionRepository.sumAmountByInvoice` faz `SUM(t.amount)` sem `CASE`
por tipo. Consumido por `InvoiceService.pay` (valor do EXPENSE de pagamento) e `buildDTO` (total
exibido da fatura).

**Reprodução (antes do fix):** em `InvoiceServiceTest`, cenário de fatura com EXPENSE 500 +
INCOME 100 (estorno); asserte que o pagamento criado vale 400. Rode:

```bash
./mvnw -f backend/pom.xml test -Dtest=InvoiceServiceTest
```

**Gate:** o teste deve falhar com total 600. *Se você vir 400 em vez de 600 → a query já foi
corrigida; confira `git log develop -- backend/src/main/java/com/fintech/api/repository/TransactionRepository.java` e pule para 1.2.*

**Solução (única razoável):** `SUM(CASE WHEN t.type = :expenseType THEN t.amount ELSE -t.amount END)`
— a mesma álgebra do saldo de conta (`sumNetLiquidBalanceByTenant` já usa o padrão espelhado).
**Derivação:** fatura é passivo; estorno (INCOME) reduz o passivo — teoria de saldo/fatura:
ver `fintech-domain-reference`. **Atenção:** com estorno > compras o total fica negativo; o
guard `total > 0` em `pay` já pula a criação do EXPENSE — mantenha e cubra com teste
(fatura só-estorno → paga sem criar transação de pagamento).

**Cercado:** NÃO corrigir só no `pay` deixando `buildDTO` com a soma antiga — o total exibido
e o total pago divergiriam.

### 1.2 — #136 divisão de parcelas perde/ganha centavos

**Local confirmado:** `TransactionService.create` —
`dto.amount().divide(BigDecimal.valueOf(installments), 2, RoundingMode.HALF_EVEN)` e todas as N
parcelas recebem o mesmo valor.

**Reprodução:** teste em `TransactionServiceTest` com o invariante
`soma(parcelas) == InstallmentGroup.totalAmount` para (100.00, 3) e (1000.00, 7):

- 100/3 → 33,33 × 3 = 99,99 (falta 1 centavo)
- 1000/7 → 142,86 × 7 = 1.000,02 (sobram 2 centavos — o erro inverte o sinal)

```bash
./mvnw -f backend/pom.xml test -Dtest=TransactionServiceTest
```

**Gate:** ambos os casos devem falhar. *Se (100,3) passar mas (1000,7) falhar → alguém corrigiu
com truncamento (DOWN) em vez de fechamento exato; a correção completa continua necessária.*

**Menu de soluções (derive antes de escolher):**

1. **Última parcela absorve o resto** — `parcela = total.divide(N, 2, DOWN)` (ou HALF_EVEN) para
   as N−1 primeiras; última `= total − (N−1)×parcela`. Invariante de soma exato por construção.
   Desvio máximo da última em relação às demais: até N−1 centavos com DOWN (ex.: 1000/7 →
   6×142,85 + 1×143,10... conta: 1000 − 6×142,85 = 142,90 → 5 centavos a mais). Simples, 1 linha
   de diferença, é o que a issue pede. **Recomendada.**
2. **Round-robin (algoritmo do resto distribuído)** — `base = total.divide(N, 2, DOWN)`;
   `resto_centavos = (total − N×base) × 100`; as primeiras `resto_centavos` parcelas ganham
   +0,01. Diferença máxima entre quaisquer duas parcelas: exatamente 1 centavo. Mais justo,
   um pouco mais de código. Escolha se o desenvolvedor preferir a estética contábil.
3. ~~Manter HALF_EVEN e "ajustar" `totalAmount` do grupo~~ — **cercado**: o total é o fato
   (valor da compra); as parcelas é que derivam dele, nunca o contrário.

**Cercado adicional:** `update` com `propagate: ["amount"]` reaplica um valor uniforme às
parcelas futuras e pode recriar o desvio — está fora do escopo desta issue; se detectar,
anote no relatório e sugira issue nova (não conserte de carona sem spec).

### 1.3 — #137 `DateTimeException` para closingDay/dueDay > dias do mês

**Local confirmado:** `InvoiceService.createNewInvoice` usa `withDayOfMonth(closingDay)` /
`withDayOfMonth(dueDay)` sem capping; `CreditCardDetailsDTO` não valida faixa. Espelho no
frontend: `installment-preview.ts` (aritmética com `Date.setMonth`, overflow análogo).

**Reprodução:** teste em `InvoiceServiceTest`: cartão `closingDay=31`, fatura fechando em
fevereiro → esperar hoje `DateTimeException` ("Invalid date FEBRUARY 31"). Na API isso vira 500
e derruba a criação da transação inteira.

**Gate:** *se o teste criar a fatura sem exceção → verifique se o mês testado realmente tem
menos de 31 dias (fevereiro do ano do teste) antes de concluir que já foi corrigido.*

**Menu de soluções:**

1. **Capping local** — `date.withDayOfMonth(Math.min(day, date.lengthOfMonth()))` num helper
   privado usado nos dois pontos (closing e due). **Derivação:** semântica desejada é "dia 31 ou
   o último dia do mês, o que vier primeiro" — igual ao comportamento de `BYMONTHDAY=-1` já
   documentado no motor de recorrência. **Recomendada.**
2. `TemporalAdjusters.lastDayOfMonth()` condicionado — equivalente, mais verboso.
3. Bean Validation limitando 1–28 no DTO — **cercada como solução única**: quebra cartões reais
   (fechamento dia 30/31 existe no mundo) e não conserta dados já persistidos.

**Obrigatório no mesmo pacote:** espelhar o capping no preview do frontend
(`frontend/src/app/features/transaction/transaction-form/installment-preview.ts` — `calcDueDate`
e a base do mês da fatura) + teste Vitest da util (arquivo é lógica pura, roda sem TestBed).
**Atenção:** a comparação `dueDay >= closingDay` (mesmo mês vs. mês seguinte) continua sobre os
dias **configurados**, não sobre as datas já capadas — capar antes de comparar muda o mês da
fatura em fevereiro.

### 1.4 — #139 pagamento duplicado de fatura (concorrência)

**Local confirmado:** `InvoiceService.pay` é read-then-write do status; nenhuma entidade do
domínio tem `@Version` (verificado por grep em 2026-07-05); o EXPENSE de pagamento não tem
constraint única.

**Reprodução:** teste de concorrência (2 threads chamando `pay` na mesma fatura CLOSED, latch
para alinhar a largada) — precisa de banco real (Postgres local de pé), não H2/mock, porque a
disputa é no isolamento do banco. Alternativa mínima aceitável: teste `@SpringBootTest` com
`ExecutorService` de 2 threads; asserte que existe exatamente 1 transação de pagamento ao final.

**Gate:** *se os 2 POSTs simultâneos resultarem em 1 pagamento já hoje → provavelmente o timing
não colidiu (falso verde). Rode ≥20 repetições antes de concluir que não reproduz; a janela é
pequena mas real.*

**Menu de soluções (compare antes de escolher — a issue lista as três):**

1. **UPDATE condicional + checagem de linhas afetadas** — `@Modifying UPDATE Invoice i SET
   i.status = PAID WHERE i.id = :id AND i.status = CLOSED` executado ANTES de criar o EXPENSE;
   0 linhas afetadas → `IllegalStateException` (409/422). **Derivação:** o UPDATE é atômico no
   banco em READ_COMMITTED; o segundo concorrente afeta 0 linhas e aborta antes de criar o
   pagamento. Sem migration, sem lock retido, exige reordenar o método (status primeiro, efeitos
   depois, tudo na mesma `@Transactional`). **Recomendada.**
2. **Lock pessimista** — método de repositório com `@Lock(LockModeType.PESSIMISTIC_WRITE)` para
   carregar a fatura em `pay`. Serializa os concorrentes (o segundo espera, relê PAID, falha no
   guard existente). Simples de raciocinar; custo: conexão retida durante todo o `pay` (que faz
   batch update + insert). Aceitável no volume atual.
3. **`@Version` na Invoice** — exige migration nova coluna + tratamento de
   `OptimisticLockException` + política de retry. Mais invasivo; só se o desenvolvedor quiser
   introduzir optimistic locking como padrão do domínio (decisão de ADR, não de bugfix).

**Reforço opcional (casa com a fase 3):** constraint única no banco ligando o EXPENSE de
pagamento à fatura quitada (ver decisão do marcador em 3.2) dá rede de segurança final no schema.

**Fechamento da fase 1:** os 4+ testes de reprodução passam; `./scripts/test-summary.sh backend`
verde; merge em `develop` conforme change-control. Critério mensurável: para todo (total, N)
testado `soma == total`; fatura com estorno paga o líquido; closingDay=31 não lança exceção em
fevereiro; 2 pays concorrentes → exatamente 1 pagamento e 1 resposta de erro.

---

## Fase 2 — Cluster B: segurança

### 2.1 — #143 CSV formula injection no export de fatura

**Local confirmado:** `frontend/src/app/core/csv.utils.ts` — `csvField` escapa só `;`, `"`,
`\n`; `exportInvoiceToCsv` em
`frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.ts` exporta
`description`/`categoryPath` crus. Já existe `csv.utils.spec.ts` (lógica pura, Vitest direto).

**Reprodução:** adicionar casos ao spec com payloads `=HYPERLINK(...)`, `+1+1`, `-2+3`,
`@SUM(A1)`, `\tcmd`, e um campo com `\r` solto:

```bash
./scripts/test-summary.sh frontend
```

(mesmo sendo util pura, siga a convenção da casa — `fintech-core-validation-and-qa`: rodar via
`ng test`/`npm test`/`test-summary.sh`, nunca `npx vitest` cru).

**Gate:** payloads devem sair crus hoje. *Se saírem prefixados com `'` → já corrigido; confira
se `\r` também força quoting antes de encerrar o item.*

**Solução (padrão OWASP para CSV injection):** em `csvField`, se o valor começar com `=`, `+`,
`-`, `@`, `\t` ou `\r` → prefixar `'` (apóstrofo); incluir `\r` na condição de quoting junto de
`\n`. **Derivação:** o apóstrofo força o Excel/LibreOffice a tratar a célula como texto; o
quoting sozinho NÃO impede execução de fórmula — são defesas diferentes (quoting protege a
estrutura do CSV; apóstrofo protege o interpretador da planilha).

**Cercado:** NÃO "sanitizar" removendo caracteres do dado (perde informação legítima — descrição
"−R$ 50 ajuste" existe); neutralize na borda de export.

### 2.2 — #144 bypass do rate limit via X-Forwarded-For + DoS de memória

**Local confirmado:** `AuthController.login` monta a chave `ip:email` lendo `X-Forwarded-For`
do cliente sem trusted proxy; `LoginRateLimiter` (`config/LoginRateLimiter.java`) só tem
eviction preguiçosa (na releitura da chave ou no sucesso) — flood de chaves aleatórias cresce o
`ConcurrentHashMap` sem teto.

**Reprodução:** teste de controller (MockMvc): 6+ POSTs `/auth/login` com senha errada para o
mesmo email, variando `X-Forwarded-For` a cada request → hoje NUNCA retorna 429. Teste do
limiter (`LoginRateLimiterTest` já existe — estender): registrar N chaves distintas e verificar
que o mapa não encolhe nunca.

```bash
./mvnw -f backend/pom.xml test -Dtest=LoginRateLimiterTest
```

**Gate:** *se o 6º request com XFF rotativo devolver 429 → a chave já não inclui o IP;
verifique o segundo problema (eviction) separadamente antes de pular o item.*

**Menu de soluções:**

1. **Chave por email apenas** (remover IP da chave) + eviction com teto. **Derivação:** o
   objetivo declarado do limiter (summary.md) é "5 tentativas falhas por email/minuto" — o IP na
   chave só enfraquece isso quando o atacante controla o header. Proteção por IP legítima exige
   `server.forward-headers-strategy` configurado atrás de proxy confiável (Railway) — mudança de
   config → registrar onde `fintech-core-config-and-flags` manda. **Recomendada como mínimo.**
2. **Duas janelas (email E IP confiável)** — só faz sentido DEPOIS de forward-headers correto;
   caso contrário a janela por IP é teatro.
3. **Eviction:** (a) `@Scheduled` varrendo janelas expiradas (sem dependência nova, alinhado ao
   estilo do projeto); (b) Caffeine com `expireAfterWrite` + `maximumSize` (dependência nova —
   decisão a apresentar no plano, não tomar sozinho).

**Cercado:** NÃO confiar em `X-Forwarded-For` sem `ForwardedHeaderFilter`/estratégia de
forward-headers; NÃO logar a senha ou o token nos testes/logs do limiter.

**Fechamento da fase 2:** payloads de fórmula saem neutralizados; 6ª tentativa falha por email
→ 429 independente de headers; mapa do limiter tem teto/limpeza comprovada por teste; suíte
verde; merge via change-control.

---

## Fase 3 — Cluster C: double-entry e agregados do dashboard

### 3.1 — #138 update/delete aceita perna de transferência

**Local confirmado:** `TransactionService.update` e `delete` não têm guard para
`transferId != null`; no `delete`, perna de transferência cai no ramo
`installmentGroup == null` e é apagada sozinha. Só `deleteTransfer` remove o par.

**Reprodução:** testes em `TransactionServiceTest`: (a) update de `amount` numa perna → hoje
passa; (b) delete SINGLE de uma perna → hoje deleta e deixa a irmã órfã.

**Gate:** *se update/delete já lançarem exceção para `transferId != null` → confirme que o
`GlobalExceptionHandler` mapeia para 400/409 (não 500) e vá para 3.2.*

**Menu de soluções:**

1. **Rejeitar** — guard no início de `update` e `delete`: `transferId != null` →
   `IllegalStateException` com mensagem apontando o fluxo de transferência
   (`DELETE /api/transfers/{transferId}`). **Derivação:** double-entry é invariante — as pernas
   nascem juntas (`createTransfer`) e morrem juntas (`deleteTransfer`); mutação unilateral cria
   ou some dinheiro do tenant. Rejeitar preserva o invariante com ~6 linhas. **Recomendada.**
2. **Mutação simétrica** — aplicar update nas duas pernas. Cercada por ora: `accountId` e `type`
   não têm espelhamento bem-definido (as pernas têm contas e tipos opostos); exigiria spec
   própria de "editar transferência". Se o desenvolvedor quiser, é feature, não bugfix.

**Frontend (defesa em profundidade):** ocultar/desabilitar editar+excluir individual em linhas
com `transferId` na lista de transações, apontando para a ação de excluir transferência.
Backend continua sendo a cerca real.

### 3.2 — #145 transferências e pagamento de cartão inflam income/expense

**Local confirmado:** `sumByTenantAndTypeAndPeriod` e `countByTenantAndPeriod`
(`TransactionRepository`) não excluem `transferId IS NOT NULL` — ao contrário de
`findUnplannedByCycle`, que já exclui (o padrão correto existe no próprio arquivo). Além disso,
o EXPENSE de pagamento de fatura (`InvoiceService.pay`, `invoice=null`, `date=now()`) soma no
mês do pagamento ALÉM das compras no mês do `dueDate` → despesa 2× (teoria da dupla contagem:
ver `fintech-domain-reference`).

**Reprodução:** `DashboardServiceTest`: (a) transferência de 5.000 no período → income e expense
inflam 5.000 cada; (b) compra de cartão em junho paga em julho → 100 de despesa em cada mês.

**Duas correções independentes — não misture:**

1. **Transferências (fazer já):** `AND t.transferId IS NULL` nas duas queries do dashboard.
   Sem migration, sem ambiguidade.
2. **Pagamento de fatura (exige decisão de schema):** hoje NADA identifica a transação de
   pagamento (é um EXPENSE comum). Menu:
   - **Coluna FK nullable `paid_invoice_id` em `transactions`** (migration V22+): "este EXPENSE
     quita a fatura X". Permite excluir do dashboard (`paid_invoice_id IS NULL`), dá
     rastreabilidade, e um índice único parcial sobre ela fecha DEFINITIVAMENTE o #139 no schema.
     **Recomendada** — mas é mudança de schema: migration nova + atualizar dataset conforme a
     regra do `dataset.md` (nova coluna → revisar INSERTs; seeds aplicados são imutáveis, então
     a coluna nasce nullable e os dados antigos ficam NULL, o que é semanticamente correto).
   - Flag boolean `is_invoice_payment` — mais pobre (sem rastreio de qual fatura, sem unique).
   - ~~Detectar por `description LIKE 'Pagamento fatura%'`~~ — **cercado** (cerca global 4).

**Gate de decisão:** *se o desenvolvedor não aprovar a migration nesta fase → entregue só a
exclusão de transferências, deixe o teste (b) marcado `@Disabled("aguarda decisão #145 marcador
de pagamento")` e registre a pendência no relatório — nunca entregue o teste deletado.*

### 3.3 — #151 totalAccountBalance inclui contas arquivadas

**Local confirmado:** `TransactionRepository.sumNetLiquidBalanceByTenant` não filtra
`account.active = true`; `AccountRepository.sumLiquidBalanceByTenant` (usado no
`openingBalance` do ciclo) filtra → dois "saldos líquidos" divergentes para o mesmo tenant.

**Reprodução:** `DashboardServiceTest`: conta com 1.000 PAID, arquivar (`active=false`),
dashboard ainda mostra 1.000.

**Divergência declarada (mesmo fato, dois enquadramentos):** a assimetria é intencional no
código atual — `sumNetLiquidBalanceByTenant` foi escrita sem o filtro, como documenta
`fintech-core-proof-and-analysis-toolkit` §2b —, mas a issue #151 (OPEN) a trata como bug a
corrigir. O veredicto é do desenvolvedor, na spec da fase; não dê nenhum dos lados como
decidido.

**Solução proposta pela issue:** `AND t.account.active = true` na query. **Derivação (o
argumento da issue):** arquivar é o soft delete de conta; um agregado "disponível agora" que
soma conta arquivada contradiz a própria definição de `countInLiquidBalance` — e a
consistência com o `openingBalance` do planejamento é o critério de pronto (os dois números
batem para o mesmo tenant/instante). Se o desenvolvedor decidir manter o comportamento atual,
feche o item documentando a decisão na issue e atualize o proof-toolkit e esta skill.

**Fechamento da fase 3:** perna de transferência imutável isoladamente (400/409 coberto por
teste); dashboard sem income/expense fictícios de transferência; saldo do dashboard ==
saldo-base do ciclo para o mesmo corte; suíte verde; merge via change-control.

---

## Fase 4 — Cluster D: recorrência ↔ planejamento

Os cinco primeiros itens são o mesmo defeito de fundo: **os contratos entre RecurrenceRule,
Transaction e BudgetItem não são validados nas bordas**. Leia
`docs/superpowers/specs/2026-06-25-motor-de-recorrencia-nucleo-design.md` antes da spec da fase.

### 4.1 — #141 `link()` sem os guards de `realize()`

**Local confirmado:** `BudgetItemService.link` usa `findByTransactionAndCycleNot` (só bloqueia
vínculo em OUTRO ciclo — a mesma transação pode ser vinculada a 2+ itens do MESMO ciclo), não
checa ciclo OPEN, não checa compatibilidade de tipo, não sincroniza `item.setAmount(tx)` —
tudo que `realize()` (no mesmo arquivo) já faz.

**Reprodução:** `BudgetItemServiceTest`: tx PAID de 100 vinculada a dois itens EXPENSE do mesmo
ciclo → `currentBalance` debita 200. Um teste por guard ausente (4 testes).

**Solução:** extrair os guards de `realize()` para um método privado
(`validateAndAttach(item, tx)`) e usá-lo em ambos. **Derivação:** dois caminhos de escrita para
o mesmo estado com validações diferentes é a definição de bug de invariante; a correção é
unificar o caminho, não duplicar os ifs (duplicação = a próxima divergência).

### 4.2 — #140 confirmar recorrência não vincula ao BudgetItem (dupla contagem)

**Local confirmado:** `TransactionService.materializeFromRule` cria a transação com
`recurrenceRule` + `recurrenceOccurrence` mas não procura o `BudgetItem` RECURRING
correspondente no ciclo aberto; a transação cai em `findUnplannedByCycle` como avulsa e
`BudgetSummaryService` soma o item planejado E a avulsa.

**Reprodução:** teste de resumo do ciclo (`BudgetSummaryServiceTest`): regra Netflix 55 vira
item RECURRING; confirmar a ocorrência pela rota de transações; `availableToSpend` hoje cai 110.

**Solução:** após materializar, localizar `BudgetItem` com `source=RECURRING`,
`recurrenceRuleId` da regra e `recurrenceOccurrenceDate == occurrence` no ciclo OPEN do tenant;
se existir, vincular via o caminho unificado de 4.1 (transação nasce PENDING — o modelo já
suporta REALIZED com tx PENDING via `transactionStatus`, ver summary.md). **Por isso 4.1 vem
antes de 4.2** — o vínculo automático deve passar pelos mesmos guards. Direção da dependência:
o serviço de transação não deve conhecer planejamento — dispare a vinculação do lado do
planejamento (listener/serviço chamado por `RecurrenceRuleService.confirmOccurrence`) ou num
orquestrador; decida na spec, não improvisando import cruzado.

### 4.3 — #146 confirmar/pular não valida slot, EXDATE nem status

**Local confirmado:** `RecurrenceRuleService.confirmOccurrence/skipOccurrence` aceitam qualquer
data (`findOwned` não checa status; nenhuma validação contra a expansão da RRULE nem contra
`recurrence_exceptions`). Confirmar um "slot" inexistente (14/07 numa regra BYMONTHDAY=15)
convive com o fantasma real de 15/07 → pagamento 2× (o índice único não protege: datas
diferentes).

**Reprodução:** `RecurrenceRuleServiceTest`: (a) confirmar data fora da expansão → hoje cria
transação; (b) confirmar data já em EXDATE → hoje cria; (c) confirmar/pular em regra CANCELLED
→ hoje aceita.

**Solução:** antes de materializar/pular: regra `ACTIVE` (senão 422); `occurrence` ∈
expansão da RRULE na janela do mês da data (reuse a expansão de
`backend/src/main/java/com/fintech/api/service/recurrence/RecurrenceProjectionService.java` —
nunca expanda sem janela); `occurrence` ∉ EXDATE para confirm. **Derivação:** a projeção
subtrai materializadas por data EXATA — logo o conjunto de datas confirmáveis tem que ser
exatamente o conjunto projetável, ou a subtração nunca converge.

### 4.4 — #152 `syncInstallments` apaga itens REALIZED

**Local confirmado:** `BudgetCycleService.syncInstallments` deleta TODOS os itens INSTALLMENT
(inclusive REALIZED/vinculados) e recria PENDING via `populateInstallmentItems`.

**Reprodução:** `BudgetCycleServiceTest`: realizar parcela (vincular tx PAID), sincronizar →
hoje o realizado vira PENDING e o vínculo se perde (`realizedExpense` muda retroativamente).

**Solução:** sync aditivo — remover apenas itens INSTALLMENT com `status == PENDING` e
`transaction == null`; inserir somente parcelas do período que ainda não têm item (chave:
transação da parcela já referenciada por algum item do ciclo). **Derivação:** REALIZED é fato
consumado do ciclo; sincronização é reconciliação de PREVISTOS — apagar fatos numa
reconciliação é corrupção de histórico, o mesmo princípio das migrations imutáveis.

### 4.5 — #147 reativação de regra cancelada inalcançável

**Local confirmado:** `RecurrenceRuleService.findAll` só retorna ACTIVE; o frontend
(`recurring-item-list.ts`) monta `inactiveItems` filtrando `status !== 'ACTIVE'` do que recebeu
— após F5 a lista de inativas fica sempre vazia e `PATCH /{id}/reactivate` (existe e funciona)
fica inalcançável pela UI.

**Solução:** parâmetro `?includeInactive=true` no GET. **É mudança de contrato** → primeiro
`api-spec/openapi.yaml`, depois `./scripts/api-sync.sh` (nunca os passos manuais), depois
backend + frontend. Teste: backend (param filtra) + frontend (aba lista canceladas após reload).

### 4.6 — #142 fantasmas (id=null) deduplicados somem da lista

**Local confirmado:** `sortTransferPairsTogether` em
`frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts` deduplica
com `Set` keyed por `t.id`; fantasmas têm `id=null` no contrato → o primeiro `null` entra e
todos os fantasmas seguintes são omitidos silenciosamente. O tipo Orval `id: string` esconde o
`null` (armadilha "sem required → `!` assertions" do summary.md).

**Reprodução:** caso novo em `transaction-list.utils.spec.ts` com 2+ fantasmas
(`projected: true, id: null as unknown as string`) → hoje só o primeiro sobrevive.

```bash
./scripts/test-summary.sh frontend
```

**Solução:** chave estável `ghost:${recurrenceRuleId}:${occurrenceDate}` quando `id` for
null/undefined (ou pular dedup para fantasmas — fantasma nunca é perna de transferência).
Auditar TODOS os usos de `t.id` como chave no arquivo (`expandedIds.has(t.id)` tem o mesmo
defeito). **Cercado:** NÃO "consertar" fazendo o backend inventar um id sintético para
fantasma — `id=null` é contrato documentado (summary.md, `includeProjected`).

**Fechamento da fase 4:** confirmar ocorrência nunca gera dupla contagem no ciclo nem slot
órfão; sync preserva realizados; regra cancelada reativável pela UI após F5; N fantasmas do
mês → N linhas na tabela; suíte backend+frontend verde; merge via change-control.

---

## Fase 5 — Cluster E: frontend (entrada de dados e sessão)

Specs de componente rodam via `npm test` / `./scripts/test-summary.sh frontend` — nunca
`npx vitest` cru. Lógica nova deve nascer em arquivo de util pura (padrão do projeto).

### 5.1 — #148 parsing de valor com `.` e data com `toISOString`

**Local confirmado:** `transaction-form.ts` — `onAmountInput` faz `raw.replace(/[^\d,]/g, '')`
(remove o `.`: digitar `1234.56` → `123456` → R$ 123.456,00 silencioso); `toDateString` usa
`date.toISOString().split('T')[0]` (meia-noite local em UTC− vira D−1 — nota: a issue diz
"UTC+", mas a derivação correta é: `toISOString` converte para UTC, então em fusos NEGATIVOS
como o Brasil, 2026-07-05T00:00−03:00 vira 2026-07-05T03:00Z... e em fusos positivos
2026-07-05T00:00+03:00 vira 2026-07-04T21:00Z → D−1. O bug existe; o fuso afetado é o de
offset POSITIVO, confirme com teste antes de dar o item por reproduzido).

**Solução:** (a) no parse, normalizar `.` → `,` quando for separador decimal (heurística: um
único `.` e nada de `,` → decimal) OU tratar ambos; extrair para util pura com testes de tabela
(`"1234.56"→1234.56`, `"1.234,56"→1234.56`, `"1234,56"→1234.56`); (b) formatar
`yyyy-MM-dd` manualmente com `getFullYear/getMonth/getDate` — sem passar por UTC.

### 5.2 — #149 `form.invalid` lido direto no template (Zoneless)

**Local confirmado por grep (2026-07-05), 9 ocorrências em 8 templates:**
`planning/budget-item-form.html` (linhas com `cycleForm.invalid` e `itemForm.invalid` — a
issue cita como `form.invalid`, mesma classe de bug), `planning/recurring-item-form.html`,
`team/invite-dialog.html`, `category/category-form.html`, `account/account-form.html`,
`auth/register.html`, `auth/accept-invite.html`, `auth/login.html`.

**Solução (padrão já canonizado no projeto — ver o pitfall Zoneless/`form.invalid` em
`fintech-core-debugging-playbook`):**
`readonly formStatus = toSignal(this.form.statusChanges, { initialValue: this.form.status })` +
`computed` para o `[disabled]`. Correção mecânica × 9; um componente por commit facilita o
review. Re-grep como critério de pronto:

```bash
grep -rn "\.invalid" frontend/src/app --include='*.html'
```

→ zero ocorrências lendo `FormGroup.invalid` direto.

### 5.3 — #150 sessão expirada sem tratamento de 401

**Local confirmado:** `app.config.ts` registra só `apiUrlInterceptor` + `authInterceptor`
(nenhum interceptor de erro); `AuthService.decodeToken`
(`frontend/src/app/core/services/auth.ts`) popula `currentUser` sem validar `exp` (só faz
logout se o decode lançar).

**Solução:** (a) interceptor de erro registrado em `app.config.ts`: resposta 401 → limpar
sessão (logout) + redirect `/login` (cuidado para não interceptar o próprio POST `/auth/login`,
senão erro de senha vira redirect-loop); (b) `decodeToken` valida `exp` contra o relógio e
trata token vencido como ausente. Teste: util pura para a decisão de expiração + spec do
interceptor.

**Fechamento da fase 5:** tabela de casos de parsing passa; grep de `.invalid` em templates
zerado; 401 em uso derruba para `/login` sem loop de snackbar; suíte frontend verde; merge via
change-control.

---

## Fase 6 — Estrutural: `effective_date` (#85, ADR-001 item 3)

**Quando entrar:** somente após fases 1 e 3 mergeadas em `develop` (mesmos arquivos:
`TransactionService`, `InvoiceService`, `TransactionRepository`) e, idealmente, após a decisão
do marcador de pagamento (3.2) — a coluna nova e o marcador podem sair na mesma migration se as
decisões coincidirem no tempo. É mudança multi-arquivo com migration → spec SDD obrigatória.

**Problema (issue + ADR-001):** a regra de data efetiva `effectiveSortDate` (enunciado e
teoria: ver `fintech-domain-reference`) só existe em memória, no Java. Sem coluna no banco não há
`ORDER BY` + `LIMIT/OFFSET` → sem paginação real, e exportação/relatório anual carrega o tenant
inteiro em memória.

**Desenho por estágios (escrita dupla — cada estágio mergeável e verde sozinho):**

1. **Migration V(próxima) — coluna + backfill + índice** (a issue #85 traz o SQL de referência):
   ```sql
   ALTER TABLE transactions ADD COLUMN effective_date DATE;
   -- backfill: com invoice → invoices.due_date; sem invoice → transactions.date
   -- (a issue restringe a installment_group_id IS NOT NULL; confirme na spec se avulsa de
   --  cartão segue t.date — é o comportamento atual do effectiveSortDate, ver summary.md)
   ALTER TABLE transactions ALTER COLUMN effective_date SET NOT NULL;
   CREATE INDEX idx_transactions_tenant_effective_date
       ON transactions (tenant_id, effective_date DESC);
   ```
   Seeds V13/V16/V20 são imutáveis e rodam ANTES da nova migration → o backfill cobre as linhas
   deles; nenhuma edição de seed é necessária (nem permitida). Gate: `SELECT COUNT(*) FROM
   transactions WHERE effective_date IS NULL` → 0 no banco dev pós-migration.
2. **Escrita dupla:** todo ponto que cria/altera Transaction preenche a coluna. Pontos de
   escrita confirmados no código em 2026-07-05: `TransactionService.create` (parcelas e
   avulsas), `update` (mudança de date/account), `materializeFromRule`, `createTransfer`
   (duas pernas), `InvoiceService.pay` (EXPENSE de pagamento) e `BudgetItemService.realize`
   (cria transação quando `transactionId == null`). Menu para o "como":
   - **Callback `@PrePersist/@PreUpdate` na entidade** derivando de `invoice`/`date` — ponto
     único, difícil esquecer um call site; risco: depende do `invoice` estar acessível no
     flush (mesma persistence context — verdadeiro nos fluxos atuais).
   - **Set explícito em cada service** — visível, porém 6 pontos hoje e N amanhã; o `NOT NULL`
     ao menos falha alto se um site for esquecido.
   Derive e decida na spec; a leitura continua usando `effectiveSortDate` em memória neste
   estágio (os dois caminhos coexistem — é isso que "escrita dupla" compra: rollback barato).
3. **Virada de leitura:** `findAllByTenantWithFilters` passa a `ORDER BY t.effectiveDate DESC`
   (+ filtros de período sobre a coluna). Gate de equivalência ANTES de remover o caminho velho:
   teste que compara a ordenação em memória vs. a do banco no dataset Família Costa — listas
   idênticas, senão o backfill ou a escrita dupla está errada em algum ramo.
4. **Remoção:** apagar `effectiveSortDate` do serviço só depois do gate 3 verde. O frontend
   replica a regra em `timeline-shared.ts` para EXIBIÇÃO — isso continua válido (mostrar a data
   efetiva) e não deve ser removido junto.

**Critério de pronto (da própria issue):** migration + backfill corretos; índice criado;
escrita preenche em todos os pontos; `pay` mantém a coluna coerente; listagem ordena no banco;
zero `effective_date IS NULL`; suíte verde.

---

## Protocolo de validação e promoção (toda fase)

1. Spec/plano SDD aprovado pelo desenvolvedor (mudança multi-arquivo — todas as fases são).
2. Worktree própria a partir de `develop` atualizada (fluxo exato: `git-operator.md` /
   `fintech-core-change-control`).
3. Testes de reprodução commitados FALHANDO (commit separado ou explícitos no PR) → fix →
   mesmos testes passando como regressão permanente.
4. Schema mudou? → migration nova (nunca editar aplicadas) + regra do dataset (`dataset.md`):
   nova coluna/tabela → seed/fixture/`docs/http/seed-dataset.http` conforme a tabela de lá.
5. Contrato mudou? → `api-spec/openapi.yaml` primeiro + `./scripts/api-sync.sh`.
6. Suíte completa verde: `./scripts/test-summary.sh` (backend em background se necessário).
7. Merge em `develop` após aprovação explícita; PR cumulativo da fase para `main`; remover a
   worktree. Sugerir (não executar) fechamento das issues no relatório final da fase.

## Tabela-resumo

| Issue | Cluster | Fase | Severidade | Teste de reprodução | Critério de pronto |
|---|---|---|---|---|---|
| #135 | A | 1 | crítica | fatura 500 EXPENSE + 100 INCOME → pagamento hoje 600 | pagamento = 400; buildDTO idem; estorno-só não cria pagamento |
| #136 | A | 1 | alta | (100,3) e (1000,7): soma(parcelas) ≠ total | soma == total para toda tabela de casos |
| #137 | A | 1 | alta | closingDay=31 fechando em fev → DateTimeException | fatura criada no último dia do mês; preview espelhado |
| #139 | A | 1 | alta | 2 pays concorrentes → 2 EXPENSEs | exatamente 1 pagamento; 2º recebe erro de estado |
| #143 | B | 2 | alta (seg) | payload `=HYPERLINK` sai cru no CSV | prefixo `'` para `= + - @ \t \r`; `\r` força quoting |
| #144 | B | 2 | alta (seg) | 6 falhas com XFF rotativo → nunca 429 | 429 por email independente de header; eviction com teto testada |
| #138 | C | 3 | alta | update/delete em perna com transferId passa | 400/409; par intocável fora de `deleteTransfer`; UI oculta ações |
| #145 | C | 3 | média | transferência infla income+expense; compra+pagamento = 2× despesa | somas excluem transferId; decisão do marcador registrada/implementada |
| #151 | C | 3 | média | conta arquivada segue no totalAccountBalance | decisão do dev registrada na #151: filtro `active=true` OU comportamento atual documentado |
| #141 | D | 4 | alta | tx 100 vinculada a 2 itens do mesmo ciclo | guards unificados link/realize (4 testes) |
| #140 | D | 4 | alta | confirmar recorrência → availableToSpend cai 2× | materialização vincula item RECURRING do ciclo; sem avulsa duplicada |
| #146 | D | 4 | média | confirmar slot inexistente/EXDATE/regra CANCELLED passa | 400/422 nos três casos; só slots projetáveis confirmam |
| #152 | D | 4 | média | sync apaga item REALIZED e recria PENDING | sync preserva REALIZED/vinculados; só adiciona faltantes |
| #147 | D | 4 | média | F5 → aba inativas vazia, reactivate inalcançável | `?includeInactive=true` no contrato; aba popula após reload |
| #142 | D | 4 | alta | 2+ fantasmas no mês → 1 linha na tabela | chave estável para id=null; N fantasmas → N linhas |
| #148 | E | 5 | média | `1234.56` → 123456; data D−1 conforme fuso | tabela de parsing passa; data formatada sem UTC |
| #149 | E | 5 | média | grep acha 9 `*.invalid` em templates | grep zerado; padrão toSignal(statusChanges) |
| #150 | E | 5 | média | 401 em uso → loop de snackbar, sem redirect | 401 → logout + /login sem loop; decodeToken valida exp |
| #85 | F | 6 | estrutural | ordenação/paginação impossível no banco | coluna + backfill + índice + escrita em 6 pontos + ORDER BY no banco |

## Proveniência e manutenção

Fatos verificados contra o repo e o GitHub em **2026-07-05** (issues datadas de 2026-07-04).
Linhas de código citadas nas issues foram conferidas por método/arquivo (números de linha
derivam com o tempo — confie no nome do método). Re-verificação de uma linha cada:

```bash
gh issue list -R sergiohcosta/fintech-core --state open --limit 50   # backlog ainda é este?
gh issue view 135 -R sergiohcosta/fintech-core                        # (idem 136..152, 85)
grep -n 'SUM(t.amount)' backend/src/main/java/com/fintech/api/repository/TransactionRepository.java   # #135 vivo?
grep -n 'HALF_EVEN' backend/src/main/java/com/fintech/api/service/TransactionService.java             # #136 vivo?
grep -n 'withDayOfMonth' backend/src/main/java/com/fintech/api/service/InvoiceService.java            # #137 vivo?
grep -rn 'transferId' backend/src/main/java/com/fintech/api/service/TransactionService.java | grep -c 'update\|delete'  # #138: guard existe?
grep -rln '@Version' backend/src/main/java/com/fintech/api/domain/    # #139: vazio = sem optimistic lock
grep -n "X-Forwarded-For" backend/src/main/java/com/fintech/api/controller/AuthController.java        # #144 vivo?
grep -n "includes(';')" frontend/src/app/core/csv.utils.ts            # #143 vivo?
grep -rn "\.invalid" frontend/src/app --include='*.html'              # #149: quantos restam
grep -n 'effective_date' backend/src/main/resources/db/migration/*.sql # #85: migration já existe?
```

Ao concluir uma fase: atualizar `summary.md`/`domain.md`/`database-schema.md` conforme o mapa
fonte-única (`fintech-core-docs-and-writing`) e ATUALIZAR ESTA SKILL riscando a fase concluída
com a data — uma campanha desatualizada é pior que nenhuma.
