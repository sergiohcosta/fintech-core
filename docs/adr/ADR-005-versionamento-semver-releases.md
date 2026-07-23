# ADR-005: Versionamento SemVer 2.0 e releases nomeadas

## Status

Aceito — 2026-07-15.

Convenção operacional (padrão sempre carregado): `git-operator.md` ("Versionamento e
Releases"). Runbook com comandos: `commands.md` ("Cortar uma release"). Skill:
`fintech-core-release-and-versioning`. Depende do pipeline do ADR-004 (imagens no GHCR
taggadas por `sha-<sha>`).

## Contexto

Com o CI/CD estruturado (ADR-004), todo artefato era identificado **só** por `sha-<commit>`.
Preciso, mas ilegível: não havia como dizer "prod roda `v1.3.0`", nem release notes fora do
`git log`, nem alvo de rollback com nome humano. Faltava a camada de **versionamento de
release** — o source (git) e o schema (Flyway `V1..V22`) já eram versionados; releases não.

Fatos do projeto que calibram a decisão:
- **Dev único, orientado a aprendizado**, sem usuários externos, sem histórico de incidentes.
- Contrato público = `api-spec/openapi.yaml` (spec-first) — é o que um consumidor (o próprio
  frontend Angular) depende, logo é a fronteira natural de compatibilidade do SemVer.
- Commits em **PT-BR imperativo**, ~683 deles — **não** Conventional Commits.
- Pipeline: `develop → dev`, `main → hmg → prod` (gate), imagem por `sha-<sha>`; deploys
  disparam por **push** no branch, não por tag.

### Duas camadas de versionamento (distinção que a decisão preserva)

| Camada | Identidade | Cobertura |
|--------|-----------|-----------|
| Por SHA | `sha-<commit>` — exata, automática | todo ambiente, sempre |
| SemVer | `vX.Y.Z` — nome humano curado | só pontos de release escolhidos |

Nada roda anônimo; SemVer é curadoria por cima do SHA, não substituto dele.

### Opções avaliadas — mecanismo de bump

1. **git tag manual (escolhida).** `git tag -a vX.Y.Z` na `main`; workflow reage à tag.
   Controle total do número, zero mudança na convenção de commit.
2. **`release-please` / `semantic-release`.** Computa o bump a partir do histórico —
   **exige** Conventional Commits (`feat:`/`fix:`/`BREAKING CHANGE:`). Reescreveria o ritual
   de 683 commits PT-BR pra frente. Máquina pesada pra ganho marginal em projeto solo.
3. **`gh release` manual, sem tag de imagem.** Mais lazy, mas a imagem no cluster continuaria
   só SHA — a versão viraria label solto, sem rastro no artefato.

### Opções avaliadas — semântica da tag

1. **Label (escolhida).** A tag nomeia um commit já buildado/deployado; não dispara nem gateia
   deploy. Menor blast-radius sobre o pipeline do ADR-004.
2. **Release-gated deploy.** prod só deploya no push da tag. Separa "integrado" de "liberado",
   mas arranca os deploys do fluxo push-em-main e reescreve o trigger recém-estabilizado.

## Decisão

**SemVer 2.0 manual, tag como label, re-tag de imagem sem rebuild.**

1. **Esquema:** `MAJOR.MINOR.PATCH`. Fronteira de compatibilidade = `openapi.yaml`. MAJOR =
   quebra do contrato; MINOR = feature retrocompatível; PATCH = fix. **Pré-1.0** (`0.y.z`):
   instável por definição — MINOR pra feature, PATCH pra fix, sem MAJOR até declarar `1.0.0`.
2. **Corte:** `git tag -a vX.Y.Z` num commit da **`main`** já validado em hmg/prod.
3. **Automação (`.github/workflows/release.yml`, dispara em `push: tags: ['v*']`):**
   - re-tag registry-side via `docker buildx imagetools create` — a tag de versão aponta pro
     **mesmo digest** da imagem `sha-<sha>`; nenhuma layer é rebuildada ou movida;
   - `gh release create --generate-notes` — changelog automático do intervalo desde a tag
     anterior; `--repo` explícito (o job não faz `checkout`, então o `gh` precisa do repo).
   - **Por que os nomes batem sem estado:** num evento de tag, `github.sha` = o commit que a
     tag aponta; a imagem foi buildada como `sha-${{ github.sha }}` no push do mesmo commit.
     Os dois workflows derivam o nome do mesmo commit → resultado idêntico, stateless.
4. **Por ambiente:** dev = só SHA (contínuo, não nomeado); hmg/prod = SHA sempre, **carregam**
   `vX.Y.Z` quando o SHA que rodam == commit taggado.

## Consequências

- "Que versão está em prod?" passa a ter resposta humana; rollback ganha alvo nomeado.
- Release é **ato deliberado** (nem todo push na `main` vira versão) — nomeia-se marco, não
  cada commit. Commits de `main` entre releases rodam em hmg/prod só com SHA; é esperado.
- Custo por release ≈ 2 comandos (`git tag` + `git push`); o resto é automático e sem rebuild.
- Convenção de commit PT-BR **preservada** — nenhuma disciplina nova imposta ao dia a dia.
- **Impacto SemVer declarado por PR** (campo no `pull_request_template.md`) + o agente sugere o
  incremento ao concluir a mudança; a release usa o **máximo acumulado** desde a última tag
  (fechar 1 issue ≠ 1 release). Eixo de **shipping** (tag → Release → imagem) separado do de
  **planning** (Milestone/Iteration/issues), que só se encontram no número da versão. Milestones
  são **opcionais** — o projeto usa Project+Iteration; adotar Milestones só pra agrupar issues por
  versão, se surgir a necessidade (Iteration ≠ Milestone).

## Riscos

- **Taggar commit não-buildado** → `release.yml` falha no re-tag ("tag not found"). Mitigação:
  regra "só taggar commit já verde no `build-and-push`" documentada no runbook.
- **Corrida push-em-massa** → tag empurrada antes do build do commit-alvo terminar. Mitigação:
  esperar o `build-and-push` verde antes do push da tag.
- **Workflow fixado no commit da tag** → bug no `release.yml` de uma tag já criada não se
  corrige re-rodando (roda o código daquele commit). Correção é fix-forward na próxima tag.
  (Foi exatamente o que ocorreu na `v0.1.0`: o re-tag passou, o `gh release` falhou por falta
  de `--repo`; o Release foi criado à mão e o fix seguiu pra próxima release.)

## Fora de escopo (gaps de maturidade adiados)

- **`release-please` / bump automático:** só com adoção de Conventional Commits — reavaliar se
  o projeto ganhar múltiplos contribuidores.
- **Pre-releases `-rc.N` por ambiente (hmg sempre nomeado):** duas variantes —
  (a) *label*: marcar como pre-release no GitHub, ~3 linhas no `release.yml`;
  (b) *RC dirige deploy*: hmg no `-rc`, prod no final — **revisa o trigger do ADR-004**.
  Adotar só quando "que versão está em hmg?" incomodar a ponto de não querer olhar SHA, ou
  quando surgir um segundo dev. Hoje a garantia central (prod roda o artefato exato validado
  em hmg) já existe de graça: é a mesma imagem, mesmo SHA, com gate no meio.
- **Bump de `pom.xml` / `package.json`:** papelada pra container deploy (não publicamos
  artefato Maven/npm). Adotar só se um dia publicar biblioteca.
- **Graduação para `1.0.0`:** declarar quando o contrato `openapi.yaml` for considerado
  estável o suficiente pra prometer retrocompatibilidade.
