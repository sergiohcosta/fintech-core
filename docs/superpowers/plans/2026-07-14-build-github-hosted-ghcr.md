# Plano de execução: Build em runner GitHub-hosted + GHCR

> Ledger de execução. Spec correspondente:
> `docs/superpowers/specs/2026-07-14-build-github-hosted-ghcr-design.md`.
> Marque `- [x]` conforme cada tarefa é concluída.

## Goal

Desbloquear o auto-deploy do fintech-core movendo o job `build-and-push` do runner
self-hosted (instável, #153) para um runner GitHub-hosted, pushando as imagens para o
`ghcr.io`. Os jobs `deploy-*` continuam self-hosted. Fim esperado: push em `develop` →
pipeline verde ponta a ponta → `dev` atualizado automaticamente.

## Architecture

Mudança em **dois repositórios**, que precisam pousar juntos (o nome da imagem casado pelo
kustomize tem de bater entre o manifest base e o job de deploy):

- **fintech-core** — `.github/workflows/ci-cd.yml` (build no hosted + GHCR; deploy troca só
  o nome da imagem).
- **homelab-k8s** — `projects/fintech-core/base/{backend,frontend}/deployment.yaml` (ref de
  imagem → GHCR).

Fluxo resultante:
```
GitHub-hosted (ubuntu-latest)          self-hosted (k3s)
  build-and-push ──push──► ghcr.io ◄──pull── kubelet
                                      deploy-* (kubectl, InClusterConfig)
```

## Tech Stack

- `docker/login-action@v3`, `docker/setup-buildx-action@v3`, `docker/build-push-action@v6`.
- Jib (mantido) para o backend.
- GHCR (`ghcr.io/sergiohcosta/fintech-core-*`), `GITHUB_TOKEN` com `packages: write`.
- kustomize (já usado pelos jobs de deploy).

## Global Constraints

- **Não** tocar nos jobs `deploy-dev/hmg/prod` além do nome da imagem — eles ficam
  self-hosted de propósito.
- **Não** apagar nada no registry do homelab (rollback depende dele).
- Seguir o workflow de branch/worktree do `git-operator.md`: branch a partir de `develop`,
  spec + plano commitados na `develop` **antes** da worktree.
- Commits PT-BR imperativos, sem co-autoria.
- A mudança do homelab-k8s vai em PR próprio nesse repo (base `main`).

## Tarefas

### T1 — Spec + plano na `develop` (portão SDD)
- [ ] Commit da spec e deste plano na `develop` (`docs(spec)` / `docs(plan)`) antes de criar a worktree.

### T2 — Workflow: job `build-and-push` (fintech-core)
- [ ] `runs-on: [self-hosted, k3s]` → `ubuntu-latest`.
- [ ] Adicionar `permissions: { contents: read, packages: write }` no job.
- [ ] Trocar o env: remover `REGISTRY_INTERNAL`; `SHA_TAG` mantém. (Manter `REGISTRY` só se ainda referenciado; senão remover.)
- [ ] Step de login GHCR (`docker/login-action`, `registry: ghcr.io`, `username: ${{ github.actor }}`, `password: ${{ secrets.GITHUB_TOKEN }}`).
- [ ] Backend: `-Dimage=ghcr.io/sergiohcosta/fintech-core-backend:$SHA_TAG`.
- [ ] Frontend: `setup-buildx-action` + `build-push-action` (context `.`, file `frontend/Dockerfile`, push, tag GHCR). Remover steps `Instala crane`, `Instala kaniko executor` e o `Build & push frontend` via kaniko (com `ulimit`/`--snapshot-mode`).

### T3 — Workflow: jobs `deploy-*` (fintech-core)
- [ ] Nos três jobs, trocar o `kustomize edit set image` para `ghcr.io/sergiohcosta/fintech-core-{backend,frontend}=...:$SHA_TAG`.
- [ ] Conferir que o env `REGISTRY` desses jobs (se ainda usado) reflete o novo nome ou foi removido.

### T4 — Manifests (homelab-k8s, PR próprio base `main`)
- [ ] `base/backend/deployment.yaml`: `image:` → `ghcr.io/sergiohcosta/fintech-core-backend:latest`.
- [ ] `base/frontend/deployment.yaml`: `image:` → `ghcr.io/sergiohcosta/fintech-core-frontend:latest`.
- [ ] Verificar overlays (`overlays/{dev,hmg,prod}`) — nenhum fixa a ref antiga.

### T5 — Bootstrap GHCR (uma vez)
- [ ] Rodar o pipeline uma vez para criar os packages no 1º push.
- [ ] Tornar `fintech-core-backend` e `fintech-core-frontend` **públicos** (UI do GitHub ou `gh api`), senão o pull do kubelet dá `ImagePullBackOff`.

### T6 — Validação ponta a ponta
- [ ] Push em `develop` → `build-and-push` verde em `ubuntu-latest` (sem #153); job reporta `success`.
- [ ] `deploy-dev` **dispara** (não mais `skipped`).
- [ ] `ghcr.io/...:sha-<sha>` existe (ambos) e público.
- [ ] `kubectl get pods -n dev -o …image` = SHA novo; pods `Ready`; backend saudável (`/actuator/health`).

### T7 — Docs, issue, memória
- [ ] Marcar a spec como `aprovado`.
- [ ] Atualizar `commands.md` se o fluxo de deploy manual/observação mudar de nome de registry.
- [ ] Comentar/fechar #153 conforme o resultado (o build deixou de depender da rede do homelab).
- [ ] Atualizar a memória `project_cicd_pipeline_design.md` (build agora hosted + GHCR; #153 resolvido pela via da arquitetura, não do keepalive).

## Ordem de merge (evita janela quebrada)

1. T4 (homelab-k8s) mergeado em `main` primeiro — manifests apontam pro GHCR (`:latest`
   placeholder, ainda sem imagem; deploy não roda até haver push).
2. T2+T3 (fintech-core) mergeado em `develop` → 1º push builda e pusha pro GHCR.
3. T5 — tornar packages públicos.
4. Re-rodar/observar o `deploy-dev` puxar e subir.

## Rollback

Reverter os commits de T2/T3 (fintech-core) e T4 (homelab-k8s). O registry do homelab e as
imagens antigas seguem intactos; um novo push volta a buildar no self-hosted como antes. A
mitigação de keepalive (homelab-k8s#1) fica.
