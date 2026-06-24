# Integração SonarQube Community ao fluxo de desenvolvimento

**Data:** 2026-06-24
**Status:** Aprovado (design)
**Branch:** `feat/sonarqube-integration`

## Contexto e restrições

Instância **local** do SonarQube **Community Edition** rodando na máquina do dev. Objetivo:
incorporar análise estática + cobertura ao fluxo, sem fricção e respeitando o workflow de
worktree por feature.

Duas restrições da edição/topologia moldam todo o desenho:

1. **Community analisa apenas uma branch** (a principal). Análise por branch e *PR decoration*
   são exclusivos das edições pagas. Com worktree por feature, o Sonar **não** analisa cada
   branch isolada — entrega uma foto consolidada da branch de integração (`develop`).
2. **Instância é local.** O runner do GitHub Actions (nuvem) **não alcança** `localhost`. Logo,
   nada de step de Sonar no CI atual — a análise é **local**, sob demanda ou via git hook.

O **MCP do SonarQube já está conectado** ao Claude Code (ferramentas `mcp__sonarqube__*`),
então a consulta de resultados a partir do assistente já tem trilho — não exige código novo.

## Decisões

| Decisão | Escolha | Por quê |
|---------|---------|---------|
| Gatilho | Sob demanda (local) **+** hook `post-merge` na `develop` | Controle no dia a dia + foto consolidada automática a cada integração |
| Escopo | Dois projetos Sonar separados | Scanners e quality gates distintos por stack (Java ≠ TS) |
| Cobertura | Back **e** front | Cobertura é valor central do projeto (meta 80%+ em lógica de negócio) |
| Host | `http://localhost:9000` (configurável via `.env`) | Default são da instância local |
| Auth | `SONAR_TOKEN` lido de `.env` | Nunca hardcodar segredo; reusa o `.env` que os scripts de DB já consomem |
| Provisionamento | Auto-provisionado no 1º scan | Sem setup manual de projeto na UI |
| Quality gate | "Sonar way" padrão | Gate custom seria especulativo agora |

### Chaves de projeto
- Backend: `fintech-core-backend`
- Frontend: `fintech-core-frontend`

## Arquitetura

```
scripts/sonar-scan.sh [backend|frontend|all]   ← ponto de entrada único (DRY)
        ▲                         ▲
        │ manual (sob demanda)    │ automático
   você roda                .githooks/post-merge (só na develop, em background)
```

### Componente 1 — Backend: coverage + análise (`backend/pom.xml`)

- **JaCoCo plugin**: `prepare-agent` (instrumenta os testes) + `report` ligado ao `verify`
  → gera `target/site/jacoco/jacoco.xml`.
- **sonar-maven-plugin**: versão **pinada** (evita resolver `RELEASE` instável).
- Props em `<properties>` do pom:
  - `sonar.projectKey=fintech-core-backend`
  - `sonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml`
  - *(Manter a chave no pom também é onde o MCP do Sonar resolve o projeto.)*
- Invocação: `./mvnw verify sonar:sonar`.

### Componente 2 — Frontend: coverage + análise (`frontend/`)

- Dev deps: `@vitest/coverage-v8` (gera `coverage/lcov.info`) e `sonarqube-scanner`
  (roda o scanner sem CLI global instalado).
- `vitest.config.ts`: liga coverage (provider `v8`, reporters `text` + `lcov`,
  `reportsDirectory: 'coverage'`).
- `sonar-project.properties`:
  - `sonar.projectKey=fintech-core-frontend`
  - `sonar.sources=src`, `sonar.tests=src`, `sonar.test.inclusions=**/*.spec.ts`
  - `sonar.javascript.lcov.reportPaths=coverage/lcov.info`
  - `sonar.exclusions=src/app/core/api/**` (cliente Orval **gerado** — não cobrar qualidade de código gerado)
- Scripts npm: `test:cov` (vitest run --coverage) e `sonar` (sonarqube-scanner).

### Componente 3 — Orquestrador (`scripts/sonar-scan.sh`)

- Argumento opcional: `backend` | `frontend` | `all` (default `all`).
- Carrega `.env` da raiz; **valida** `SONAR_TOKEN` (falha cedo com mensagem clara se ausente).
- Exporta `SONAR_HOST_URL`/`SONAR_TOKEN` e dispara o(s) scanner(s) do(s) lado(s) pedido(s).
- É o comando que o dev roda sob demanda antes de pedir merge na `develop`.

### Componente 4 — Hook de merge (`.githooks/post-merge`)

- Versionado no repo; ativado uma vez com `git config core.hooksPath .githooks`.
- **Guarda:** só age se `git rev-parse --abbrev-ref HEAD == develop`.
- Roda `scripts/sonar-scan.sh all` em **background** (log em arquivo) para não bloquear
  `git merge` / `git pull`.
- **Ceiling conhecido** (`ponytail:`): `mvn verify` roda a suíte inteira (Testcontainers/Docker),
  é pesado; background mitiga; se incomodar, aliviar com `-DskipITs`.
- **Efeito colateral aceito:** `git pull` na `develop` também é um merge → dispara o hook.
  Desejável (foto fresca após puxar), mas documentado.

### Componente 5 — Env (`scripts/.env.template`)

- Acrescenta `SONAR_HOST_URL` (default `http://localhost:9000`) e `SONAR_TOKEN` (vazio, a preencher).

### Componente 6 — Consulta via MCP (sem código)

Após um scan, o assistente consulta a instância local: status do quality gate, issues
(bugs/smells/vulnerabilidades), security hotspots e duplicação — e entrega resumo acionável.

## Fora de escopo (e por quê)

- **Step de Sonar no CI** — runner na nuvem não alcança o localhost.
- **SonarLint / connected mode** — IDE em tempo real não foi pedido.
- **Quality gate custom** (ex: falhar abaixo de 80%) — decisão adiada; "Sonar way" serve por ora.

## Critérios de sucesso

1. `./scripts/sonar-scan.sh backend` publica análise + cobertura do Java no projeto
   `fintech-core-backend` da instância local.
2. `./scripts/sonar-scan.sh frontend` publica análise + cobertura do TS no projeto
   `fintech-core-frontend`, com o cliente Orval excluído.
3. `./scripts/sonar-scan.sh` (sem arg) faz os dois.
4. Após `git config core.hooksPath .githooks`, um merge na `develop` dispara o scan em
   background sem travar o terminal; um merge em qualquer outra branch **não** dispara.
5. Segredo (`SONAR_TOKEN`) nunca aparece versionado — só no `.env` local (gitignored).

## Documentação a atualizar na entrega

- `commands.md` — seção curta "Análise de Código (SonarQube)" com os comandos.
- `CLAUDE.md` — uma linha no roteador apontando para este fluxo, se fizer sentido.
- (Sem mudança de schema → seed de testes não é afetado.)
