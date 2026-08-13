## Common Commands

### Infrastructure
```bash
docker compose up -d          # Start PostgreSQL + pgAdmin
```

### Backend
```bash
cd backend
./mvnw spring-boot:run        # Run API (localhost:8080)
./mvnw test                   # Run tests
./mvnw generate-sources       # Regenerate OpenAPI interfaces
./mvnw clean install          # Full build
```

### Frontend
```bash
cd frontend
npm install                   # Install dependencies
npm start                     # Dev server (localhost:4200)
npm test                      # Run Vitest
npm run api:generate          # Regenerate API client from OpenAPI spec
```

### Scripts de Agente (atalhos anti-fricção)
```bash
./scripts/api-sync.sh              # Pipeline completo do contrato: spec → static → generate-sources → orval → limpa auth.service.ts
./scripts/test-summary.sh          # Roda backend + frontend e imprime só o resumo agregado (logs em /tmp)
./scripts/test-summary.sh backend  # Só Maven/surefire (a suíte completa demora >7 min)
./scripts/clean-worktrees.sh       # Lista/remove worktrees órfãs com confirmação (rodar da raiz estável)
```

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

Opcional: o hook `.githooks/post-merge` lembra (ou roda) a análise a cada merge na
`develop`. Ativar uma vez: `git config core.hooksPath .githooks`. Por padrão só lembra;
para auto-scan em background, defina `SONAR_AUTO_SCAN=1` no `.env` (pesa — `mvn verify`).

### CI/CD — o que dispara o quê (local vs remote)

Pipeline em `.github/workflows/ci-cd.yml`. **Git local não dispara nada** — commit ou merge
na `develop`/`main` local só move ponteiro no disco. A pipeline vive no GitHub e só acorda
quando o **remote recebe push** (e um merge de PR pelo GitHub é um push no branch de destino).

`on: push` e `on: pull_request` para `main`/`develop`. Condições dos jobs:

| Job | Dispara quando | Runner | Efeito |
|-----|----------------|--------|--------|
| Testes (back+front) | qualquer **push ou PR** em develop/main | GitHub-hosted | — |
| Build & Push (imagens → GHCR) | só **push** em develop/main (não em PR) | GitHub-hosted (`ubuntu-latest`) | pusha `ghcr.io/sergiohcosta/fintech-core-*:sha-<sha>` |
| Deploy (dev) | **push em `develop`** | self-hosted (k3s) | atualiza namespace `dev` |
| Deploy (hmg) | **push em `main`** | self-hosted (k3s) | atualiza namespace `hmg` |
| Deploy (prod) | **push em `main`**, mas **pausa no gate manual** | self-hosted (k3s) | atualiza `prod` só após aprovação |
| Release (nomear versão) | **push de tag `v*`** (`release.yml`) | GitHub-hosted | re-tagga a imagem `sha-<sha>` como `vX.Y.Z` no GHCR (sem rebuild) + cria GitHub Release |

Consequências práticas:
- **`git push origin develop`** (ou merge local + push) → testes → build GHCR → **deploy-dev**. `dev` atualiza sozinho.
- **Merge de PR para `main`** (ou push em main) → testes → build GHCR → **deploy-hmg** → **deploy-prod (Waiting)**. `hmg` atualiza sozinho; `prod` fica aguardando aprovação (ver abaixo).
- **PR nunca faz build nem deploy** — só roda testes (evento `pull_request`). Build/deploy exigem push no branch real.
- **1 push na `develop` gera 2 runs:** uma de `push` (build + deploy-dev) e, se houver PR `develop→main` aberto, uma de `pull_request` (só testes). Não é bug.
- Deploy usa `kustomize edit set image` para apontar a tag `sha-<sha>` da run; o kubelet puxa do GHCR (packages públicos → sem `imagePullSecret`). Manifests em `homelab-k8s/projects/fintech-core/`.
- **Deploy é verificado** (reusable workflow `.github/workflows/deploy-env.yml`): após aplicar, espera o `rollout status` ficar `Ready` (timeout 240s) e roda um **smoke test** (curl no `/actuator/health` do backend e no frontend, via Service). Se o rollout ou o smoke falham, faz **rollback automático** (`kubectl rollout undo`) e o job fica **vermelho** — em `main`, o gate do `prod` nem é alcançado. Os 3 deploys chamam o mesmo reusable workflow (`namespace` + `environment` por input).

Fluxo end-to-end:
```
worktree/branch → push → PR p/ develop → merge → (push develop) → dev
develop → PR p/ main → merge → (push main) → hmg → [aprovar gate] → prod
```

### Deploy manual (prod)

`deploy-hmg` dispara automático em todo merge de PR em `main`. `deploy-prod` roda na
mesma run (`needs: deploy-hmg`, mesmo `SHA_TAG` já validado em hmg) mas **pausa**
aguardando aprovação — gate via GitHub Environment `prod` (Settings → Environments →
`prod` → Required reviewers, restrito a branches protegidas). Sem aprovação, o job
fica `Waiting` indefinidamente.

**Aprovar pela UI:** aba *Actions* do repo → run em andamento → clique em
*Review deployments* → marca `prod` → *Approve and deploy*.

**Aprovar via `gh` (CLI):**
```bash
# 1. Descobre o run_id da run pendente (branch main, evento push, status aguardando)
gh run list --branch main --limit 5

# 2. Descobre o environment_id do "prod" (id fixo do repo, não muda por run)
gh api repos/sergiohcosta/fintech-core/environments/prod -q .id

# 3. Aprova (state: approved) ou rejeita (state: rejected)
gh api --method POST repos/sergiohcosta/fintech-core/actions/runs/<run_id>/pending_deployments \
  -F 'environment_ids[]=<environment_id>' \
  -F 'state=approved' \
  -F 'comment=deploy manual aprovado'
```

**Rejeitar:** mesmo comando com `state=rejected` — a run marca `deploy-prod` como
`failure` e encerra sem tocar o cluster.

### Cortar uma release (versão SemVer)

Runbook completo: skill `fintech-core-release-and-versioning`.

### Health Check
```bash
curl http://localhost:8080/actuator/health
```
