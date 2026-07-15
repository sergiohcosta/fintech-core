# Spec: Build em runner GitHub-hosted + push pro GHCR

**Data:** 2026-07-14
**Status:** proposto

## Contexto

O job `build-and-push` (`.github/workflows/ci-cd.yml`) roda num runner **self-hosted**
dentro do k3s do homelab. Ele builda as imagens (Jib no backend, kaniko no frontend) e
pusha pro registry do homelab. Os jobs `deploy-dev/hmg/prod` dependem dele.

O runner self-hosted **perde a conexão de controle com o GitHub** em steps longos
(`SSL connection could not be established`) — issue #153. A correção de keepalive
(homelab-k8s#1: `net.ipv4.tcp_keepalive_time` 7200→20s no netns do pod) parou o **abandono
no meio do build** — o build agora roda inteiro e as duas imagens pushham de fato —, mas um
**resíduo** persiste: o runner ainda perde comms na fase Post/idle (~44s depois do corpo do
job), e a annotation do GitHub marca o job `Failed` ("The self-hosted runner lost
communication with the server"). Com o job `Failed`, o `deploy-*` é sempre `skipped`.

**A amarra estrutural:** o build está no self-hosted porque o runner precisa estar **dentro
do cluster** para alcançar o registry interno (`registry.infra.svc.cluster.local:5000`,
HTTP cluster-local). É essa amarra que prende o trabalho pesado/longo à rede instável do
homelab.

O contorno definitivo é **quebrar a amarra**: mover o build para um runner GitHub-hosted
(rede estável para o GitHub → #153 desaparece para o build) e resolver o "onde pushar",
já que um runner hosted está na internet pública, fora do cluster e fora da tailnet.

## Decisões

- **`build-and-push` → `runs-on: ubuntu-latest`.** GitHub-hosted fala com o GitHub pela
  rede do próprio GitHub — o modo de falha do #153 não existe nesse ambiente.
  *Abordagem descartada — manter self-hosted e afinar keepalive/rede:* o keepalive é
  mitigação, não cura (o runner segue perdendo comms fora da janela de streaming); insistir
  seria perseguir um timeout de NAT/firewall incerto do homelab.

- **Registry de destino: `ghcr.io`.** Push do runner hosted via `docker/login-action` com o
  `GITHUB_TOKEN` embutido — **zero segredo novo**. Como o repo `fintech-core` é **público**,
  os packages podem ser públicos → o kubelet do homelab puxa **sem `imagePullSecret`**
  (consistente com o estado atual: os deployments não têm pull secret).
  *Abordagem descartada — manter registry do homelab + join efêmero na tailnet
  (`tailscale/github-action`):* exige um `TS_AUTHKEY` novo (rotação, ACL/tag restrita) e uma
  peça móvel a mais. É a Variante B; só compensa se as imagens precisarem morar no hardware.
  *Abordagem descartada — expor o registry do homelab publicamente:* superfície de ataque na
  internet (auth forte, rate limit, patching); risco desproporcional para um homelab.

- **Frontend: `docker/build-push-action` (buildx) em vez de kaniko.** O runner hosted tem
  daemon Docker → não precisa de build 100% userspace. Remove os contornos `crane` +
  `kaniko-executor` + `ulimit -n 65536` + `--snapshot-mode=redo` do workflow.
  *Motivo do kaniko originalmente:* ausência de daemon Docker no pod self-hosted — some com o
  ambiente hosted.

- **Backend: Jib mantém.** Já funciona, é rápido e independe de daemon; só muda o destino do
  `-Dimage` para o GHCR. Menor diff que trocar por `docker build` do `backend/Dockerfile`.

- **`deploy-dev/hmg/prod` permanecem self-hosted, intocados.** Usam `InClusterConfig`
  (token da ServiceAccount) + RBAC por namespace, e são **curtos (~1-2min)** → muito abaixo
  da janela de idle que derruba builds longos, logo imunes ao #153. Movê-los para hosted
  exigiria expor o kube-API/kubeconfig na internet — inaceitável.

- **Refs de imagem migram** de `registry.atlas-haddock.ts.net/fintech-core-*` para
  `ghcr.io/sergiohcosta/fintech-core-*` nos manifests base do homelab-k8s **e** nos
  `kustomize edit set image` dos jobs de deploy (o nome casado pelo kustomize precisa bater).

## Componentes afetados

| Repo | Arquivo | Mudança |
|---|---|---|
| fintech-core | `.github/workflows/ci-cd.yml` | job `build-and-push`: `runs-on`, `permissions: packages: write`, login GHCR, Jib→GHCR, frontend buildx→GHCR, remove kaniko/crane/ulimit; jobs `deploy-*`: nome da imagem no `kustomize edit set image` |
| homelab-k8s | `projects/fintech-core/base/backend/deployment.yaml` | `image:` → `ghcr.io/sergiohcosta/fintech-core-backend:latest` |
| homelab-k8s | `projects/fintech-core/base/frontend/deployment.yaml` | `image:` → `ghcr.io/sergiohcosta/fintech-core-frontend:latest` |
| GHCR | packages `fintech-core-backend`, `fintech-core-frontend` | criados no 1º push; **tornar públicos** (bootstrap único) |

## Contrato técnico

**Nomes e tag.** `ghcr.io/sergiohcosta/fintech-core-{backend,frontend}`; tag mantém a
convenção `sha-${{ github.sha }}` (variável `SHA_TAG`). O `:latest` nos manifests base é só
placeholder — o `kustomize edit set image` do deploy sobrescreve para a tag SHA da run.

**Auth do push (GHCR).** No job `build-and-push`:
```yaml
permissions:
  contents: read
  packages: write
steps:
  - uses: docker/login-action@v3
    with:
      registry: ghcr.io
      username: ${{ github.actor }}
      password: ${{ secrets.GITHUB_TOKEN }}
```
O `login-action` escreve `~/.docker/config.json`; o Jib lê esse config e autentica no push
sem configuração extra. O buildx idem.

**Backend (Jib):**
```yaml
- name: Build & push backend (Jib)
  working-directory: ./backend
  run: ./mvnw -B compile jib:build -Dimage=ghcr.io/sergiohcosta/fintech-core-backend:$SHA_TAG
```

**Frontend (buildx):**
```yaml
- uses: docker/setup-buildx-action@v3
- uses: docker/build-push-action@v6
  with:
    context: .
    file: frontend/Dockerfile
    push: true
    tags: ghcr.io/sergiohcosta/fintech-core-frontend:${{ env.SHA_TAG }}
```

**Deploy (inalterado exceto o nome da imagem):**
```yaml
kustomize edit set image \
  ghcr.io/sergiohcosta/fintech-core-backend=ghcr.io/sergiohcosta/fintech-core-backend:$SHA_TAG \
  ghcr.io/sergiohcosta/fintech-core-frontend=ghcr.io/sergiohcosta/fintech-core-frontend:$SHA_TAG
```

**Visibilidade do package (gotcha crítico).** Packages do GHCR nascem **privados** mesmo em
repo público. Enquanto privados, o pull do kubelet falha (`ImagePullBackOff`) sem
`imagePullSecret`. Bootstrap único após o 1º push: tornar ambos os packages **públicos**
(UI do GitHub → Package → Settings → Change visibility → Public, ou `gh api`). Alternativa
se preferir mantê-los privados: criar um `imagePullSecret` (`dockerconfigjson` com um PAT
read-only `read:packages`) nos namespaces `dev/hmg/prod` e referenciá-lo nos deployments —
adiciona um segredo, então a opção pública é a recomendada.

## Teste

1. Push em `develop` → `build-and-push` roda em `ubuntu-latest` e termina **`success`**
   (sem #153); `deploy-dev` **dispara** (não mais `skipped`).
2. `ghcr.io/sergiohcosta/fintech-core-{backend,frontend}:sha-<sha>` existem e estão públicos.
3. `dev` roda a tag SHA nova: `kubectl get pods -n dev -o …image` bate com o SHA; pods
   `Ready`, backend saudável (readiness em `/actuator/health`).
4. **Rollback:** reverter `ci-cd.yml` + os dois manifests base; as imagens antigas no
   registry do homelab continuam pulláveis (nada foi apagado lá).

## Fora de escopo

- Desmontar/retirar o registry do homelab (segue servindo outros apps / decisão futura).
- Cache de build persistente (buildx registry cache / `actions/cache`) — otimização posterior.
- Mover os `deploy-*` para hosted (permanecem self-hosted **de propósito**).
- Fix da rede do homelab em si (deixa de ser necessário para o build).
- Variante B (tailscale join) — descartada acima, registrada como alternativa reversível.
- Reverter a mitigação de keepalive (homelab-k8s#1 fica — ainda protege o runner nos
  `deploy-*` e em qualquer job self-hosted longo futuro).

## Riscos

| Risco | Mitigação |
|---|---|
| Package GHCR privado por default → `ImagePullBackOff` no 1º deploy | Bootstrap: tornar público após o 1º push (ou `imagePullSecret`) |
| Dependência do GHCR para os pulls (disponibilidade) | Aceitável — o pipeline inteiro já depende do GitHub; imagens ficam cacheadas nos nós |
| Minutos de GitHub Actions | Repo **público** → Actions grátis; custo ≈ zero |
| Nome da imagem dessincronizado entre manifest base e `kustomize set image` | Ambos migram na mesma entrega; teste #1 pega |
