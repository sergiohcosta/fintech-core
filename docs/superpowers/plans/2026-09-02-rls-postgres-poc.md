# RLS Postgres — PoC em `transactions` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** provar, com teste discriminante, que uma policy RLS no Postgres bloqueia leitura
cross-tenant em `transactions` **mesmo quando a aplicação não filtra por tenant** — sem
quebrar o comportamento existente.

**Arquitetura:** `ENABLE`/`FORCE ROW LEVEL SECURITY` + policy `USING (tenant_id =
current_setting('app.tenant_id')::uuid)` na tabela (migration V33). Um `TenantRlsAspect`
(Spring AOP `@Around`) envolve os métodos públicos `@Transactional` de `TransactionService` e
executa `SET LOCAL app.tenant_id = '<uuid>'` no início da transação, lendo o tenant do
contexto de autenticação (mesma fonte que `SecurityFilter` já usa). Racional completo:
ADR-006 + spec `2026-09-02-rls-postgres-poc-design.md`.

**Tech Stack:** Java 21, Spring Boot, Spring AOP, Flyway, `@SpringBootTest` contra Postgres
real (padrão de integração do projeto — confirmar mecanismo exato no Step 1 da Task 1).

## Global Constraints

- Migration V33 e a Task 2 (aspect) entram no **mesmo commit/PR** — nunca fazer merge com a
  policy ativa e sem o aspect (qualquer request autenticada pararia de funcionar, pois nenhuma
  sessão teria `app.tenant_id` setado).
- Filtros `WHERE tenant_id` existentes em `TransactionRepository`/`TransactionService`
  **não são removidos** — RLS é redundante por design (defesa em profundidade, não
  substituição).
- Se a Task 4 (regressão) revelar caminho de código que toca `transactions` fora de
  `@Transactional` do `TransactionService`, **não silenciar** — documentar como achado do PoC
  no PR e decidir extensão do aspect ali mesmo, não depois.
- Seeds (`V13`, `seed_base.sql`) rodam antes da policy existir por ordem de migration
  (V13 < V33) — mas toda vez que a suíte reconstrói o banco do zero, a ordem de aplicação é
  sequencial, então V33 roda DEPOIS do seed já ter inserido dados. Se o seed usa o mesmo role
  Postgres da aplicação (a confirmar no Step 1), releituras futuras do seed em banco já
  existente (não aplicável — Flyway não re-roda migration aplicada) não são um problema; o
  risco real é runtime da app **depois** que V33 já rodou. Verificar mesmo assim no Step 1.
- SemVer: **nenhum impacto** — RLS é interno, não toca `openapi.yaml`.
- `database-schema.md` ganha a linha da V33 (Task 5).

---

### Task 1: Levantamento — confirma premissas antes de escrever a migration

**Files:** nenhum arquivo alterado — só leitura/comandos.

- [ ] **Step 1: Confirma o role/usuário Postgres usado pela aplicação e pelos testes**

Run:
```bash
grep -n "spring.datasource" backend/src/main/resources/application*.properties
grep -n "spring.datasource" backend/src/test/resources/application*.properties
grep -n "POSTGRES_USER\|POSTGRES_PASSWORD" docker-compose.yml
```
Expected: identificar se o usuário de runtime é o **owner** das tabelas (criado pelas
migrations) — se sim, `FORCE ROW LEVEL SECURITY` é obrigatório (sem ele, owner ignora a
policy) e o próprio teste discriminante precisa confirmar que `FORCE` realmente restringe o
owner também.

- [ ] **Step 2: Confirma o mecanismo de integração (Postgres real vs. Testcontainers)**

Run: `grep -rn "Testcontainers\|@Container" backend/src/test/java/com/fintech/api/ | head -5`
Expected: usar o mesmo mecanismo já em uso para a Task 3/4 — não introduzir um novo.

- [ ] **Step 3: Baseline verde**

Run: `./scripts/test-summary.sh backend` (ou `./mvnw test` em background, é a suíte >7min —
não bloquear a sessão)
Expected: PASS. Falha pré-existente → abrir issue antes de prosseguir (regra do
change-control).

---

### Task 2: Migration V33 — policy RLS em `transactions`

**Files:**
- Create: `backend/src/main/resources/db/migration/V33__transactions_rls.sql`

- [ ] **Step 1: Escreve a migration**

```sql
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON transactions
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

`current_setting(..., true)` (segundo argumento `missing_ok=true`) evita erro se
`app.tenant_id` não estiver setado — nesse caso a comparação é `NULL = tenant_id` → `NULL`
(nenhuma linha visível), fail-safe por padrão em vez de exceção. Confirmar essa escolha
durante a Task 3 (o teste discriminante deve provar isso, não só assumir).

**Não rodar esta migration isolada** — ela por si só derruba toda a aplicação (nenhuma sessão
tem `app.tenant_id` setado ainda). Só validar em conjunto com a Task 3.

---

### Task 3: `TenantRlsAspect`

**Files:**
- Create: `backend/src/main/java/com/fintech/api/config/TenantRlsAspect.java`
- Test: `backend/src/test/java/com/fintech/api/config/TenantRlsAspectTest.java` (ou
  integrado ao teste discriminante da Task 4 — decidir ao escrever, evitar duplicar setup)

- [ ] **Step 1: Teste discriminante primeiro (RED)**

Escrever o teste de integração que:
1. Popula (ou reusa do seed) 2 tenants com transações.
2. Executa, dentro de uma transação autenticada como tenant A, uma query nativa
   **sem** `WHERE tenant_id` contra `transactions`.
3. Assert: 0 linhas do tenant B retornadas.
4. Um segundo teste (ou o mesmo, parametrizado) roda a MESMA query nativa numa transação sem
   `TenantRlsAspect` ativo (ex.: chamando o repositório diretamente via
   `EntityManager` fora do fluxo do service, ou um profile/flag que desliga o aspect) —
   assert: N > 0 linhas de outro tenant aparecem, provando que sem o aspect a policy sozinha
   (com `app.tenant_id` nunca setado) já bloquearia tudo, e é o aspect que faz a app
   funcionar normalmente.

Run: `./mvnw test -Dtest=TenantRlsAspectTest` (ou nome escolhido)
Expected: **FAIL** (aspect não existe ainda — RED genuíno).

- [ ] **Step 2: Implementa o aspect (GREEN)**

`@Around` em torno de métodos públicos de `TransactionService` anotados `@Transactional`.
Lê o tenant do `SecurityContextHolder`/contexto já usado por `SecurityFilter` (reusar o mesmo
mecanismo, não duplicar). Executa `SET LOCAL app.tenant_id = ?` via
`entityManager.unwrap(Session.class).doWork(...)` ou `jdbcTemplate.execute(...)` — decidir
pela opção que já tem precedente no codebase (buscar uso de `doWork`/JDBC direto antes de
escolher).

Run: `./mvnw test -Dtest=TenantRlsAspectTest`
Expected: PASS.

- [ ] **Step 3: Aplica a migration V33 real e roda o teste completo junto**

Run: `./mvnw test -Dtest=TenantRlsAspectTest,TransactionServiceTest,TransactionControllerTest`
Expected: PASS — prova que Task 2 + Task 3 juntas não quebram nada.

---

### Task 4: Regressão completa

**Files:** nenhum arquivo novo — só execução.

- [ ] **Step 1: Suíte backend completa**

Run: `./scripts/test-summary.sh backend`
Expected: PASS, mesmo resultado da baseline da Task 1 Step 3 (nenhuma regressão).

- [ ] **Step 2: Suíte frontend (não deveria ser afetada, é validação de não-regressão)**

Run: `./scripts/test-summary.sh frontend`
Expected: PASS, inalterado.

---

### Task 5: Documentação

**Files:**
- Modify: `database-schema.md` (linha da V33 na tabela de migrations)
- Modify: `architecture.md` ou seção nova referenciando ADR-006 (decidir o menor diff que
  torna o mecanismo descobrível)

- [ ] **Step 1: Atualiza `database-schema.md`**

Adiciona a linha V33 seguindo o padrão das demais (ver V22, V25 como exemplos de migration
"comportamental", não schema de tabela nova).

- [ ] **Step 2: Referencia ADR-006 em `architecture.md`**

Uma linha apontando para o ADR, no mesmo espírito de outras referências cruzadas já
existentes no arquivo.

---

## Fim do PoC — critério de conclusão (issue #116, parcial)

- [x] Decisão registrada em ADR (`ADR-006`).
- [ ] PoC numa tabela validando que query sem filtro de tenant não retorna dado de outro
      tenant (Task 3, Step 1).

Rollout para as demais tabelas fica para uma spec/plano futuro, fora deste PoC (ver spec,
seção "Fora de escopo").
