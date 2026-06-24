# SonarQube Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Incorporar a instância local do SonarQube Community ao fluxo de dev: análise estática + cobertura para backend (Java) e frontend (TS), acionável sob demanda e automaticamente a cada merge na `develop`.

**Architecture:** Um script orquestrador (`scripts/sonar-scan.sh`) é o ponto de entrada único, chamado manualmente (sob demanda) ou por um hook `post-merge` versionado (só dispara na `develop`, em background). Backend usa JaCoCo + `sonar-maven-plugin`; frontend usa o coverage nativo do builder `@angular/build:unit-test` (lcov) + `sonarqube-scanner`. Dois projetos Sonar separados.

**Tech Stack:** Maven, JaCoCo 0.8.13, sonar-maven-plugin 5.x, Angular 21 `@angular/build:unit-test` (Vitest), `@vitest/coverage-v8`, `sonarqube-scanner` (npm), bash.

## Global Constraints

Estes contratos são compartilhados entre tarefas — todo passo os assume:

- **Chaves de projeto Sonar:** `fintech-core-backend` (Java) e `fintech-core-frontend` (TS). Verbatim.
- **Variáveis de ambiente:** `SONAR_HOST_URL` (default `http://localhost:9000`) e `SONAR_TOKEN` (obrigatória; vem do `.env`). Nunca hardcodar o token.
- **Caminho do orquestrador:** `scripts/sonar-scan.sh` — consumido pelo hook `.githooks/post-merge`.
- **Scripts npm do frontend:** `test:cov` (gera `frontend/coverage/lcov.info`) e `sonar` (roda o scanner). Consumidos por `scan_frontend()` no orquestrador.
- **Git/commits:** PT-BR, imperativo, **sem** `Co-Authored-By`. Trabalho na branch `feat/sonarqube-integration` (worktree já criada).
- **Segredos:** `.env` é gitignored. `frontend/coverage/`, `.scannerwork/` e `.sonar-scan.log` **não** podem ser versionados.
- **Sem mudança de schema** → o dataset de testes (V13/V16/seed_base) **não** é afetado por esta feature.

---

### Task 1: Backend — JaCoCo + sonar-maven-plugin (`backend/pom.xml`)

**Files:**
- Modify: `backend/pom.xml` (bloco `<properties>` em `:29-31` e `<build><plugins>` em `:112-227`)

**Interfaces:**
- Produces: relatório `backend/target/site/jacoco/jacoco.xml` (lido pelo Sonar via `sonar.coverage.jacoco.xmlReportPaths`); goal `sonar:sonar` disponível via `./mvnw`.
- Consumes: nada de tarefas anteriores.

- [ ] **Step 1: Adicionar properties do Sonar/JaCoCo ao pom**

Em `backend/pom.xml`, substituir o bloco `<properties>`:

```xml
	<properties>
		<java.version>21</java.version>
		<!-- SonarQube: chave do projeto e caminho do relatório JaCoCo.
		     Manter a chave aqui também é onde o MCP do Sonar resolve o projeto. -->
		<sonar.projectKey>fintech-core-backend</sonar.projectKey>
		<sonar.projectName>Fintech Core Backend</sonar.projectName>
		<sonar.coverage.jacoco.xmlReportPaths>${project.build.directory}/site/jacoco/jacoco.xml</sonar.coverage.jacoco.xmlReportPaths>
	</properties>
```

- [ ] **Step 2: Adicionar os plugins JaCoCo e Sonar**

Em `backend/pom.xml`, dentro de `<build><plugins>`, após o bloco do `openapi-generator-maven-plugin` (antes de `</plugins>` na linha 227), inserir:

```xml
			<!-- JaCoCo: prepare-agent instrumenta os testes; report gera o XML
			     que o Sonar lê para calcular cobertura. -->
			<plugin>
				<groupId>org.jacoco</groupId>
				<artifactId>jacoco-maven-plugin</artifactId>
				<version>0.8.13</version>
				<executions>
					<execution>
						<id>prepare-agent</id>
						<goals>
							<goal>prepare-agent</goal>
						</goals>
					</execution>
					<execution>
						<id>report</id>
						<phase>verify</phase>
						<goals>
							<goal>report</goal>
						</goals>
					</execution>
				</executions>
			</plugin>

			<!-- Scanner do SonarQube via Maven. Versão pinada para não puxar RELEASE instável. -->
			<plugin>
				<groupId>org.sonarsource.scanner.maven</groupId>
				<artifactId>sonar-maven-plugin</artifactId>
				<version>5.1.0.4751</version>
			</plugin>
```

> Se o Maven não resolver `5.1.0.4751`, consultar a última versão de `org.sonarsource.scanner.maven:sonar-maven-plugin` no Maven Central e usá-la. Mesma ideia para `jacoco-maven-plugin` se `0.8.13` falhar (fallback `0.8.12`).

- [ ] **Step 3: Rodar o build e verificar o relatório de cobertura**

Pré-requisito: Docker de pé (`docker compose up -d`), pois `verify` roda a suíte com Testcontainers.

Run: `cd backend && ./mvnw -B verify`
Expected: `BUILD SUCCESS` e o arquivo de cobertura existe.

Run: `ls backend/target/site/jacoco/jacoco.xml`
Expected: o caminho é listado (arquivo existe).

- [ ] **Step 4: Commit**

```bash
git add backend/pom.xml
git commit -m "build(backend): adiciona JaCoCo e sonar-maven-plugin para análise no Sonar"
```

---

### Task 2: Frontend — coverage (lcov) + config do Sonar (`frontend/`)

**Files:**
- Modify: `frontend/package.json` (scripts em `:4-11`, devDependencies em `:39-49`)
- Create: `frontend/sonar-project.properties`
- Modify: `.gitignore` (raiz do repo)

**Interfaces:**
- Produces: `frontend/coverage/lcov.info` (via `npm run test:cov`); script `npm run sonar`; arquivo `sonar-project.properties` com `sonar.projectKey=fintech-core-frontend`.
- Consumes: nada de tarefas anteriores.

- [ ] **Step 1: Instalar as dependências de coverage e scanner**

Run: `cd frontend && npm install -D @vitest/coverage-v8@4 sonarqube-scanner`
Expected: `package.json` ganha as duas devDependencies; `npm` finaliza sem erro.

> `@vitest/coverage-v8` é o engine de cobertura que o builder do Angular (Vitest) exige para o provider `v8`. `sonarqube-scanner` traz o binário `sonar-scanner` em `node_modules/.bin/` (sem instalação global).

- [ ] **Step 2: Confirmar os nomes exatos das flags de coverage**

Run: `cd frontend && npx ng test --help`
Expected: a saída lista as opções do builder `@angular/build:unit-test`, incluindo `--coverage`, `--coverage-reporters` e `--coverage-exclude`. (Confirmado no schema da versão 21.2.15; este passo só valida que não mudaram no seu ambiente.)

- [ ] **Step 3: Adicionar os scripts `test:cov` e `sonar`**

Em `frontend/package.json`, substituir o bloco `"scripts"`:

```json
  "scripts": {
    "ng": "ng",
    "start": "ng serve",
    "build": "ng build",
    "watch": "ng build --watch --configuration development",
    "test": "ng test",
    "test:cov": "ng test --coverage --coverage-reporters lcovonly --coverage-reporters text --coverage-exclude \"src/app/core/api/**\" --coverage-exclude \"**/*.spec.ts\"",
    "sonar": "sonar-scanner",
    "api:generate": "orval --config orval.config.ts"
  },
```

> `lcovonly` gera `coverage/lcov.info` (o que o Sonar lê); `text` dá feedback no terminal. O Orval gerado e os specs ficam fora do denominador de cobertura.

- [ ] **Step 4: Rodar o coverage e verificar o lcov**

Run: `cd frontend && npm run test:cov`
Expected: testes passam; ao final, o arquivo de cobertura existe.

Run: `ls frontend/coverage/lcov.info`
Expected: o caminho é listado (arquivo existe).

> Se `lcov.info` **não** aparecer (o builder ignorou `lcovonly` e usou outro reporter), checar onde o coverage foi escrito com `find frontend -name "lcov*.info" -not -path "*/node_modules/*"` e ajustar `sonar.javascript.lcov.reportPaths` (Step 5) para o caminho real. Se o comando ficar em modo watch, acrescentar `--no-watch` ao script `test:cov`.

- [ ] **Step 5: Criar o `sonar-project.properties` do frontend**

Create `frontend/sonar-project.properties`:

```properties
sonar.projectKey=fintech-core-frontend
sonar.projectName=Fintech Core Frontend
sonar.sources=src
sonar.tests=src
sonar.test.inclusions=**/*.spec.ts
# Exclui da análise o cliente HTTP gerado pelo Orval (código gerado não se cobra)
sonar.exclusions=src/app/core/api/**
sonar.javascript.lcov.reportPaths=coverage/lcov.info
sonar.sourceEncoding=UTF-8
```

> `sonar.host.url` e o token **não** ficam aqui — vêm do ambiente (`SONAR_HOST_URL`/`SONAR_TOKEN`), exportados pelo orquestrador na Task 3. Specs entram como `sonar.tests` (código de teste), não em `sonar.exclusions`.

- [ ] **Step 6: Ignorar artefatos de coverage/scanner no git**

Em `.gitignore` (raiz), acrescentar ao final:

```gitignore

# SonarQube / coverage
frontend/coverage/
.scannerwork/
.sonar-scan.log
```

- [ ] **Step 7: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/sonar-project.properties .gitignore
git commit -m "test(frontend): habilita coverage lcov e configura projeto Sonar"
```

---

### Task 3: Orquestrador `sonar-scan.sh` + `.env.template` + docs

**Files:**
- Create: `scripts/sonar-scan.sh`
- Modify: `scripts/.env.template` (`:1-15`)
- Modify: `commands.md`

**Interfaces:**
- Consumes: chaves de projeto, env vars (`SONAR_HOST_URL`/`SONAR_TOKEN`), e os scripts npm `test:cov`/`sonar` (Task 2); goal `sonar:sonar` (Task 1).
- Produces: comando `./scripts/sonar-scan.sh [backend|frontend|all]` — consumido pelo hook (Task 4).

- [ ] **Step 1: Criar o script orquestrador**

Create `scripts/sonar-scan.sh`:

```bash
#!/bin/bash

# fintech-core — Análise local no SonarQube (Community, instância local).
#
# Uso:
#   ./scripts/sonar-scan.sh            # back + front
#   ./scripts/sonar-scan.sh backend    # só Java
#   ./scripts/sonar-scan.sh frontend   # só TypeScript
#
# Pré-requisitos:
#   - Instância do SonarQube de pé (default http://localhost:9000)
#   - SONAR_TOKEN no .env (token de análise gerado na UI do Sonar)
#   - Docker de pé para o backend (mvn verify roda a suíte com Testcontainers)

set -euo pipefail

cd "$(dirname "$0")/.."   # raiz do repo

# --- Carregar .env (mesmo padrão do sync-tenant.sh) ---
load_env() {
  local env_file=$1
  if [ -f "$env_file" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      [[ "$line" =~ ^#.*$ || -z "$line" ]] && continue
      export "$line"
    done < "$env_file"
  fi
}
load_env ".env"
load_env ".env.local"

export SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9000}"

if [ -z "${SONAR_TOKEN:-}" ]; then
  echo "❌ SONAR_TOKEN não definida. Gere um token na UI do Sonar"
  echo "   (My Account → Security → Generate Token) e adicione ao .env:"
  echo "   SONAR_TOKEN=seu_token"
  exit 1
fi

target="${1:-all}"

scan_backend() {
  echo "▶ Backend → $SONAR_HOST_URL (projeto fintech-core-backend)"
  ( cd backend && ./mvnw -B verify sonar:sonar \
      -Dsonar.host.url="$SONAR_HOST_URL" \
      -Dsonar.token="$SONAR_TOKEN" )
}

scan_frontend() {
  echo "▶ Frontend → $SONAR_HOST_URL (projeto fintech-core-frontend)"
  # sonar-scanner lê SONAR_HOST_URL e SONAR_TOKEN do ambiente (já exportados acima)
  ( cd frontend && npm run test:cov && npm run sonar )
}

case "$target" in
  backend)  scan_backend ;;
  frontend) scan_frontend ;;
  all)      scan_backend; scan_frontend ;;
  *) echo "Uso: $0 [backend|frontend|all]"; exit 1 ;;
esac

echo "✅ Análise concluída. Resultados em $SONAR_HOST_URL"
```

- [ ] **Step 2: Tornar o script executável**

Run: `chmod +x scripts/sonar-scan.sh`
Expected: sem saída (sucesso).

- [ ] **Step 3: Verificar o guard de token (falha cedo, sem servidor)**

Run: `SONAR_TOKEN= ./scripts/sonar-scan.sh backend; echo "exit=$?"`
Expected: imprime a mensagem `❌ SONAR_TOKEN não definida...` e `exit=1`.

> Se você já tiver `SONAR_TOKEN` no `.env`, o `load_env` vai sobrescrever o vazio do inline. Para validar o guard isoladamente, rode num diretório sem `.env` ou comente a linha temporariamente. O objetivo deste passo é só confirmar que o script falha cedo e claro quando não há token.

- [ ] **Step 4: Adicionar as variáveis ao `.env.template`**

Em `scripts/.env.template`, acrescentar ao final:

```
# --- SonarQube (análise local) ---
# URL da instância local (default se omitido: http://localhost:9000)
SONAR_HOST_URL=http://localhost:9000
# Token de análise gerado na UI do Sonar (My Account → Security → Generate Token)
SONAR_TOKEN=your_sonar_token_here
```

- [ ] **Step 5: Documentar em `commands.md`**

Em `commands.md`, após a seção `### Frontend` (antes de `### Health Check`), inserir:

```markdown
### Análise de Código (SonarQube)

Instância local do SonarQube Community. Pré-requisito: `SONAR_TOKEN` no `.env`
(gerar na UI: My Account → Security). Backend exige Docker de pé (a análise roda
`mvn verify` com Testcontainers).

```bash
./scripts/sonar-scan.sh            # back + front
./scripts/sonar-scan.sh backend    # só Java   (projeto fintech-core-backend)
./scripts/sonar-scan.sh frontend   # só TS     (projeto fintech-core-frontend)
```

Projetos são auto-provisionados no 1º scan. Resultados em `http://localhost:9000`.
A análise também dispara automaticamente em background a cada merge na `develop`
(hook `.githooks/post-merge`; ativar uma vez com `git config core.hooksPath .githooks`).
```

- [ ] **Step 6: Commit**

```bash
git add scripts/sonar-scan.sh scripts/.env.template commands.md
git commit -m "feat(scripts): adiciona orquestrador sonar-scan e documenta o fluxo"
```

---

### Task 4: Hook `post-merge` na develop (`.githooks/`)

**Files:**
- Create: `.githooks/post-merge`

**Interfaces:**
- Consumes: `scripts/sonar-scan.sh` (Task 3).
- Produces: hook que, ativado via `core.hooksPath`, dispara o scan em background só na `develop`.

- [ ] **Step 1: Criar o hook**

Create `.githooks/post-merge`:

```bash
#!/bin/bash
# fintech-core — dispara análise do SonarQube após merges na develop.
# Ativar uma vez: git config core.hooksPath .githooks
#
# Só age na develop — a única branch que o Sonar Community analisa.
# Roda em background para não travar o terminal após merge/pull.

branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" != "develop" ] && exit 0

repo_root=$(git rev-parse --show-toplevel)
log="$repo_root/.sonar-scan.log"

echo "🔍 Merge na develop — análise do Sonar em background (log: .sonar-scan.log)"
# ponytail: mvn verify roda a suíte toda (Testcontainers) — é pesado; o background
# mitiga. Se incomodar, criar uma variante do sonar-scan.sh com -DskipITs.
nohup "$repo_root/scripts/sonar-scan.sh" all > "$log" 2>&1 &
```

- [ ] **Step 2: Tornar o hook executável**

Run: `chmod +x .githooks/post-merge`
Expected: sem saída (sucesso).

- [ ] **Step 3: Verificar o guard de branch (silencioso fora da develop)**

Pré-requisito: você está na branch `feat/sonarqube-integration` (não `develop`).

Run: `bash .githooks/post-merge; echo "exit=$?"`
Expected: **nenhuma** saída de scan e `exit=0` (o guard barrou por não estar na develop). O disparo real na develop é validado na Task 5.

- [ ] **Step 4: Commit**

```bash
git add .githooks/post-merge
git commit -m "feat(git): adiciona hook post-merge que analisa o Sonar na develop"
```

---

### Task 5: Verificação end-to-end ao vivo (instância local)

Sem código novo — é o portão de integração contra a sua instância real. Requer o SonarQube de pé e o `SONAR_TOKEN` no `.env`.

**Files:** nenhum (runbook de verificação).

**Interfaces:**
- Consumes: tudo das Tasks 1–4.

- [ ] **Step 1: Confirmar que a instância está no ar**

Run: `curl -s http://localhost:9000/api/system/status`
Expected: JSON com `"status":"UP"`.

- [ ] **Step 2: Garantir o token no `.env`**

Gerar um token na UI (My Account → Security → Generate Token) e adicionar ao `.env` da raiz:
`SONAR_TOKEN=...`. (O `.env` é gitignored — o token nunca é versionado.)

- [ ] **Step 3: Rodar a análise do backend**

Pré-requisito: `docker compose up -d`.

Run: `./scripts/sonar-scan.sh backend`
Expected: `BUILD SUCCESS`; no fim, `✅ Análise concluída`. Na UI, o projeto `fintech-core-backend` aparece com cobertura > 0%.

- [ ] **Step 4: Rodar a análise do frontend**

Run: `./scripts/sonar-scan.sh frontend`
Expected: scanner conclui (`EXECUTION SUCCESS`). Na UI, `fintech-core-frontend` aparece com cobertura > 0% e **sem** os arquivos de `core/api/**` na análise.

- [ ] **Step 5: Ativar o hook e validar o disparo na develop**

Run: `git config core.hooksPath .githooks`
Expected: sem saída.

Depois (após esta feature ser mergeada na develop, ou num teste de merge na develop): confirmar que o terminal **não** trava e que `.sonar-scan.log` é criado com a saída do scan.

- [ ] **Step 6: (Opcional) Resumo via MCP**

Pedir ao assistente um resumo via MCP do SonarQube: status do quality gate e principais issues/hotspots de cada projeto (`mcp__sonarqube__get_project_quality_gate_status`, `mcp__sonarqube__search_sonar_issues_in_projects`).

- [ ] **Step 7: Finalização**

Indicar que a branch `feat/sonarqube-integration` está pronta e sugerir o merge na `develop` (conforme `git-operator.md`). Sem commit adicional aqui.

---

## Self-Review

**Spec coverage:**
- Gatilho sob demanda → Task 3 (script). ✔
- Gatilho hook na develop → Task 4. ✔
- Dois projetos separados → keys em Task 1 (pom) e Task 2 (properties). ✔
- Cobertura back → Task 1 (JaCoCo). ✔
- Cobertura front → Task 2 (lcov). ✔
- Exclusão Orval → Task 2 (`sonar.exclusions` + `--coverage-exclude`). ✔
- Token via `.env`, nunca hardcoded → Task 3 (guard + `.env.template`). ✔
- Auto-provisionamento + quality gate padrão → Task 5 (verificação). ✔
- Host default localhost:9000 → Task 3 (`SONAR_HOST_URL` default). ✔
- Fora de escopo (CI, SonarLint, gate custom) → não há tarefa, correto. ✔
- Docs (commands.md) → Task 3 Step 5. ✔

**Placeholder scan:** sem TBD/TODO; toda flag e versão é concreta, com fallback explícito onde havia incerteza de versão (sonar-maven-plugin, jacoco, caminho do lcov). ✔

**Type/contract consistency:** `scripts/sonar-scan.sh` (Task 3) é o mesmo caminho consumido pelo hook (Task 4); scripts npm `test:cov`/`sonar` (Task 2) batem com os chamados em `scan_frontend()` (Task 3); chaves `fintech-core-backend`/`fintech-core-frontend` idênticas em pom, properties e docs; env vars `SONAR_HOST_URL`/`SONAR_TOKEN` consistentes entre script, `.env.template` e backend `-Dsonar.*`. ✔
