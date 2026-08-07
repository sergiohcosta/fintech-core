### Workflow de Branches e PRs
**Regras invioláveis:**
- Este repositório utiliza o **Git Worktree** para gerenciamento de ambiente local. O objetivo é garantir isolamento total entre tarefas, eliminação do uso de `git stash` e proteção das branches principais.
- Nenhuma feature ou hotfix é desenvolvida diretamente nas branches `develop` ou `main`.
- Toda e qualquer nova branch **DEVE** nascer a partir da `develop` atualizada (nunca de `main` ou de outra feature branch)
- **Cada agente trabalha na sua própria branch separada**, sempre derivada de `develop`. Concluído e aprovado o trabalho, solita o merge na `develop`. Agentes em paralelo nunca compartilham branch.
- Ao concluir uma feature com sucesso, indicar em que branch está e sugerir merge na `develop` local
- PRs devem ser o mais cumulativos possível: agrupar issues relacionadas da mesma sessão em uma única PR em vez de abrir uma por issue
- PRs sempre apontam para `main` e partem de `develop` (o fluxo é `feature → develop → PR → main`)
- Nunca fazer merge de `develop` → `main` diretamente; sempre via PR com revisão
- Deletar branches e worktrees locais após o merge em `develop` para manter o repositório limpo (`./scripts/clean-worktrees.sh` automatiza; sempre rodar a partir da raiz estável — nunca com cwd dentro da worktree que será removida)
- **Spec e plano SDD são commitados na `develop` ANTES de criar a worktree** (ou escritos já dentro dela). Worktree criada antes do commit do plano não enxerga o arquivo e trava a execução.
- Worktrees criadas pela ferramenta nativa do Claude Code vivem em `.claude/worktrees/` — local aceito, equivalente a `.worktrees/`. Ambos são ignorados pelo git.
- Em worktrees, comandos git sempre com path absoluto ou `git -C <raiz-da-worktree>` — o cwd do shell reseta entre comandos e paths relativos duplicados (`backend/backend/...`) são o erro mais repetido nas sessões.

**Fluxo padrão:**
# 1. Vá para a pasta base estável
cd ~/fintech-core

# 2. Garanta que sua base local tem as últimas alterações do servidor
git pull origin develop

# 3. Crie a nova worktree e branch baseada em develop
git worktree add -b nome-da-sua-branch ~/fintech-core/.worktrees/nome-da-sua-branch develop

# 4. Navegue para o novo ambiente isolado
cd ~/fintech-core/.worktrees/nome-da-sua-branch

# 5. Desenvolva o que foi pedido
Trabalhe normalmente dentro da pasta criada. Suas dependências e arquivos temporários não afetarão as outras branches.

# 6. Finalização e Limpeza
Após finalização e aprovação explícita do merge com a develop e push da mesma ao remote, sugira abertura de PR e limpe seu ambiente local para economizar espaço:

git worktree remove ~/fintech-core/.worktrees/nome-da-sua-branch

### Commits
- Mensagens em português, descritivas, no imperativo ("adiciona", "corrige", "implementa")
- Nunca incluir co-autoria (`Co-Authored-By`) nas mensagens de commit

### Versionamento e Releases

Racional completo: `docs/adr/ADR-005-versionamento-semver-releases.md`. Runbook com comandos: `commands.md` ("Cortar uma release"). Skill: `fintech-core-release-and-versioning`.

**Duas camadas — não confundir:**

| Camada | O que é | Quem tem |
|--------|---------|----------|
| **Versão por SHA** | identidade exata do artefato (`sha-<commit>`) | **todo ambiente, sempre, automático** — dev, hmg, prod |
| **Nome SemVer** | apelido humano curado (`v1.2.0`) | só o que você decide nomear (pontos de release) |

Nada roda anônimo: qualquer ambiente rastreia o commit exato pelo `sha-<commit>`. SemVer é a camada de curadoria por cima.

**SemVer 2.0 (`MAJOR.MINOR.PATCH`) — a fronteira de compatibilidade é o `api-spec/openapi.yaml`:**

| Sobe | Quando | Exemplo |
|------|--------|---------|
| **PATCH** | corrige bug, sem mudar contrato | fix de cálculo |
| **MINOR** | feature nova, retrocompatível no contrato | endpoint/campo novo opcional |
| **MAJOR** | quebra quem consome o contrato | remove/renomeia campo que o frontend usa |

**Pré-1.0 (`0.y.z`):** ainda instável — MINOR pra feature, PATCH pra fix, ignora MAJOR até declarar `1.0.0` (= promessa de estabilidade do contrato).

**Versionamento por ambiente:**

| Ambiente | Dispara | Versionamento |
|----------|---------|---------------|
| **dev** | push em `develop` | **só SHA** — contínuo, alta rotatividade; não recebe nome SemVer |
| **hmg** | push em `main` | SHA sempre; **carrega** `vX.Y.Z` quando o SHA que roda == commit taggado |
| **prod** | mesma run da hmg (gate) | idem hmg — mesmo `SHA_TAG` já validado |

**Marca d'água no frontend (ambiente + versão):** o app exibe `ambiente · versão` — discreta no canto inferior (global) e evidente na tela de login. Formato: `prod · v0.2.0 (a1b2c3d)` com SemVer, ou `dev · a1b2c3d` só com SHA. Mecânica (mesma imagem serve os 3 envs, então nada é build-time exceto o SHA):
- `frontend/public/env.js` → `window.__APP_ENV`, lido por `core/app-env.ts`. Reescrito no boot do container pelo entrypoint `frontend/docker-entrypoint.d/40-app-env.sh` (nativo do nginx:alpine).
- `APP_SHA` assado no build (`build-arg`, `ci-cd.yml`). `APP_ENVIRONMENT` (=namespace), `APP_VERSION` (lookup de tag apontando pro SHA) e `APP_COMMIT_TIME` (data/hora do commit, `committer.date` via API do GitHub) injetados no deploy via `kubectl set env` (`deploy-env.yml`) — o manifesto do frontend fica sem `env:` de propósito, senão o `apply` sobrescreveria.
- SemVer aparece só quando um deploy roda num SHA já taggado (bate com a regra hmg/prod acima). Em push normal ainda não há tag → mostra o SHA.
- `APP_COMMIT_TIME` só aparece no **tooltip** (hover) da marca d'água, não no texto visível — mantém o rodapé discreto. Formatado em `pt-BR`/`America/Sao_Paulo` (`formatVersionTooltip`, `core/app-env.ts`).

**Regra de corte:** release **sai da `main`** (o que está validado em hmg/prod). `git tag -a vX.Y.Z` no commit de `main` já buildado → `.github/workflows/release.yml` re-tagga a imagem no GHCR (sem rebuild) e cria o GitHub Release com notas automáticas. Versão é **label**, não gate: não muda o deploy (que segue por push).

> **Exceção histórica:** `v0.1.0` foi cortada da `develop` como primeira release de aprendizado, antes do `release.yml` estar na `main`. Da `v0.2.0` em diante, cortar sempre da `main`.

**Diferido (não construir sem dor real):** pre-releases `-rc.N` por ambiente (hmg sempre nomeado) — ver ADR-005 "Fora de escopo". Adotar só quando "que versão está em hmg?" incomodar, ou surgir um segundo dev.

### Templates
- `.github/ISSUE_TEMPLATE/` — templates de issue (bug, feature, chore) + config.yml
- `.github/pull_request_template.md` — template de PR com checklist das regras invioláveis

### Referências
- [Conventional Commits](https://www.conventionalcommits.org/) - Commit format