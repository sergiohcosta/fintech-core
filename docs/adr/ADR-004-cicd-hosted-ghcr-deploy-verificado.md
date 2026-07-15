# ADR-004: CI/CD — build em runner GitHub-hosted, imagens no GHCR e deploy verificado

## Status

Aceito — 2026-07-15.

Detalhe de implementação (fotografia por mudança) nas specs:
`docs/superpowers/specs/2026-07-14-build-github-hosted-ghcr-design.md` e
`docs/superpowers/specs/2026-07-14-deploy-health-gate-smoke-design.md`.

## Contexto

O pipeline (`.github/workflows/ci-cd.yml`) faz `feature → develop → PR → main`, com deploy
para 3 ambientes num cluster k3s de homelab (`dev` ← develop, `hmg`/`prod` ← main, `prod`
atrás de gate manual). Dois problemas estruturais foram enfrentados:

1. **Build no runner self-hosted era instável (#153).** O job `build-and-push` rodava num
   runner self-hosted dentro do k3s (necessário porque o registry do homelab só era
   alcançável de dentro do cluster). Em steps longos (build do frontend), o runner perdia a
   conexão de controle com o GitHub (`SSL connection could not be established`) e o job era
   abandonado → deploy nunca disparava. Um `initContainer` baixando o keepalive TCP
   (homelab-k8s#1) mitigou o abandono mid-build, mas o runner ainda perdia comms na fase
   Post/idle — a rede do homelab era o elo fraco.

2. **Deploy era fire-and-forget.** `kubectl rollout restart` não esperava o pod ficar
   `Ready`, então o job reportava `success` mesmo com pod quebrado; em `main` isso liberava o
   gate do `prod` sobre versão não-saudável. Sem rollback e sem verificação de que o app
   respondia.

### Opções avaliadas — build

| Opção | Prós | Contras |
|---|---|---|
| Manter self-hosted + afinar keepalive/rede | Sem segredo novo; imagens no hardware | Persegue timeout de NAT incerto; keepalive é mitigação, não cura |
| **Hosted + GHCR** (escolhida) | Rede estável (fala com o GitHub pela rede do GitHub); push via `GITHUB_TOKEN`, zero segredo; repo público → pull sem `imagePullSecret`; daemon Docker (dispensa kaniko) | Imagens passam a morar no GitHub; consome minutos de Actions (grátis em repo público) |
| Hosted + registry do homelab via tailscale join | Imagens no hardware | Exige `TS_AUTHKEY` (rotação, ACL); peça móvel a mais |
| Expor registry do homelab publicamente | Imagens no hardware | Superfície de ataque na internet |

### Opções avaliadas — deploy

| Opção | Prós | Contras |
|---|---|---|
| **Deploy verificado + rollback** (escolhida) | Falha visível; ambiente volta sozinho; smoke prova o app | +RBAC de `replicasets`; sem progressive delivery |
| Manter fire-and-forget | Simples | Deploy ruim fica no ar e mente `success` |
| GitOps com reconciliador (ArgoCD/Flux) | Drift detection, fonte única declarativa | Peso operacional grande pro estágio atual (adiado — ver Consequências) |

## Decisão

1. **`build-and-push` roda em `ubuntu-latest` (GitHub-hosted)** e pusha as imagens pro
   **GHCR** (`ghcr.io/sergiohcosta/fintech-core-{backend,frontend}:sha-<sha>`), autenticado
   pelo `GITHUB_TOKEN` (`permissions: packages: write`). Backend via Jib
   (`-Djib.httpTimeout=180000`, o default de 20s estourava no push das layers); frontend via
   `docker/build-push-action` (buildx) — kaniko/crane/ulimit removidos.

2. **Os 3 deploys são um reusable workflow** (`.github/workflows/deploy-env.yml`,
   `workflow_call`) que faz `apply` + **gate de rollout** (`rollout status --timeout=240s`) +
   **smoke test** (curl do runner nos Services de backend/frontend) + **rollback automático**
   (`rollout undo`) se rollout ou smoke falham. `deploy-dev/hmg/prod` viram chamadores finos
   (`namespace` + `environment` por input). O gate manual do `prod` é preservado via
   `environment: ${{ inputs.environment }}`.

3. **Os `deploy-*` permanecem self-hosted no k3s** — precisam de `InClusterConfig` + RBAC
   escopado por namespace, e são curtos (~1–2min) → imunes ao modo de falha do #153. Só o
   trabalho pesado/longo (build) saiu pra rede estável.

## Consequências

**Positivas:**
- #153 resolvido pela arquitetura (não por mitigação): o build não depende mais da rede do
  homelab. `deploy-dev/hmg/prod` disparam de forma confiável.
- Deploy **seguro**: um deploy quebrado falha o job, reverte sozinho e (em `main`) não
  alcança o gate do `prod`. Validado em dev (feliz + negativo), hmg e prod ao vivo.
- Build mais simples (daemon Docker) e DRY (~105 linhas de deploy duplicado → 1 reusable
  workflow; install do kustomize idempotente num lugar só).
- Separação idiomática: trabalho longo em runner hosted; trabalho privilegiado/curto em
  self-hosted.

**Negativas:**
- Dependência do GHCR para os pulls (aceitável — o pipeline já depende do GitHub).
- O registry do homelab fica sem uso por este app (mantido pra rollback das imagens antigas).
- Broadening pequeno da RBAC do `gha-runner`: `apps/replicasets` get/list/watch (read-only)
  pro `rollout undo` (homelab-k8s#3). O smoke **não** exige RBAC de `pods` — usa o curl do
  runner, mantendo o menor privilégio.
- Push só-de-doc ainda dispara build+deploy (falta `paths-ignore`) — churn conhecido.

## Riscos

| Risco | Mitigação |
|---|---|
| Package GHCR nasce privado → `ImagePullBackOff` | Bootstrap: tornar público (repo é público) |
| Timeout de rollout curto → falso-negativo em boot lento | 240s cobre boot ~76s + folga; ajustável |
| `rollout undo` reverte só o Deployment, não configmap/ingress | Caso comum é bump de imagem; documentado |
| Reusable workflow com `environment` dinâmico abrindo prod sem gate | Confirmado ao vivo que o gate do `prod` ainda pausa (run 29384711639) |

## Fora de escopo (gaps de maturidade adiados)

Progressive delivery (canary/blue-green), GitOps com reconciliador (ArgoCD/Flux), scan de
CVE/SBOM das imagens (Trivy/Grype), release/tag semântico e `paths-ignore` para docs — cada
um vira issue própria de fronteira.
