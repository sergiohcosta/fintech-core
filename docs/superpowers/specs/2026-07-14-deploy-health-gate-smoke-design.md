# Spec: Deploy com gate de saúde, rollback automático e smoke test

**Data:** 2026-07-14
**Status:** proposto

## Contexto

Os jobs `deploy-dev/hmg/prod` (`.github/workflows/ci-cd.yml`) hoje fazem **fire-and-forget**:

```
kustomize edit set image ...
kubectl apply -k .
kubectl rollout restart deployment/fintech-core-backend deployment/fintech-core-frontend -n <ns>
```

`rollout restart` **retorna na hora** — não espera o novo pod ficar `Ready`. Consequências:
1. Se o pod novo sobe quebrado (crash loop, migration falha, config errada), o job reporta
   **`success` mesmo assim**. Em `main`, isso deixa `deploy-hmg` verde e libera o gate do
   `prod` sobre uma versão que **não está saudável**.
2. Não há **rollback** — um deploy ruim fica no ar até intervenção manual.
3. Não há **verificação de que o app funciona** de fato após subir (só a readiness probe do
   kubelet, que o próprio deploy nem consulta).

Isto é o gap que separa "deploya automático" de "deploya com segurança" (avaliação de
maturidade do pipeline, 2026-07-14). Esta spec cobre os gaps **1 (gate de rollout +
rollback)** e **2 (smoke test)**.

## Decisões

- **Gate de rollout (gap 1):** após `apply` + `rollout restart`, esperar
  `kubectl rollout status` de **ambos** os deployments com timeout. Timeout estourado
  (pods não ficaram `Ready` dentro do orçamento) → job **falha**.
  *Timeout = 240s* — o backend leva ~76s de boot (startupProbe) + margem para pull/rolling.

- **Rollback automático (gap 1):** qualquer falha do gate (ou do smoke) dispara
  `kubectl rollout undo` nos dois deployments (reverte para a ReplicaSet anterior = imagem
  anterior) e **falha o job**. Assim o ambiente volta sozinho ao último estado saudável e o
  pipeline fica vermelho (em `main`, o gate do `prod` nem é alcançado).
  *Ressalva:* `undo` reverte só o template do Deployment (imagem). Mudanças de
  configmap/ingress aplicadas junto **não** são revertidas — aceitável, o caso comum é bump
  de imagem. No 1º deploy de um ambiente (sem revisão anterior) o `undo` é no-op inofensivo.

- **Smoke test (gap 2):** depois do rollout `Ready`, um pod efêmero de `curl` (dentro do
  namespace) exercita o app **através dos Services** (não só o localhost da probe):
  - `GET http://fintech-core-backend:8080/actuator/health` → **200 UP** (prova app + DB +
    Flyway, via roteamento de Service).
  - `GET http://fintech-core-frontend/` → **200** (nginx servindo o SPA buildado).
  - (opcional, aprofundamento) `GET .../openapi.yaml` → 200 (camada de controller/estáticos).
  `curl -fsS` sai não-zero em resposta não-2xx → falha o job → dispara rollback.
  *Por que não é redundante com a readiness probe:* a probe é kubelet→pod localhost; o smoke
  passa por DNS + Service + (frontend) nginx — pega falha de roteamento/serviço que a probe
  não vê.

- **DRY via reusable workflow (`workflow_call`):** a lógica deploy + gate + smoke + rollback
  é **idêntica** nos 3 ambientes. Em vez de triplicar ~35 linhas, extrair para
  `.github/workflows/deploy-env.yml` (chamado com `namespace` e `environment` como inputs).
  Os 3 jobs viram chamadores finos.
  *Abordagem descartada — inline nos 3 jobs:* ~105 linhas de quase-duplicata; qualquer ajuste
  de gate/rollback teria de ser replicado 3×. O reusable workflow também consolida o step de
  `Instala kustomize` (hoje duplicado) num lugar só.
  *Abordagem descartada — script em `homelab-k8s`:* os jobs de deploy dão checkout do
  homelab-k8s (não do fintech-core), então um script do fintech-core não estaria no
  workspace; e colocar a lógica do pipeline no repo de manifests separa trigger de lógica.

- **Aplica aos 3 ambientes; `prod` continua gated.** O gate manual do `prod` (Environment
  `prod`) roda **antes** do job; o gate de rollout + rollback roda **dentro**, após a
  aprovação. Deploy de prod que falhe o rollout **reverte sozinho** e não deixa prod quebrado.

## Componentes afetados

| Arquivo | Mudança |
|---|---|
| `.github/workflows/deploy-env.yml` (novo) | reusable workflow: inputs `namespace`/`environment`; app-token → checkout homelab-k8s → setup-kubectl → kustomize idempotente → **deploy + rollout-gate + smoke + rollback** |
| `.github/workflows/ci-cd.yml` | `deploy-dev/hmg/prod` viram chamadores (`uses: ./.github/workflows/deploy-env.yml` + `with`/`secrets: inherit`); remove o corpo inline duplicado |

Nenhuma mudança em `homelab-k8s` (o smoke usa nomes de Service já existentes:
`fintech-core-backend:8080`, `fintech-core-frontend:80`).

## Contrato técnico (esboço do job de deploy)

```bash
kustomize edit set image \
  $REGISTRY/fintech-core-backend=$REGISTRY/fintech-core-backend:$SHA_TAG \
  $REGISTRY/fintech-core-frontend=$REGISTRY/fintech-core-frontend:$SHA_TAG
kubectl apply -k .
kubectl rollout restart deployment/fintech-core-backend deployment/fintech-core-frontend -n $NS

rollback() { kubectl rollout undo deployment/fintech-core-backend deployment/fintech-core-frontend -n $NS || true; }

# Gate 1 — rollout saudável
for d in fintech-core-backend fintech-core-frontend; do
  kubectl rollout status deployment/$d -n $NS --timeout=240s || { echo "::error::rollout $d falhou"; rollback; exit 1; }
done

# Gate 2 — smoke test através dos Services
kubectl run smoke-$RANDOM -n $NS --rm -i --restart=Never --image=curlimages/curl:8.10.1 --command -- \
  sh -c 'curl -fsS http://fintech-core-backend:8080/actuator/health && curl -fsS -o /dev/null http://fintech-core-frontend/' \
  || { echo "::error::smoke test falhou"; rollback; exit 1; }
```

`environment: ${{ inputs.environment }}` no job do reusable workflow — `prod` liga o gate;
dev/hmg passam vazio (sem gate).

## Teste

1. **Caminho feliz:** push em `develop` → `deploy-dev` roda o fluxo novo → rollout `Ready` +
   smoke 200 → job `success`; `dev` no SHA novo, saudável.
2. **Caminho de falha (negativo, em `dev`):** forçar um deploy quebrado (ex: apontar uma tag
   de imagem inexistente ou um health que falha) → `rollout status` estoura o timeout →
   **`rollout undo`** executa → `dev` volta ao SHA anterior (pods `Ready`) → job **`failure`**.
   Provar que o ambiente ficou no estado bom e o pipeline vermelho.
3. **Smoke isolado:** com rollout OK mas app servindo erro (simular 500 no health) → smoke
   `curl -fsS` sai não-zero → rollback + job `failure`.

## Fora de escopo

- **Progressive delivery** (canary, blue-green) — rollout continua rolling update simples.
- **GitOps com reconciliador** (ArgoCD/Flux) — deploy continua por push do CI (gap 4, futuro).
- **Scan de CVE/SBOM das imagens** (Trivy/Grype) — gap 5, futuro.
- **Release/tag semântico** — segue tag por SHA (gap 6).
- **`paths-ignore` para docs** — churn de deploy por commit só-de-doc (gap 7); melhoria
  ortogonal, pode entrar junto mas não é o foco.
- **Notificações de deploy** (Slack/etc.).

## Riscos

| Risco | Mitigação |
|---|---|
| Timeout curto → falso-negativo em boot lento (pull grande, nó sob carga) | 240s cobre boot ~76s + folga; ajustável se aparecer falso-fail |
| `undo` reverte só o Deployment, não configmap/ingress aplicados junto | Caso comum é bump de imagem (só template muda); documentado |
| Smoke test flaky (Service ainda propagando logo após rollout) | Roda **após** `rollout status Ready`; `curl` com retry curto se necessário |
| Reusable workflow + `environment` dinâmico mal configurado abre prod sem gate | Teste explícito: confirmar que `deploy-prod` ainda pausa no gate após a refatoração |
| 1º deploy de um ambiente novo: `undo` sem revisão anterior | `|| true` no rollback torna no-op inofensivo |
