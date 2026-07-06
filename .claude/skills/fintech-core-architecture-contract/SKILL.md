---
name: fintech-core-architecture-contract
description: >
  Contrato arquitetural do fintech-core: invariantes que nunca podem quebrar (tenant isolation
  acima de tudo), decisões de design estruturais reais e o porquê de cada uma (camadas
  Controller→Service→Repository, DTO nas bordas, saldo sempre calculado, lazy-create de fatura,
  double-entry, recorrência regra-vs-fato, Modelo A, spec-first OpenAPI, Zoneless/Signals),
  e pontos fracos abertos declarados. Use quando a pergunta for "por que é assim?", "posso mudar
  isso?", "qual o invariante?", "isso quebra a arquitetura?", ou ao revisar design de qualquer
  mudança estrutural (architecture, invariante, tenant isolation, camadas, design decision).
---

# Contrato Arquitetural — fintech-core

Este documento é um **contrato**, não um tutorial. Ele enumera o que **não pode quebrar** neste
repositório, por que cada decisão estrutural foi tomada, e onde o projeto sabe que é fraco.
Toda mudança estrutural deve ser confrontada com esta página antes de virar código — e roteada
pelo ciclo de mudança (ver `fintech-core-change-control`).

## Quando NÃO usar esta skill

| Se você precisa de… | Use |
|---|---|
| Teoria de domínio (semântica de saldo, ciclo de fatura, RRULE, matemática do Modelo A) | `fintech-domain-reference` |
| **Provar** um invariante (query de tenant, correção de saldo, race de `getOrCreate`) | `fintech-core-proof-and-analysis-toolkit` |
| Corrigir um bug do backlog 2026-07 (#135–#152) | `fintech-core-bug-backlog-campaign` |
| Diagnosticar um sintoma (Flyway checksum, drift Orval, pitfall Zoneless) | `fintech-core-debugging-playbook` |
| Processo de mudança (SDD, worktree, migration, PR) | `fintech-core-change-control` |

---

## Invariante nº 1 — Isolamento de tenant

> **Toda query de dados de negócio é escopada pelo tenant do usuário autenticado.
> Vazamento de tenant é o bug mais grave possível neste projeto.** Uma única instância
> atende múltiplas famílias/empresas; um `findById` sem tenant devolve dados de outra família.

**Como o invariante se materializa no código** (verificado em 2026-07-04):

- Repositories expõem métodos com escopo explícito: `findByIdAndTenant(...)`,
  `findByIdAndTenantIdAndDeletedAtIsNull(...)` (categorias), `findByTransferIdAndTenant(...)`,
  `findByTenantAndStatus(...)` — o padrão está em `TransactionRepository`, `AccountRepository`,
  `CategoryRepository`, `InvoiceService.findByIdAndTenant`, etc.
- Queries agregadas incluem `WHERE t.tenant = :tenant` (ex: `sumNetLiquidBalanceByTenant`
  em `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`).
- O `SecurityFilter` popula o principal (com `tenant`) em toda requisição; services recebem
  o `User` autenticado e derivam o tenant dele — nunca de parâmetro do cliente.

**Regra de revisão (bloqueante em qualquer review):**

1. Todo método novo de repository que toca tabela de negócio DEVE receber `Tenant`/`tenantId`
   como parâmetro e usá-lo no `WHERE`. `findById(UUID)` cru em entidade de negócio é reprovação
   automática — mesmo que "o caller já validou".
2. Todo endpoint novo DEVE derivar o tenant do principal autenticado, nunca de path/query/body.
3. Recurso de outro tenant responde **404** (não 403) — não confirmar existência do recurso.

Como **demonstrar** o isolamento em nível de query (dois tenants, mesma chamada, resultados
disjuntos): receita completa em `fintech-core-proof-and-analysis-toolkit`.

Defesa adicional candidata (RLS no Postgres) ainda **não avaliada** — ver Pontos fracos, #116.

---

## Tabela de invariantes

| # | Invariante | Onde vive | Violação típica |
|---|---|---|---|
| 1 | Query de negócio sempre escopada pelo tenant autenticado | Todos os repositories | `findById` cru; tenant vindo do request |
| 2 | Entidade JPA nunca cruza a borda do controller — DTO sempre | `dto/` espelha `domain/` | Controller retornando `Transaction` |
| 3 | Saldo é **calculado** (SUM de transações PAID), nunca armazenado | `AccountRepository.calculateBalance`, `sumNetLiquidBalanceByTenant` | Criar coluna/tabela de snapshot de saldo |
| 4 | `@Transactional` pertence ao service; controller é fino | `service/*` | Lógica ou transação no controller |
| 5 | Erro de negócio via exceções mapeadas no `GlobalExceptionHandler` | `exception/` | `try/catch` local devolvendo `ResponseEntity` ad hoc |
| 6 | Contrato de API nasce em `api-spec/openapi.yaml` (spec-first) | pom (`interfaceOnly=true`) + `frontend/orval.config.ts` | Endpoint escrito à mão sem tocar o spec |
| 7 | Controle de acesso validado em **duas camadas** (SecurityConfigurations + frontend) | `config/SecurityConfigurations.java` | Ocultar só no frontend |
| 8 | Migrations imutáveis, sem `ddl-auto` | `db/migration/` | Editar migration aplicada (processo: `fintech-core-change-control`) |
| 9 | Frontend Zoneless + Signals-first; nada que dependa de zone.js | `app.config.ts` (`provideZonelessChangeDetection()`) | Ler `form.invalid` direto no template, `setTimeout` para CD |

---

## Decisões estruturais (Decisão / Porquê / O que quebra se violar)

### 1. Camadas Controller → Service → Repository, DTO nas bordas

- **Decisão:** controllers finos delegam imediatamente; services são donos das regras e dos
  limites `@Transactional`; repositories têm JPQL custom mas nunca lógica. Entidade JPA nunca
  sai do backend — todo endpoint fala DTO (com Bean Validation).
- **Porquê:** o domínio financeiro é o valor do projeto; concentrar regra no service torna a
  regra testável com Mockito sem subir HTTP, e o DTO desacopla o contrato público do schema
  (permite evoluir entidade sem quebrar cliente). Exceções de infra nunca sobem cruas: services
  relançam via `com.fintech.api.exception.EntityNotFoundException` (404), `BusinessException`
  (400), `IllegalStateException` (422), `BusinessConflictException` (409) — todas mapeadas no
  `GlobalExceptionHandler`, que também loga stack só em 5xx.
- **O que quebra se violar:** entidade exposta serializa proxies LAZY (500 aleatório), vaza
  campos internos (`passwordHash`), e acopla o contrato ao schema; transação no controller
  quebra o limite transacional único de operações como `pay` de fatura.

### 2. Saldo sempre calculado, nunca snapshot

- **Decisão:** não existe coluna nem tabela de saldo. Saldo de conta =
  `SUM(CASE WHEN type=INCOME THEN amount ELSE -amount END)` sobre transações **PAID** da conta;
  saldo líquido do tenant = mesma soma restrita a contas `countInLiquidBalance=true`
  (`sumNetLiquidBalanceByTenant`). Verificado: `V5__accounts_migration.sql` não tem coluna
  `balance` (as colunas de snapshot da V14 são de **budget cycle**, outro conceito).
- **Porquê:** projeto pequeno (dataset de uma família) — a soma é barata, e um valor calculado
  **elimina por construção a dessincronização de snapshot**: não há job de reconciliação, não
  há snapshot defasado, não há dupla escrita para dessincronizar (a fórmula em si ainda pode
  estar errada — ver #136). Simplicidade + correção > microssegundos.
- **O que quebra se violar:** um snapshot introduz a classe inteira de bugs de consistência
  (escrita dupla, retro-edição de transação sem reprocessar snapshot) que hoje não existe.
  Se performance um dia exigir, o caminho é view materializada/consulta agregada — decisão a
  passar por ADR, não atalho.

### 3. Dois flags de conta: `countInLiquidBalance` vs `countInNetWorth`

- **Decisão:** duas perguntas ortogonais, dois booleans na conta — "conta como dinheiro
  disponível agora?" e "integra o patrimônio total?". Só o primeiro é consumido hoje
  (dashboard/planejamento); o segundo aguarda a tela de Patrimônio (issue #90 documenta as
  queries futuras).
- **Porquê:** um único flag não modela cartão de crédito (não é liquidez, mas afeta patrimônio)
  nem investimento (patrimônio sim, caixa não). Dois flags evitam heurística por tipo de conta
  hardcoded nas queries. Semântica detalhada e defaults por tipo: `fintech-domain-reference`.
- **O que quebra se violar:** colapsar os dois conceitos infla o "disponível" do planejamento
  com dinheiro preso em investimento, ou some com o cartão do patrimônio.

### 4. Fatura lazy-create com retry (ADR-001 / #83)

- **Decisão:** fatura de cartão nasce **na primeira transação do período** via
  `InvoiceService.getOrCreate`: `find` → se ausente, `createNewInvoice` em
  `@Transactional(REQUIRES_NEW)` (via self-injection `@Lazy`, porque chamada em `this` ignora
  o proxy AOP) → `catch DataIntegrityViolationException` → re-`find` da fatura vencedora.
  O `UNIQUE(account_id, reference_year, reference_month)` (V9) é a rede de segurança no banco.
  Ciclo de estado: `OPEN → [close] CLOSED → [pay] PAID` — `close` só muda status (ainda aceita
  cobranças atrasadas); `pay` é uma única `@Transactional`.
- **Porquê:** criar 12 faturas antecipadas por cartão seria materializar futuro especulativo
  (mesmo princípio da recorrência, decisão 6). O `REQUIRES_NEW` existe porque um conflito de
  chave única marca a transação corrente para rollback — sem transação separada, o retry no
  `catch` morreria junto.
- **O que quebra se violar:** remover o retry devolve o 500 da race original (duas abas
  lançando no mesmo mês); remover o `REQUIRES_NEW` faz o retry rodar numa transação já
  condenada. Atenção: ainda há concorrência conhecida no **pagamento** (bug #139).

### 5. Transferência double-entry sem entidade Transfer

- **Decisão:** transferência = par de `Transaction` (EXPENSE na origem + INCOME no destino)
  compartilhando um `transferId` UUID gerado no service. Não existe entidade/tabela Transfer;
  `deleteTransfer` remove as duas pernas por `findByTransferIdAndTenant`.
- **Porquê:** o saldo é calculado somando transações (decisão 2) — se a transferência fosse
  entidade própria, toda query de saldo precisaria de um segundo caminho. Como par de
  transações, saldo, extrato e filtros funcionam de graça; o `transferId` dá a atomicidade
  lógica do par.
- **O que quebra se violar:** editar/excluir **uma** perna isoladamente quebra o double-entry
  (dinheiro criado ou destruído do nada). Esse guard hoje está **incompleto** — bug #138.
  Transferências também não devem inflar income/expense do dashboard (#145).

### 6. Recorrência: regra (definição) vs transação (fato), projeção on-the-fly

- **Decisão:** `RecurrenceRule` guarda a definição atemporal (string RRULE, RFC 5545, lib
  `org.dmfs:lib-recur`); `Transaction` é o fato imutável, gravado **somente** na confirmação.
  Nada é materializado antecipadamente: `RecurrenceProjectionService` calcula os "fantasmas"
  de uma janela como `expand(rrule) − materializadas − EXDATE`, sempre com janela `[from,to]`
  explícita, sem persistir nada. Índice único parcial `(recurrence_rule_id,
  recurrence_occurrence)` impede confirmar a mesma ocorrência duas vezes (409).
- **Porquê:** materializar futuro cria lixo a limpar quando a regra muda ("editar as próximas
  47?"), e projeção é barata numa janela. Fatos e previsões têm ciclo de vida oposto — fato é
  imutável, previsão é recalculável. Detalhe do subconjunto RRULE suportado e da semântica de
  confirmar/pular: `fintech-domain-reference`.
- **O que quebra se violar:** qualquer código que "pré-crie" transações futuras de uma regra
  reintroduz o problema de sincronização que o design elimina; expansão sem janela é loop
  infinito em regra sem `UNTIL/COUNT`.

### 7. Planejamento — Modelo A: cálculo no `BudgetSummaryService`, DTO burro

- **Decisão:** toda a matemática do resumo do ciclo (`currentBalance`, `availableToSpend`,
  `dailyAllowance`, avulsas) vive em **um único lugar**, `BudgetSummaryService`; o DTO de
  resposta apenas mapeia. `currentBalance` conta só PAID (caixa real); `availableToSpend`
  conta tudo exceto SKIPPED (projeção conservadora).
- **Porquê:** números financeiros derivados espalhados por DTO/frontend divergem entre telas
  — fonte única de cálculo é a única forma de o modal de composição do frontend e o card
  baterem sempre. Fórmulas e racional completo: `fintech-domain-reference`.
- **O que quebra se violar:** replicar uma fórmula no DTO ou no componente cria dois números
  "oficiais" diferentes para o mesmo conceito — exatamente o bug que o Modelo A matou.

### 8. Spec-first OpenAPI

- **Decisão:** `api-spec/openapi.yaml` é a fonte do contrato. Backend gera **interfaces**
  Spring (openapi-generator, `interfaceOnly=true`, em `target/`, não commitadas) que os
  controllers implementam; frontend gera cliente Angular via Orval (`orval.config.ts`,
  `tags-split`, `providedIn: 'root'`). Pipeline: `./scripts/api-sync.sh` (detalhes de
  regeneração: `fintech-core-build-and-env`).
- **Porquê:** reduz drasticamente o drift entre backend e frontend — o
  compilador dos dois lados acusa quando alguém sai do contrato. Interface (não código
  completo) no backend mantém a implementação livre nas camadas da decisão 1.
- **O que quebra se violar:** endpoint criado sem passar pelo spec fica invisível para o
  cliente gerado e para o Swagger; edição manual em `frontend/src/app/core/api/` é
  sobrescrita na próxima geração.

### 9. `effectiveSortDate` — data efetiva como decisão de ordenação

- **Decisão:** parcela de cartão ordena/filtra pela `dueDate` da fatura; o resto, pela data
  da transação. A regra existe em JPQL (filtros) e em memória (`effectiveSortDateDto` no
  `TransactionService`, para o sort final descendente). Regra completa e casos:
  `fintech-domain-reference`.
- **Porquê:** para o usuário, a parcela "acontece" quando a fatura vence, não quando a compra
  foi feita — ordenar pela data da compra embaralha o extrato mental do mês.
- **Consequência assumida (dívida declarada):** como a data efetiva não existe como coluna,
  o sort final é em memória — o que **bloqueia paginação server-side** (`ORDER BY` + `LIMIT`
  no banco é impossível). Desbloqueio estrutural = coluna `effective_date` preenchida na
  escrita — issue #85, o bloqueio arquitetural mais relevante do backlog (ADR-001).

### 10. Categorias: árvore multinível com soft delete em cascata

- **Decisão:** árvore livre (`parent_id` autorreferente), soft delete via `deleted_at`
  propagado à subárvore inteira (`softDeleteSubtree`), DELETE bloqueado com 409 +
  `transactionCount` se a subárvore tem transações (archive com `targetCategoryId` reassocia
  antes), propagação de icon/color aos descendentes no update, `taxonomy_code` como código
  semântico cross-tenant que **só ADMIN define** (`AccessDeniedException` no service).
- **Porquê:** transação aponta para categoria — hard delete órfã o histórico; soft delete
  preserva relatórios antigos. Cascata evita subárvore "fantasma" pendurada em pai deletado.
- **O que quebra se violar / estado real verificado (2026-07-04):** a validação anti-circular
  ("descendente não pode virar pai") é aplicada **somente no frontend** (o
  `category-form` exclui a própria subárvore das opções de pai ao achatar a árvore);
  `CategoryService.update` no backend **não** revalida ciclo. Pela regra de defesa em
  profundidade do projeto, isso é um gap conhecido: um PUT direto na API pode criar ciclo e
  fazer `collectSubtreeIds` recursar para sempre. Trate qualquer mudança nessa área como
  oportunidade de fechar o gap. (O N+1 da recursão da árvore é a issue #86.)

### 11. Frontend: Zoneless + Signals-first, lazy por feature, lógica pura fora do TestBed

- **Decisão:** `provideZonelessChangeDetection()` em `app.config.ts`; estado local com
  `signal/computed/effect` (RxJS só para streams genuinamente assíncronos); features
  lazy-loaded autocontidas em `features/`, compartilhado em `core/`; lógica de negócio de
  tela extraída para arquivos **sem imports Angular** (`transaction-list.utils.ts`,
  `amount-math.ts`, `installment-preview.ts`, `calendar-utils.ts`, `csv.utils.ts`…).
- **Porquê:** Zoneless remove a mágica do zone.js — a reatividade fica explícita e barata,
  mas exige disciplina (nada de mutação fora do grafo de signals). Lógica pura em arquivo
  sem Angular roda no Vitest sem `TestBed` — testes de regra de negócio em milissegundos,
  sem o custo dos specs de componente.
- **O que quebra se violar:** APIs dependentes de zone.js simplesmente não atualizam a tela;
  ler `form.invalid` direto no template não é reativo (bug real, #149 — o padrão correto é
  `toSignal(statusChanges)`); lógica de negócio dentro do componente vira testável só via
  spec de componente lento.

---

## Pontos fracos abertos — declarados sem enfeite

Estado em **2026-07-04** (issues abertas confirmadas via `gh issue list`):

| Issue | Fraqueza | Consequência prática |
|---|---|---|
| #85 | Sem coluna `effective_date`; sort de data efetiva em memória | **Paginação server-side bloqueada**; sem filtro de data, carrega o tenant inteiro |
| #86 | Recursão Java nível-a-nível na árvore de categorias (`collectSubtreeIds` etc.) | N+1 crescente com a profundidade; `WITH RECURSIVE` é a solução conhecida |
| #87 | `TransactionService` acumula responsabilidades (transações + transferências + parcelamento + resolução de fatura; ~18 KB) | Cada feature nova tenta pendurar mais uma dependência ali; extrair `TransferService` |
| #88 | Contrato de erro 400 em migração | Nota: `BusinessException` **já existe** no código com handler 400, e `IllegalArgumentException` já mapeia para 500; a issue segue aberta — verificar o que resta antes de mexer |
| #91 | JWT em `localStorage` (`core/services/auth.ts`) | Legível por qualquer XSS; caminho longo = cookie `httpOnly` (mudança coordenada back+front) |
| #116 | RLS (Row-Level Security) no Postgres não avaliado | O isolamento de tenant depende 100% de disciplina em JPQL; RLS seria segunda camada no banco |
| — | Anti-circular de categoria só no frontend (ver decisão 10) | PUT direto pode criar ciclo na árvore; sem issue dedicada em 2026-07-04 |
| #135–#152 | Cluster de ~18 bugs da auditoria 2026-07: correção monetária (centavos de parcela #136, estorno em pagamento #135), concorrência (pagamento duplicado #139), integridade double-entry (#138), dashboard (#145, #151), segurança (#143 CSV injection, #144 rate limit), recorrência↔planejamento (#140, #141, #146, #147, #152), frontend (#142, #148, #149, #150) | Plano de ataque faseado por causa-raiz: `fintech-core-bug-backlog-campaign` |

Regra do contrato: fraqueza declarada **não é licença** para piorá-la. Código novo não pode
adicionar mais uma query sem tenant, mais um cálculo fora do `BudgetSummaryService`, mais uma
responsabilidade no `TransactionService` "porque já está assim".

---

## Proveniência e manutenção

Escrito em 2026-07-04 a partir do código real (branch `develop`), `docs/adr/ADR-001` (lido na
íntegra; ADR-002 Stripe e ADR-003 Fly.io são planos aceitos ainda não refletidos no repo —
fronteira em `fintech-core-research-frontier`), `architecture.md`, `summary.md`, `domain.md`
e auditoria das issues abertas. Re-verificação de uma linha por afirmação:

```bash
# Invariante 1 — escopo de tenant nos repositories
grep -rn "findByIdAndTenant\|WHERE t.tenant" backend/src/main/java/com/fintech/api/repository/ | head
# Saldo calculado, nunca snapshot (não deve haver coluna balance em accounts)
grep -n "balance" backend/src/main/resources/db/migration/V5__accounts_migration.sql
# Lazy-create de fatura com REQUIRES_NEW + retry
grep -n "REQUIRES_NEW\|DataIntegrityViolationException" backend/src/main/java/com/fintech/api/service/InvoiceService.java
# Double-entry sem entidade Transfer
grep -n "transferId" backend/src/main/java/com/fintech/api/service/TransactionService.java
# Projeção on-the-fly (nada persistido)
sed -n '20,30p' backend/src/main/java/com/fintech/api/service/recurrence/RecurrenceProjectionService.java
# Spec-first (interfaceOnly no pom, Orval no frontend)
grep -n "interfaceOnly" backend/pom.xml && head -6 frontend/orval.config.ts
# Zoneless
grep -n "provideZonelessChangeDetection" frontend/src/app/app.config.ts
# Estado das issues citadas
gh issue list --state open --limit 60
```

Gatilhos de atualização: fechar qualquer issue da tabela de pontos fracos; criar migration que
toque `transactions`/`accounts`/`categories`; qualquer ADR novo; mudança no pipeline de geração
de código.
