---
name: fintech-core-validation-and-qa
description: >-
  O que conta como EVIDÊNCIA no fintech-core e como produzi-la — convenções de teste reais
  (JUnit 5 + Mockito + AssertJ, MockMvc + spring-security-test com teste de 403 por role,
  integração @SpringBootTest contra Postgres local, Vitest com lógica pura fora do TestBed),
  como rodar a suíte (test-summary.sh, mvnw test, ng test), cobertura (JaCoCo, alvo 80%+ em
  lógica de negócio, test:cov), gates de CI (ci-cd.yml, branch protection em main), baseline
  verde antes de plano SDD, SonarQube local (sonar-scan.sh, quality gate via curl) e o dataset
  Família Costa como inventário dourado. Gatilhos: testes, rodar suíte, adicionar teste,
  coverage, cobertura, Vitest, ng test, mvnw test, CI, pipeline, quality gate, SonarQube,
  baseline, 403, MockMvc, fixture. NÃO cobre provar invariantes de domínio por SQL/experimento
  (saldo, tenant, centavos, RRULE — use fintech-core-proof-and-analysis-toolkit) nem
  diagnosticar teste que falha de forma misteriosa (use fintech-core-debugging-playbook).
---

# Validação & QA — o que conta como evidência no fintech-core

> Runbook para engenheiro júnior/pleno (ou agente) com contexto zero. Todo comando abaixo foi
> verificado contra o repositório em **2026-07-04**. Paths relativos assumem a raiz
> `/home/sergio/fintech-core` (o cwd do shell reseta entre comandos — prefira paths absolutos).

## O que conta como evidência aqui

"Funciona" neste projeto significa, nesta ordem de força:

1. **Suíte verde** — backend (JUnit 5) e frontend (Vitest via `ng test`) passando localmente.
2. **CI verde** — os dois jobs de `.github/workflows/ci-cd.yml` (é o que o branch protection de `main` exige).
3. **Teste novo cobrindo o comportamento novo** — cobertura alvo **80%+ na lógica de negócio**, não em boilerplate (regra do CLAUDE.md). Feature sem teste não é entrega completa.
4. **Comportamento observado contra o dataset Família Costa** — dados previsíveis com UUIDs fixos (ver seção do dataset).
5. **SonarQube sem regressão de gate** — verificação local opcional, mas o instrumental existe e está pronto.

"Rodou uma vez no meu terminal" ou "o código parece certo" **não** é evidência.

## Como rodar a suíte (runbook)

| Objetivo | Comando | Observações |
|---|---|---|
| Resumo agregado (recomendado p/ agentes) | `./scripts/test-summary.sh backend` \| `frontend` \| sem arg (ambos) | Saída: 1 linha de totais + 1 linha por classe com falha. Logs completos em `/tmp/fintech-mvn-test.log` e `/tmp/fintech-ng-test.log` |
| Suíte backend completa | `./mvnw -f backend/pom.xml test` | **>7 minutos** — rodar em background, NUNCA bloquear a sessão em foreground |
| Uma classe backend (feedback rápido) | `./mvnw -f backend/pom.xml test -Dtest=TransactionServiceTest` | Segundos a poucos minutos |
| Suíte frontend | `cd frontend && npm test` (= `ng test`) ou `npx ng test --watch=false` | Vitest por baixo do builder `@angular/build:unit-test` |
| Cobertura frontend | `cd frontend && npm run test:cov` | Gera lcov + texto; exclui `src/app/core/api/**` (código gerado pelo Orval) e `**/*.spec.ts` |
| Cobertura backend | `./mvnw -f backend/pom.xml verify` | JaCoCo `prepare-agent` + `report` na fase `verify` → XML em `backend/target/site/jacoco/jacoco.xml` |

**Proibições (cada uma causou loop real de tentativa-erro):**

- **NUNCA `npx vitest` cru para specs de componente** — quebram fora do builder Angular. Sempre `ng test --watch=false` / `npm test`. Só lógica pura em arquivos sem imports Angular sobreviveria ao Vitest direto — e mesmo assim use `ng test`, que roda tudo.
- **NUNCA a suíte backend em foreground bloqueante** — use `test-summary.sh backend`, background, ou `-Dtest=Classe`.
- **Pré-requisito backend: PostgreSQL local de pé** (`docker compose up -d`). Os testes de controller e repositório usam `@SpringBootTest` e conectam em `jdbc:postgresql://localhost:5432/fintech` (`admin`/`secret`) — sem banco, a suíte inteira falha na subida de contexto.

## Backend — as três camadas de teste (34 classes em 2026-07-04)

### 1. Unit de service — JUnit 5 + Mockito + AssertJ

Padrão real (ex.: `backend/src/test/java/com/fintech/api/service/AccountServiceTest.java`):

- `@ExtendWith(MockitoExtension.class)` + `@Mock` repositórios + `@InjectMocks` service.
- Assertions com AssertJ (`assertThat`, `assertThatThrownBy`); `ArgumentCaptor` para verificar o que foi persistido.
- Sem contexto Spring — rápido. É onde vive a maior parte da cobertura de lógica de negócio.

Exemplos grandes para copiar estilo: `TransactionServiceTest`, `InvoiceServiceTest`, `BudgetSummaryServiceTest`, `BudgetCycleServiceTest`.

### 2. Controller — MockMvc + spring-security-test

Padrão real (ex.: `backend/src/test/java/com/fintech/api/controller/MembersControllerTest.java`):

- `@SpringBootTest` + `@Import({ SecurityConfigurations.class, SecurityFilter.class })` — a cadeia de segurança REAL participa do teste.
- `MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()` no `@BeforeEach`.
- Services e `UserRepository`/`TokenService` mockados com `@MockitoBean`; o usuário autenticado é montado à mão com `UserRole` e `Tenant`.

**Teste de 403 por role é OBRIGATÓRIO em endpoint restrito** (metade backend da regra de defesa em profundidade — a regra completa e a metade frontend estão em `fintech-core-change-control`). Exemplo real em `MembersControllerTest`:

- `listMembers_withoutAuth_returns403()` — sem token → `status().isForbidden()`
- `listMembers_withUserRole_returns403()` — role não-ADMIN → `status().isForbidden()`

Ao adicionar endpoint com `hasRole(...)` em `SecurityConfigurations.java`, replique esse par de testes.

### 3. Integração leve — `@SpringBootTest` + `@Transactional` contra o Postgres dev

**Estado real em 2026-07-04: o projeto NÃO usa Testcontainers.** O CLAUDE.md declara Testcontainers como *preferível* a H2, mas não há dependência `testcontainers` no `backend/pom.xml` nem `@Container` em nenhum teste. O estilo efetivo da casa é o de `backend/src/test/java/com/fintech/api/repository/RecurrenceRuleRepositoryTest.java`: `@SpringBootTest` + `@Transactional` (rollback automático ao fim de cada teste) contra o Postgres do `docker compose`, criando as próprias fixtures via repositórios. O javadoc dessa classe diz explicitamente "sem Testcontainers — segue o estilo do projeto". Se você introduzir Testcontainers, isso é mudança de convenção → passe pelo `fintech-core-change-control` e atualize esta skill.

**Config de teste:** `backend/src/test/resources/application-dev.properties` substitui (não faz merge com) o de `src/main` — replique toda propriedade necessária ao editar. Pontos que já causaram falhas misteriosas:

- `spring.flyway.locations=classpath:db/migration` **sem** `db/seed` + `spring.flyway.ignore-migration-patterns=*:missing,*:future` — os seeds dev (V13, V16, V18…) estão aplicados no banco mas ausentes do classpath de teste.
- Pool Hikari limitado (`maximum-pool-size=3`) para múltiplos `@SpringBootTest` não esgotarem `max_connections=100`.

### Fixtures SQL: `seed_base.sql` / `cleanup.sql`

Em `backend/src/test/resources/sql/`. Fixture mínima desenhada para `@Sql(scripts="/sql/seed_base.sql", executionPhase=BEFORE_TEST_METHOD)` com `cleanup.sql` entre testes; cria o usuário de integração de teste (credenciais: ver `fintech-core-build-and-env`). **Em 2026-07-04 nenhuma classe de teste as referencia** — os testes existentes criam fixtures via repositório + rollback. Estão disponíveis para testes de integração futuros que precisem de estado pré-carregado. (Credenciais de dev e semântica completa do dataset: casa é `fintech-core-build-and-env`.)

## Frontend — Vitest e a convenção de lógica pura (36 specs em 2026-07-04)

**Regra estrutural:** lógica de negócio do frontend vive em arquivos **sem nenhum import Angular**, testáveis no Vitest sem `TestBed`. Exemplos reais no repo:

- `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`
- `frontend/src/app/features/transaction/transaction-form/amount-math.ts` e `installment-preview.ts`
- `frontend/src/app/features/transaction/transaction-timeline/timeline-calendar/calendar-utils.ts`, `timeline-horizontal/horizontal-utils.ts`, `timeline-grouped-list/grouped-list.utils.ts`
- `frontend/src/app/features/planning/budget-cycle-current/budget-cycle.utils.ts` e `budget-cycle.error-utils.ts`
- `frontend/src/app/core/csv.utils.ts` e `frontend/src/app/features/invoice/invoice-detail/invoice-detail.utils.ts`

Ao adicionar feature: extraia cálculo/formatação/agrupamento para um `*-utils.ts` puro + spec direto; o componente fica fino.

**Shell components** (que orquestram filhos pesados): spec com `overrideComponent()` para substituir os filhos — exemplo real: `frontend/src/app/features/transaction/transaction-timeline/transaction-timeline.spec.ts`.

**Property-based testing** já está disponível: `fast-check` é devDependency e é usado em `budget-cycle.error-utils.spec.ts` — bom modelo para invariantes de funções puras (ex.: matemática de centavos).

**Cobertura:** `npm run test:cov` = `ng test --watch=false --coverage --coverage-reporters lcovonly --coverage-reporters text --coverage-exclude "src/app/core/api/**" --coverage-exclude "**/*.spec.ts"`. O lcov aterrissa onde o Sonar espera (`sonar.javascript.lcov.reportPaths=coverage/frontend/lcov.info` em `frontend/sonar-project.properties`).

## Gates de CI (`.github/workflows/ci-cd.yml`)

Dispara em push e pull request para `main` e `develop`. Dois jobs:

| Job | O que roda | Detalhe |
|---|---|---|
| `test-backend` | `./mvnw verify` (JDK 21 temurin, cache maven) | Service container `postgres:16-alpine` com db `fintech`, user `admin`, senha `secret` na porta 5432 — espelho exato do docker compose local; se passa local, passa no CI |
| `test-frontend` | `npm ci` → `npm test` → `npm run build -- --configuration=production` (Node 22) | O build de produção também é gate — erro de template/tipo que só aparece no AOT falha aqui |

**Não há job de deploy no workflow**: Railway e Netlify fazem auto-deploy ao detectar push em `main`. O portão de qualidade é o **branch protection**: merge em `main` só com `test-backend` e `test-frontend` verdes, e só via PR (nunca merge direto de `develop`). Fluxo de PR: `fintech-core-change-control`.

## Baseline verde (regra do CLAUDE.md — esta skill é a casa da regra)

Antes de iniciar qualquer plano SDD: rode a suíte (`./scripts/test-summary.sh`). Se houver falha pré-existente, **abra issue imediatamente** — não tolere "idêntico ao baseline" por 7 tasks (caso real de auditoria de sessão: cada task vira uma negociação sobre o que é regressão). Todo teste que você encontrar quebrado sem ser causa sua vira issue, não ruído aceito.

## SonarQube local

Instância Community em `http://localhost:9000`. Projetos `fintech-core-backend` e `fintech-core-frontend`, auto-provisionados no primeiro scan.

**Pré-requisitos:** SonarQube de pé; `SONAR_TOKEN` no `.env` ou `.env.local` (token de **análise** `sqa_` gerado na UI em My Account → Security — token `sqp_` não serve); Docker/Postgres de pé para o backend (o scan roda `./mvnw verify`, ou seja, a suíte completa contra o banco local — os >7 min se aplicam).

```bash
./scripts/sonar-scan.sh            # backend + frontend
./scripts/sonar-scan.sh backend    # ./mvnw -B verify sonar:sonar (JaCoCo XML → Sonar)
./scripts/sonar-scan.sh frontend   # npm run test:cov && npm run sonar (sonar-scanner lê sonar-project.properties)
```

O pom aponta `sonar.coverage.jacoco.xmlReportPaths` para `target/site/jacoco/jacoco.xml`; o frontend exclui `src/app/core/api/**` (gerado) do escopo do Sonar.

**Consultar gate e medidas — SEMPRE via curl.** O MCP do SonarQube só tem privilégio para listar issues; `Insufficient privileges` no MCP não é transiente, não re-tente. A skill **`/sonar-status`** faz o relatório completo. O curl essencial:

```bash
cd /home/sergio/fintech-core
TOKEN=$(grep '^SONAR_TOKEN=' .env .env.local 2>/dev/null | head -1 | cut -d= -f2)
curl -su "$TOKEN:" "http://localhost:9000/api/qualitygates/project_status?projectKey=fintech-core-backend"
curl -su "$TOKEN:" "http://localhost:9000/api/measures/component?component=fintech-core-backend&metricKeys=bugs,vulnerabilities,code_smells,coverage,duplicated_lines_density"
```

Opcional: hook `.githooks/post-merge` lembra (ou roda, com `SONAR_AUTO_SCAN=1` no `.env`) a análise a cada merge na `develop` — ativar com `git config core.hooksPath .githooks`.

## Dataset Família Costa como inventário dourado

Os seeds de dev (`V13` geral + `V16` planejamento, perfil `dev`) formam um dataset realista com **UUIDs predefinidos e valores conhecidos** — trate-o como oráculo de evidência manual/exploratória:

- Resultado de endpoint contra o seed é **previsível e re-verificável**: qualquer pessoa que rode `docker compose up -d` + backend em perfil dev vê os mesmos saldos, faturas e ciclos. "Com o seed carregado, `GET /api/dashboard/summary?month=2026-06` retorna X" é evidência reproduzível (atenção: o parâmetro é `month`, required — o `summary.md` ainda cita `period`, que retorna 400); "testei com meus dados" não é.
- UUIDs fixos permitem requests copy-paste: a coleção `docs/http/seed-dataset.http` referencia as entidades do seed diretamente — use-a como suíte de smoke manual.
- Nunca edite V13/V16 já aplicados (checksum Flyway quebra e o backend não inicia) — correção via nova versão de seed (padrão do V18).

**Regra de atualização obrigatória** (nova tabela/coluna/endpoint → atualizar seed / `seed_base.sql` / `seed-dataset.http` como parte da entrega, nunca "depois"): a tabela situação→ação vive em `fintech-core-change-control`. Semântica completa do dataset e credenciais: `fintech-core-build-and-env` e a spec `docs/superpowers/specs/2026-06-09-test-dataset-design.md`.

## Quando NÃO usar esta skill

| Situação | Use em vez desta |
|---|---|
| Teste falha de forma misteriosa (checksum Flyway, drift Orval, contexto não sobe, pitfall Zoneless) | `fintech-core-debugging-playbook` |
| Provar um invariante de domínio (isolamento de tenant em query, soma de parcelas em centavos, race do getOrCreate, expansão RRULE) | `fintech-core-proof-and-analysis-toolkit` |
| Regras de entrega — spec/plano SDD, worktree, migration, commit, PR, atualização do dataset como parte da entrega | `fintech-core-change-control` |
| Subir o ambiente do zero, credenciais, docker compose, regeneração Orval | `fintech-core-build-and-env` |
| Teoria do domínio (por que saldo só conta PAID, ciclo de fatura, Modelo A) | `fintech-domain-reference` |

## Proveniência e manutenção

Fontes: CLAUDE.md, dataset.md, commands.md, `scripts/test-summary.sh`, `scripts/sonar-scan.sh`, `.github/workflows/ci-cd.yml`, `backend/pom.xml`, `backend/src/test/**`, `frontend/package.json`, `frontend/vitest.config.ts`, `frontend/sonar-project.properties`, `frontend/angular.json`. Fatos datados de 2026-07-04; skill revisada em 2026-07-05 (parâmetro `month` do dashboard, description, cross-refs). Re-verificação de uma linha:

- Testcontainers segue ausente? `grep -c testcontainers backend/pom.xml` (esperado: 0)
- `seed_base.sql` segue sem uso? `grep -rl seed_base backend/src/test/java | wc -l` (esperado: 0)
- Contagem de testes: `find backend/src/test/java -name "*Test*.java" | wc -l` e `find frontend/src -name "*.spec.ts" | wc -l`
- Scripts de teste/scan mudaram? `git log --oneline -3 -- scripts/test-summary.sh scripts/sonar-scan.sh`
- CI mudou? `git log --oneline -3 -- .github/workflows/ci-cd.yml`
- Excludes de cobertura: `grep test:cov frontend/package.json`
- Exemplo de 403 por role: `grep -n isForbidden backend/src/test/java/com/fintech/api/controller/MembersControllerTest.java`
