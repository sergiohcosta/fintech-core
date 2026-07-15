# Plano de execução: Deploy com gate de saúde, rollback e smoke test

> Ledger de execução. Spec: `docs/superpowers/specs/2026-07-14-deploy-health-gate-smoke-design.md`.
> Marque `- [x]` conforme concluir.

## Goal

Transformar `deploy-dev/hmg/prod` de fire-and-forget em deploy **verificado**: espera o
rollout ficar `Ready`, roda smoke test através dos Services, e **reverte sozinho** (rollback)
se qualquer um falhar — falhando o job (em `main`, sem alcançar o gate do `prod`). Fecha os
gaps 1 e 2 da avaliação de maturidade.

## Architecture

Mudança **só no fintech-core** (`.github/workflows/`). Extrair o corpo comum dos 3 deploys
num **reusable workflow** e adicionar as duas verificações + rollback:

```
ci-cd.yml
  deploy-dev  → uses: ./.github/workflows/deploy-env.yml  with: {namespace: dev}
  deploy-hmg  → uses: ...                                  with: {namespace: hmg}
  deploy-prod → uses: ...                                  with: {namespace: prod, environment: prod}

deploy-env.yml (workflow_call)
  job deploy (runs-on self-hosted; environment: ${{ inputs.environment }})
    app-token → checkout homelab-k8s → setup-kubectl → kustomize (idempotente)
    → deploy + rollout-gate(240s) + smoke(curl via Service) + rollback-on-failure
```

Smoke usa Services já existentes: `fintech-core-backend:8080/actuator/health` (200 UP) e
`fintech-core-frontend/` (200). Nenhuma mudança em homelab-k8s.

## Tech Stack

- GitHub Actions **reusable workflow** (`on: workflow_call`, inputs + `secrets: inherit`).
- `kubectl rollout status` (gate) / `kubectl rollout undo` (rollback).
- Pod efêmero `curlimages/curl:8.10.1` via `kubectl run --rm` para o smoke.

## Global Constraints

- **Não** mudar o comportamento externo dos gatilhos (develop→dev, main→hmg→prod-gate).
- **Preservar o gate manual do `prod`** — validar que ainda pausa após a refatoração.
- **Não** tocar em homelab-k8s.
- Runner de deploy continua self-hosted (precisa de acesso ao cluster).
- Timeout do rollout **≥ boot do backend (~76s)** com folga (240s).
- Commits PT-BR imperativos, sem co-autoria. Spec+plano commitados na `develop` antes da worktree.

## Tarefas

### T1 — Spec + plano na `develop`
- [ ] Commit da spec e deste plano na `develop` (`docs(spec)`/`docs(plan)`) antes da worktree.

### T2 — Reusable workflow `deploy-env.yml`
- [ ] Criar `.github/workflows/deploy-env.yml` com `on: workflow_call` (inputs `namespace` obrigatório, `environment` opcional; `secrets: inherit` p/ `GH_APP_ID`/`GH_APP_PRIVATE_KEY`).
- [ ] Job `deploy`: `runs-on: [self-hosted, k3s]`, `environment: ${{ inputs.environment }}`, envs `REGISTRY`/`SHA_TAG`.
- [ ] Steps: app-token → checkout homelab-k8s → setup-kubectl → instala kustomize (idempotente, `command -v`).
- [ ] Step de deploy: `kustomize edit set image` + `kubectl apply -k` + `rollout restart` (como hoje).
- [ ] **Gate 1:** `kubectl rollout status` dos 2 deployments com `--timeout=240s`; falha → `rollout undo` + `exit 1`.
- [ ] **Gate 2 (smoke):** pod efêmero `curl -fsS` em `fintech-core-backend:8080/actuator/health` e `fintech-core-frontend/`; falha → `rollout undo` + `exit 1`.
- [ ] Função `rollback` com `|| true` (no-op no 1º deploy sem revisão anterior).

### T3 — `ci-cd.yml`: deploys viram chamadores
- [ ] `deploy-dev`: `needs: build-and-push`, `if: ref==develop`, `uses: ./.github/workflows/deploy-env.yml`, `with: {namespace: dev}`, `secrets: inherit`.
- [ ] `deploy-hmg`: idem, `with: {namespace: hmg}`, `if: ref==main`.
- [ ] `deploy-prod`: `needs: deploy-hmg`, `with: {namespace: prod, environment: prod}`, `if: ref==main`.
- [ ] Remover o corpo inline duplicado dos 3 jobs (incluindo o `Instala kustomize` repetido).
- [ ] Validar YAML + `actionlint` se disponível.

### T4 — Validação
- [ ] **Feliz:** push em `develop` → `deploy-dev` roda o fluxo novo → rollout `Ready` + smoke 200 → `success`; `dev` no SHA novo.
- [ ] **Negativo (dev):** forçar deploy quebrado (tag inexistente ou health falhando) → `rollout status` estoura → **`rollout undo`** → `dev` volta ao SHA anterior → job `failure`. Restaurar `dev` em seguida.
- [ ] **Gate do prod intacto:** confirmar que `deploy-prod` ainda pausa no Environment `prod` após a refatoração (não deve deployar sem aprovação).

### T5 — Docs, memória
- [ ] Marcar a spec como `aprovado`.
- [ ] Atualizar `commands.md` (seção CI/CD): deploy agora é verificado (gate de rollout + smoke + rollback automático).
- [ ] Atualizar a memória `project_cicd_pipeline_design.md` (deploy verificado; reusable workflow; fecha gaps 1+2).

## Ordem de merge

1. T2+T3 (fintech-core) numa branch/worktree a partir da `develop`.
2. Merge em `develop` → push → `deploy-dev` exercita o fluxo novo (T4 feliz + negativo em dev).
3. PR `develop→main` → merge → `deploy-hmg` valida em hmg; confirmar gate do `prod` intacto.

## Rollback (do próprio plano)

Reverter os commits de T2/T3 restaura o deploy fire-and-forget anterior. Como a mudança é
só de workflow, não há efeito em imagens nem manifests. O reusable workflow novo é aditivo
(deletar `deploy-env.yml` + restaurar os corpos inline).
