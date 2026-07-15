---
name: fintech-core-release-and-versioning
description: >
  Como o fintech-core é versionado e como cortar uma release. Cobre Semantic Versioning 2.0
  (MAJOR.MINOR.PATCH) com a fronteira de compatibilidade ancorada no api-spec/openapi.yaml,
  a regra de pré-1.0, as duas camadas de versionamento (SHA sempre vs nome SemVer curado),
  o versionamento por ambiente (dev só-SHA, hmg/prod carregam vX.Y.Z), e o runbook completo de
  cortar release: git tag anotada na main → .github/workflows/release.yml re-tagga a imagem no
  GHCR sem rebuild (docker buildx imagetools create) + cria o GitHub Release com notas
  automáticas (gh release create --generate-notes). Inclui a mecânica de por que sha-${github.sha}
  reconstrói o nome da imagem sem estado, e os gotchas reais (taggar só commit já buildado,
  esperar o build antes da tag, workflow fixado no commit da tag, corta-se da main). Use quando a
  tarefa for: versionar, versionamento, release, cortar release, criar tag, git tag, SemVer,
  Semantic Versioning, bump de versão, changelog, GitHub Release, nomear versão, pre-release, rc,
  release candidate, "que versão está em prod/hmg", ou re-tag de imagem no GHCR. NÃO cobre o fluxo
  de mudança em si (spec → worktree → merge develop → PR main — use fintech-core-change-control)
  nem operar/deployar o cluster (use fintech-core-run-and-operate). Verificado contra o repo em
  2026-07-15.
---

# Release e Versionamento — fintech-core

Nomear versões e cortar releases. O source (git) e o schema (Flyway) já são versionados; esta
skill cobre a peça que faltava: **versionamento de release** — dar nome humano a um build.

Racional da decisão: `docs/adr/ADR-005-versionamento-semver-releases.md`. Convenção sempre
carregada: `git-operator.md` ("Versionamento e Releases"). Runbook de comandos duplicado em
`commands.md` ("Cortar uma release"). Pipeline base: ADR-004 (imagens `sha-<sha>` no GHCR).

## Quando NÃO usar esta skill

- **Fluxo de uma mudança** (spec → plano → worktree → merge develop → PR main), convenção de
  commit, migrations → `fintech-core-change-control`. Release é o passo *depois* do merge em main.
- **Subir/deployar/sincronizar** o sistema (docker compose, deploy, sync-db) →
  `fintech-core-run-and-operate`.
- **Diagnosticar uma run de CI que falhou** por outro motivo → `fintech-core-debugging-playbook`.

---

## 1. Duas camadas — nunca confundir

| Camada | Identidade | Quem tem |
|--------|-----------|----------|
| **Por SHA** | `sha-<commit>` — exata, automática | **todo ambiente, sempre** (dev, hmg, prod) |
| **SemVer** | `vX.Y.Z` — nome humano curado | só os pontos de release que você nomeia |

Nada roda anônimo: qualquer ambiente rastreia o commit exato pelo `sha-<commit>`. SemVer é
curadoria por cima — você nomeia **marcos**, não todo commit. Versão responde "isso é estável e
vale falar dele?".

## 2. SemVer 2.0 — decidir o incremento

Formato `MAJOR.MINOR.PATCH`. **A fronteira de compatibilidade é o `api-spec/openapi.yaml`** — é
o contrato que o frontend (e qualquer consumidor) depende.

| Incremento | Sobe quando | Exemplo no projeto |
|-----------|-------------|--------------------|
| **PATCH** `x.y.Z` | corrige bug, **sem** mudar o contrato | fix de centavo de parcela, correção de query |
| **MINOR** `x.Y.0` | feature nova, **retrocompatível** no contrato | endpoint ou campo novo opcional; tela nova |
| **MAJOR** `X.0.0` | **quebra** quem consome o contrato | remove/renomeia campo que o frontend usa; muda tipo/semântica |

**Pré-1.0 (`0.y.z`) — regra atual do projeto:** o contrato ainda é instável. Use **MINOR pra
feature, PATCH pra fix**, e **ignore MAJOR** até declarar `1.0.0` (= promessa de que o
`openapi.yaml` não quebra mais sem aviso). Estado atual: `v0.1.0` cortada em 2026-07-15.

## 3. Versionamento por ambiente

| Ambiente | Dispara deploy | Versionamento |
|----------|----------------|---------------|
| **dev** | push em `develop` | **só SHA** — contínuo, alta rotatividade; não recebe nome |
| **hmg** | push em `main` | SHA sempre; **carrega** `vX.Y.Z` quando o SHA que roda == commit taggado |
| **prod** | mesma run da hmg (gate manual) | idem hmg — mesmo `SHA_TAG` já validado |

"hmg está na `v0.2.0`" é verdade quando o SHA que hmg roda é o commit taggado `v0.2.0`.
Empurrou 3 commits na main e taggou só o último? Os 2 do meio rodaram só com SHA. É esperado.

## 4. Runbook — cortar uma release

**Regra de corte: release sai da `main`** (o que está validado em hmg/prod). Versão é **label**
— não dispara nem gateia deploy.

**Pré-requisito inviolável:** o commit a taggar **já passou pelo pipeline** — push em `main` →
job `build-and-push` verde → imagem `sha-<sha>` existe no GHCR. Taggar commit não-buildado faz o
`release.yml` falhar no re-tag (`tag not found`).

```bash
# 1. Decida o incremento pela seção 2 (pré-1.0: MINOR feature, PATCH fix).

# 2. main local no commit já buildado
git checkout main && git pull origin main

# 3. Confirme que o build-and-push desse commit está verde (imagem existe)
gh run list --branch main --limit 3
# opcional: confere a tag da imagem no GHCR
gh api /users/sergiohcosta/packages/container/fintech-core-backend/versions \
  -q '.[].metadata.container.tags[]' | grep "sha-$(git rev-parse HEAD | cut -c1-7)"

# 4. Tag anotada + push (dispara o release.yml)
git tag -a v0.2.0 -m "v0.2.0 - <resumo do que mudou>"
git push origin v0.2.0

# 5. Acompanhe a run de Release
gh run list --workflow release.yml --limit 1
gh run view <run_id> --log-failed   # se falhar
```

Verificar depois: imagens `vX.Y.Z` no GHCR + o Release na aba Releases.

```bash
gh release list --limit 5
gh api /users/sergiohcosta/packages/container/fintech-core-backend/versions \
  -q '.[].metadata.container.tags[]' | grep '^v0'
```

## 5. Mecânica do `release.yml` (por que funciona sem estado)

Dispara em `push: tags: ['v*']`. Três passos:

1. **Login no GHCR** (`docker/login-action`, `GITHUB_TOKEN` — zero segredo novo).
2. **Re-tag registry-side** — `docker buildx imagetools create --tag …:vX.Y.Z …:sha-<sha>`
   aponta a tag de versão pro **mesmo digest** da imagem SHA. Nenhuma layer é rebuildada ou
   puxada; o GHCR só grava "esse digest também responde por `vX.Y.Z`". Milissegundos.
3. **GitHub Release** — `gh release create "$VERSION" --generate-notes --title "$VERSION"
   --repo "$GITHUB_REPOSITORY"`. As notas saem do diff desde a tag anterior.

**Por que `sha-${{ github.sha }}` reconstrói o nome certo sem guardar nada:** num evento de tag,
`github.sha` = o commit que a tag aponta. A imagem foi buildada como `sha-${{ github.sha }}` no
push desse mesmo commit (job `build-and-push`). Os dois workflows derivam o nome do **mesmo
commit** → string idêntica. O commit é a chave comum, e ela já mora no git — stateless.

## 6. Gotchas (cada um já mordeu)

- **Taggar commit não-buildado** → re-tag falha (`tag not found`). Só tagge commit verde no
  `build-and-push`.
- **Corrida push-em-massa** → empurrou vários commits na main de uma vez; espere o
  `build-and-push` do commit-alvo ficar verde **antes** do push da tag.
- **Workflow fixado no commit da tag** → a run de uma tag roda a versão do `release.yml`
  **daquele commit**. Bug numa tag já criada não se conserta re-rodando; corrige-se fix-forward
  na próxima tag. (Foi o caso da `v0.1.0`: re-tag passou, `gh release` falhou por falta de
  `--repo`; o Release foi criado à mão e o fix seguiu pra `main`.)
- **`gh release` sem `--repo`** → `fatal: not a git repository` (o job não faz `checkout`, então
  o `gh` não acha o repo pelo git local). Já corrigido no `release.yml` com `--repo`.
- **`v0.1.0` saiu da `develop`** (exceção de aprendizado, antes do `release.yml` estar na main).
  Da `v0.2.0` em diante, cortar da `main`.

## 7. Diferido (não construir sem dor real)

- **Pre-releases `-rc.N` por ambiente** (hmg sempre nomeado): variante barata (label,
  `--prerelease`, ~3 linhas) ou cara (RC dirige deploy, revisa ADR-004). Adotar só quando "que
  versão está em hmg?" incomodar, ou surgir um segundo dev. Garantia central (prod roda o
  artefato exato de hmg) já existe de graça: mesma imagem, mesmo SHA, gate no meio.
- **`release-please` / bump automático:** exige Conventional Commits — conflita com a convenção
  PT-BR imperativa. Reavaliar só com múltiplos contribuidores.
- **Bump de `pom.xml`/`package.json`:** papelada pra container deploy; adotar só se publicar
  biblioteca.

Detalhe e gatilhos de adoção: ADR-005 "Fora de escopo".

---

## Proveniência e manutenção

Fatos verificados em **2026-07-15**. Re-verificação em uma linha:

- Workflow de release: `cat /home/sergio/fintech-core/.github/workflows/release.yml`
- Convenção sempre carregada: `sed -n '/Versionamento e Releases/,/Templates/p' /home/sergio/fintech-core/git-operator.md`
- Racional: `cat /home/sergio/fintech-core/docs/adr/ADR-005-versionamento-semver-releases.md`
- Releases existentes: `gh release list --limit 10`
- Tags no repo: `git -C /home/sergio/fintech-core tag -l 'v*' | sort -V`
- Convenção de imagem (build): `grep SHA_TAG /home/sergio/fintech-core/.github/workflows/ci-cd.yml`
