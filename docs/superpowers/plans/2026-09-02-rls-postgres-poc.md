# RLS Postgres — PoC em `transactions` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.
>
> **Status: EXECUTADO (2026-09-02).** Suíte backend completa: 426/426 verdes. Ver "Achados
> reais" em cada task — o plano original previu a estrutura certa, mas a execução revelou 4
> achados não previstos (documentados abaixo, cada um com fix aplicado e testado).

**Goal:** provar, com teste discriminante, que uma policy RLS no Postgres bloqueia leitura
cross-tenant em `transactions` **mesmo quando a aplicação não filtra por tenant** — sem
quebrar o comportamento existente. **Alcançado.**

**Arquitetura (como ficou):** `ENABLE`/`FORCE ROW LEVEL SECURITY` + policy `USING (tenant_id =
NULLIF(current_setting('app.tenant_id', true), '')::uuid)` em `transactions` (migration V33).
`TenantRlsAspect` (Spring AOP `@Around`) envolve os métodos públicos de `TransactionService`
**e** `InvoiceService.pay` (achado #4 abaixo), executando `SET LOCAL app.tenant_id` lido do
parâmetro `User` de cada método (não do `SecurityContextHolder` — achado #2). Requer role
Postgres não-superuser como runtime da app (achado #1). Racional completo: ADR-006 + spec
`2026-09-02-rls-postgres-poc-design.md`.

**Tech Stack:** Java 21, Spring Boot, Spring AOP, Flyway, `@SpringBootTest` contra Postgres
real (dev local, não Testcontainers, confirmado na Task 1).

## Global Constraints

- Migration V33 e a Task 2 (aspect) entram no **mesmo commit/PR** — nunca fazer merge com a
  policy ativa e sem o aspect.
- Filtros `WHERE tenant_id` existentes em `TransactionRepository`/`TransactionService`
  **não são removidos** — RLS é redundante por design (defesa em profundidade, não
  substituição).
- SemVer: **nenhum impacto** — RLS é interno, não toca `openapi.yaml`.
- `database-schema.md`/`architecture.md` atualizados (Task 5).

---

## Achados reais da execução (fora do previsto no plano original)

**#1 — `admin` (docker-compose) é superuser Postgres; superuser sempre bypassa RLS, mesmo com
`FORCE`.** Descoberto no primeiro run do teste discriminante (185 linhas retornadas sem
`WHERE`, deveria ser 0). Fix: role `fintech_app` (sem `SUPERUSER`/`BYPASSRLS`) criado via
`.docker/postgres-init/01-app-role.sql`, montado em `docker-compose.yml`
(`/docker-entrypoint-initdb.d`) para setups futuros e aplicado manualmente no container já
rodando. `spring.datasource.*` local passa a apontar pra `fintech_app`; `spring.flyway.*`
(novo, explícito) continua com `admin` (owner, roda migrations). Sem essa separação, RLS
nunca protegeria nada localmente — achado estrutural, não cosmético.

**#2 — Ler o tenant do `SecurityContextHolder` (design original do ADR) quebraria testes
`@SpringBootTest` que chamam o service direto sem passar pelo `SecurityFilter`** (ex.:
`ImportServiceTest`, que aciona `TransactionService.create` de dentro de
`ImportService.commit` passando um `User` de fixture). Fix: `TenantRlsAspect` lê o tenant do
parâmetro `User` que cada método interceptado já recebe — mesma fonte de verdade que o resto
do código já usa. ADR-006 e spec atualizados para refletir essa decisão revisada.

**#3 — `RESET app.tenant_id` numa GUC custom pode voltar para `''` (string vazia), não
`NULL`** — comportamento real do Postgres para parâmetros não registrados, não hipotético.
`''::uuid` estoura exceção em vez de "fail-safe deny". Fix: policy usa
`NULLIF(current_setting(...), '')::uuid`, tratando os dois formatos de "não setado" da mesma
forma. Também exigiu editar a migration diretamente (V33 nunca havia sido aplicada em
"ambiente superior" — só nesta iteração local, então não violou a regra de imutabilidade) e
remover a entrada correspondente de `flyway_schema_history` para reaplicar.

**#4 — `InvoiceService.pay()` grava a `Transaction` de pagamento direto pelo repositório, sem
passar por `TransactionService`.** Achado no teste de concorrência (#139) já existente: as 30
iterações "passavam" mas com 0 pagamentos reais — `racePay()` engole exceções do "perdedor da
corrida" por design, mascarando que TODAS as chamadas a `pay()` falhavam por RLS (nenhum
`app.tenant_id` setado nesse caminho). Fix: pointcut do aspect ampliado para cobrir também
`InvoiceService.pay` especificamente (não a classe inteira — os demais métodos de
`InvoiceService` não recebem `User` e quebrariam com o `orElseThrow`).

**Achado adicional (só teste, não produção):** 3+1 arquivos de teste gravam/leem/apagam
`transactions` direto via `JdbcTemplate`/`EntityManager`, fora de qualquer service —
precisaram de `SET LOCAL` explícito (via `TransactionTemplate` quando o teste não usa
`@Transactional` de classe, ver `InvoiceServicePaymentConcurrencyTest` e `ImportServiceTest`,
ou `entityManager.createNativeQuery(...)` quando usa, ver `TenantRlsAspectTest` e
`DashboardAggregatesRepositoryTest`). Dentro de `@BeforeEach`, `entityManager.flush()`
explícito é necessário após cada `save()` quando o tenant muda no meio do método — Hibernate
pode adiar o INSERT físico até o próximo flush, e se isso acontece depois de trocar
`app.tenant_id`, a linha é rejeitada pelo tenant errado.

---

### Task 1: Levantamento — confirma premissas antes de escrever a migration ✅

- [x] **Step 1:** role único (`admin`) para tudo — runtime, migrations, testes. Confirmou que
      `FORCE` seria necessário — e revelou o achado #1 (superuser) só na Task 3.
- [x] **Step 2:** confirmado Postgres real (dev local), não Testcontainers, `@Transactional`
      (rollback do Spring) é o padrão.
- [x] **Step 3:** baseline verde — 423/423 antes de qualquer mudança.
- [x] **Step 4:** achado dos 3 arquivos que gravam direto pelo repositório (ver "Achados
      reais" acima — o fix real ficou mais elaborado que o previsto aqui).

---

### Task 2: Migration V33 — policy RLS em `transactions` ✅

**Files:**
- Created: `backend/src/main/resources/db/migration/V33__transactions_rls.sql`

Versão final (após achado #3):
```sql
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON transactions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
```

---

### Task 3: `TenantRlsAspect` ✅

**Files:**
- Created: `backend/src/main/java/com/fintech/api/config/TenantRlsAspect.java`
- Created: `backend/src/main/java/com/fintech/api/config/TransactionManagementConfig.java`
  (não previsto no plano original — necessário para fixar a ordem do advisor de
  `@Transactional` vs. o aspect; sem isso os dois ficam empatados em
  `Ordered.LOWEST_PRECEDENCE`, ordem relativa indefinida)
- Created: `backend/src/test/java/com/fintech/api/config/TenantRlsAspectTest.java`

Pointcut final: `TransactionService.*` + `InvoiceService.pay` (achado #4). Tenant lido do
parâmetro `User` (achado #2), não do `SecurityContextHolder`.

3 testes discriminantes, todos GREEN:
1. Sem `app.tenant_id` setado → 0 linhas (fail-safe deny).
2. Com `app.tenant_id` = tenant A → nunca retorna linha do tenant B, mesmo em query nativa
   sem `WHERE`.
3. Fluxo real via `TransactionService.findAll` funciona transparente (aspect operando).

---

### Task 4: Regressão completa ✅

**Files (além do previsto — achado #4 exigiu mais arquivos):**
- Modified: `backend/src/test/java/com/fintech/api/repository/DashboardAggregatesRepositoryTest.java`
- Modified: `backend/src/test/java/com/fintech/api/service/InvoiceServicePaymentConcurrencyTest.java`
- Modified: `backend/src/test/java/com/fintech/api/service/imports/ImportServiceTest.java`
  (não previsto — só apareceu na suíte completa, não no grep inicial por
  `transactionRepository.save`, porque o problema era um `DELETE` raw de cleanup, não um save)
- `BudgetItemServiceTest`: confirmado Mockito puro (`@ExtendWith(MockitoExtension.class)`),
  sem DB real — **não precisou de ajuste**, ao contrário do previsto no plano original.

- [x] **Step 0:** os 3(+1) arquivos ajustados, cada um rodado isolado até verde.
- [x] **Step 1:** suíte backend completa — **426/426, 0 falhas, 0 erros** (423 baseline + 3
      testes novos do `TenantRlsAspectTest`).
- [x] **Step 2:** suíte frontend — não executável nesta worktree (`node_modules` nunca
      instalado aqui, `npm install` não rodado). Mudança é 100% backend (Java/SQL/
      docker-compose), zero arquivo em `frontend/` tocado — risco de regressão é nulo por
      construção, não só por falta de teste. Ver `git diff --stat` do PR antes do merge para
      confirmar.

---

### Task 5: Documentação ✅

- [x] `database-schema.md`: linha V33 + nota na seção de Constraints sobre RLS ativo em
      `transactions`.
- [x] `architecture.md`: referência ao ADR-006 na seção de regras de backend.
- [x] `docs/adr/ADR-006-rls-postgres-defesa-em-profundidade.md` e a spec de design atualizados
      durante a execução para refletir os achados #1, #2 e #4 (decisões revisadas, não só o
      plano original).

---

## Fim do PoC — critério de conclusão (issue #116, parcial)

- [x] Decisão registrada em ADR (`ADR-006`).
- [x] PoC numa tabela validando que query sem filtro de tenant não retorna dado de outro
      tenant — provado por 3 testes discriminantes + suíte completa verde.

Rollout para as demais tabelas fica para uma spec/plano futuro, fora deste PoC (ver spec,
seção "Fora de escopo").

## Próximos passos (fora deste PoC, decisão do dev)

- Suíte frontend (Task 4 Step 2, pendente).
- Push do docker-compose.yml atualizado + init script — hoje só aplicado manualmente no
  container local rodando; qualquer outro ambiente/clone precisa do `docker compose up`
  reconhecer o novo `.docker/postgres-init/` (funciona para containers NOVOS; o container
  atual já tem o role aplicado manualmente).
- Confirmar se produção (Neon) já usa um role não-superuser — se sim, RLS lá já protegeria
  mesmo sem esse achado; se não, é um segundo front a resolver antes de confiar em RLS como
  defesa real em produção.
- Decidir sobre rollout para as demais tabelas (`accounts`, `categories`, etc.) como próxima
  spec, quando fizer sentido.
