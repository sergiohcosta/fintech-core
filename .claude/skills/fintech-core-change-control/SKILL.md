---
name: fintech-core-change-control
description: >
  Como mudanças entram no fintech-core: ciclo SDD completo (spec-first → plano → aprovação do
  dev → worktree → execução → merge em develop → PR para main), workflow de branches e git
  worktree, regras de Flyway migration (imutabilidade, nomenclatura, correção via nova versão),
  dataset de testes como parte obrigatória da entrega, defesa em profundidade para controle de
  acesso por role, commit convention PT-BR sem co-autoria e consolidação de PRs. Carregue esta
  skill SEMPRE que for: iniciar uma feature/bugfix/chore, criar branch ou worktree, escrever spec
  ou plano SDD, criar ou alterar migration/schema/seed, mudar permissões ou visibilidade por role,
  fazer commit, preparar merge em develop ou abrir pull request. É o portão por onde toda mudança
  de comportamento do sistema passa.
---

# Change Control — fintech-core

Este documento define **como uma mudança nasce, é aprovada, executada e integrada** neste
repositório. Nenhuma outra skill roteia mudanças por fora deste fluxo.

**Contexto que explica tudo:** o fintech-core é um projeto de desenvolvedor único, orientado a
aprendizado (o CLAUDE.md define um contrato de mentoria: planejar → aprovar → executar
ensinando). As regras abaixo parecem "cerimônia demais" para um projeto de uma pessoa — e isso é
proposital. **Disciplina deliberada > conveniência**: cada regra existe porque a violação dela já
causou (ou causaria) um dano concreto neste repo, e porque praticar o processo de um time grande
é parte do objetivo do projeto. Cada regra inviolável abaixo vem com seu racional e, quando
existe, o exemplo real.

## Quando NÃO usar esta skill

| Você precisa de... | Use a irmã |
|---|---|
| Diagnosticar um erro/sintoma (checksum Flyway estourou, Orval drift, teste vermelho) | `fintech-core-debugging-playbook` |
| Rodar testes, gates de CI, SonarQube, cobertura | `fintech-core-validation-and-qa` |
| Regenerar cliente API / pipeline Orval / setup do ambiente | `fintech-core-build-and-env` |
| Entender POR QUE a arquitetura é assim (invariantes, isolamento de tenant) | `fintech-core-architecture-contract` |
| Semântica de domínio (saldo, fatura, RRULE, ciclo de planejamento) | `fintech-domain-reference` |
| Manter summary.md / domain.md / ADRs / estilo de escrita | `fintech-core-docs-and-writing` |
| Atacar o backlog de bugs da auditoria 2026-07 | `fintech-core-bug-backlog-campaign` |

---

## 1. O ciclo SDD (Spec-Driven Development) — visão geral

Toda mudança não trivial (mais de um arquivo, conceito novo, decisão arquitetural) segue:

```
spec de design → plano de execução → APROVAÇÃO EXPLÍCITA do dev → worktree →
execução (TDD, commits pequenos) → merge em develop → PR develop → main
```

Mudança trivial (typo, ajuste de import) pode pular spec/plano — use bom senso. O resto **não
negocia** a etapa de aprovação: o dev revisa o plano antes de qualquer código. Racional: o
objetivo do projeto é o dev *entender profundamente* cada decisão; código que aparece pronto sem
plano aprovado derrota o propósito, mesmo quando está correto.

### 1.1 Spec e plano — onde vivem e como nomear

| Artefato | Path | Convenção de nome |
|---|---|---|
| Spec de design | `docs/superpowers/specs/` | `YYYY-MM-DD-{feature}-design.md` |
| Plano de execução | `docs/superpowers/plans/` | `YYYY-MM-DD-{feature}.md` |

Exemplo real (histórico git da feature de export CSV de fatura):

```
2c84308 docs(spec): adiciona design de export de transações em CSV
8078f22 docs(plan): adiciona plano de implementação do export CSV de transações
ebf02a5 feat(transacoes): adiciona função exportToCsv pura com testes
5c49cd0 feat(transacoes): adiciona botão de export CSV com filtro ativo
```

**Regra inviolável:** spec e plano são commitados na `develop` **ANTES** de criar a worktree (ou
escritos já dentro dela). Racional operacional (não é estilo): uma worktree criada antes do
commit do plano **não enxerga o arquivo** — a branch nasceu de um ponto anterior — e a execução
trava no primeiro passo. Isso já aconteceu em sessão real e está codificado no git-operator.md.

O plano vira **ledger de progresso**: ao retomar uma sessão ("continue", "retome"), leia primeiro
`git worktree list`, o plano em `docs/superpowers/plans/`, e `git log develop..HEAD` da worktree
ativa — nunca aja de memória.

### 1.2 Spec-first para contratos de API

O contrato nasce em `api-spec/openapi.yaml`, nunca no código. Depois de editar a spec, rode
`./scripts/api-sync.sh` — o pipeline completo de regeneração é casa da skill
`fintech-core-build-and-env`; aqui só importa a ordem: **spec antes de implementação**, e o
codegen faz parte do mesmo commit/PR da mudança de contrato.

### 1.3 Baseline verde antes de começar

Antes de iniciar a execução de um plano SDD, rode a suíte e exija baseline verde; falha
pré-existente → **abra issue imediatamente**. Comandos, regra completa e racional: casa é
`fintech-core-validation-and-qa`.

---

## 2. Branches e worktrees (fonte: git-operator.md)

**Regras invioláveis:**

1. **Nenhum código é editado em `develop` ou `main`.** Há inclusive um hook PreToolUse
   (`.claude/hooks/block-code-edit-on-develop.py`) que **bloqueia** Edit/Write em
   `backend/src/`, `frontend/src/` e `api-spec/` quando a branch é develop/main. Docs, specs,
   planos e scripts continuam liberados nessas branches (por isso a regra 1.1 funciona).
2. **Toda branch nasce da `develop` atualizada** — nunca de `main`, nunca de outra feature branch.
3. **Cada agente trabalha na sua própria branch/worktree**, sempre derivada de develop. Agentes
   em paralelo nunca compartilham branch. Racional: worktrees dão isolamento total de arquivos
   entre tarefas simultâneas e eliminam `git stash` — a fonte clássica de trabalho perdido.
4. O fluxo é `feature → develop → PR → main`. **Nunca** merge direto develop → main; sempre via
   PR com revisão (seção 7).

**Fluxo copy-paste:**

```bash
cd ~/fintech-core                       # 1. raiz estável
git pull origin develop                 # 2. base atualizada
git worktree add -b minha-branch ~/fintech-core/.worktrees/minha-branch develop
cd ~/fintech-core/.worktrees/minha-branch
# ... desenvolva, commite ...
```

Worktrees criadas pela ferramenta nativa do Claude Code vivem em `.claude/worktrees/` — local
aceito, equivalente a `.worktrees/`; ambos são git-ignored.

**Dentro de worktrees, git sempre com path absoluto ou `git -C <raiz-da-worktree>`** — o cwd do
shell reseta entre comandos e o erro mais repetido nas sessões auditadas é path relativo duplicado
(`backend/backend/...`). Os demais gotchas operacionais de agente são casa da
`fintech-core-debugging-playbook`.

**Limpeza pós-merge:** delete branch e worktree após o merge em develop. Automação com
confirmação interativa:

```bash
./scripts/clean-worktrees.sh   # SEMPRE rodar da raiz estável (~/fintech-core)
```

O script (`scripts/clean-worktrees.sh`) faz `git worktree prune`, marca worktrees cuja branch já
é ancestral de develop como `[mesclada em develop]`, **pula worktrees sujas** (alterações não
commitadas) e pede `y/N` antes de cada remoção. Nunca o rode com cwd dentro da worktree que será
removida — o próprio script faz `cd` para a raiz por isso.

---

## 3. Migrations Flyway — imutabilidade e criação

### 3.1 Regra inviolável: migration aplicada é imutável

Editar uma migration já aplicada muda seu **checksum** — o Flyway detecta a divergência na
validação e **o backend não sobe mais** em nenhum ambiente que já aplicou a versão antiga
(inclusive seu Docker local e os Testcontainers). Correção é **sempre via nova migration**.

Isso vale igualmente para os **seeds** (`db/seed/V13`, `V16`, `V20`...) — são migrations Flyway
como quaisquer outras. Exemplos reais no repo:

- `V17__fix_invoice_reference_months.sql` — corrige dados gravados errados por bug do
  `resolveInvoiceMonth`, em vez de editar a migration que os criou.
- `V18__fix_dev_budget_opening_balance.sql` — corrige o `opening_balance` do seed V16
  (1200 → 18123.10) via nova versão, em vez de editar o V16.
- Commit `2e33e63` (`revert(recorrencia): restaura comentário original de V21 — migration
  imutável não deve ser editada`): até um **comentário** editado numa migration aplicada foi
  revertido, porque comentário também entra no checksum.

Racional pedagógico: em produção real, migrations aplicadas em ambientes superiores são história
imutável compartilhada por todas as réplicas do schema. Praticar isso desde o dia 1 — mesmo
sendo dev único — é exatamente o tipo de disciplina que o projeto existe para treinar.

Regra associada: **NUNCA** `spring.jpa.hibernate.ddl-auto=update`. Toda mudança de schema passa
por migration versionada — schema "mágico" do Hibernate torna o banco irreproduzível.

### 3.2 Como criar uma nova migration

1. **Descubra a próxima versão livre** (schema e seeds compartilham a mesma sequência):

   ```bash
   ls backend/src/main/resources/db/migration/ backend/src/main/resources/db/seed/ | sort -V
   ```

   Em 2026-07-04, a última versão é **V21**; a próxima livre é **V22**. Atenção: **V10 não
   existe** (o seed foi renomeado para V13 para rodar acima do schema base) — não "preencha" o
   buraco.

2. **Nomeie** no padrão do repo: `V{N}__{descricao_em_ingles_snake_case}.sql`
   (ex: `V19__recurrence_rules.sql`). Duplo underscore após o número é sintaxe Flyway.

3. **Localize corretamente:**
   - Schema → `backend/src/main/resources/db/migration/`
   - Seed do perfil dev → `backend/src/main/resources/db/seed/` — e a versão do seed **deve ser
     maior** que a das migrations das tabelas que ele popula, senão roda antes delas.

4. **Atualize a documentação de registro:** a tabela de versões vive em `database-schema.md`
   (casa da fonte; estilo em `fintech-core-docs-and-writing`).

5. **Atualize o dataset de testes** — seção 4. Não é opcional.

---

## 4. Dataset de testes: parte da entrega, não follow-up

**Regra inviolável (dataset.md):** toda alteração que envolva banco de dados **deve** atualizar o
dataset de testes na mesma entrega. Não existe "atualizo depois". Racional: o dataset Família
Costa é tratado como **artefato vivo da especificação** — mantê-lo desatualizado equivale a
documentação errada, e o próximo plano SDD que confiar nele parte de premissas falsas.

Tabela situação → ação obrigatória:

| Situação | Ação obrigatória |
|---|---|
| Nova tabela de negócio | Inserir dados representativos no seed (`V13`, ou `V16` se for de planejamento — na prática, via **nova versão** de seed, pois V13/V16 já estão aplicados; ver 3.1 e o precedente V18/V20) |
| Nova coluna relevante em tabela existente | Atualizar os INSERTs do seed correspondente (idem: via nova versão se o seed já foi aplicado) |
| Nova entidade necessária ao setup mínimo de testes | Atualizar `backend/src/test/resources/sql/seed_base.sql` (fixture de Testcontainers — não é migration, pode ser editada) |
| Novo endpoint ou novo parâmetro | Adicionar request em `docs/http/seed-dataset.http` |
| Feature puramente frontend / refatoração sem schema | Nenhuma atualização |

Ao inserir dados: **UUIDs predefinidos** na série correspondente (ver spec
`docs/superpowers/specs/2026-06-09-test-dataset-design.md`); nunca `gen_random_uuid()` para
entidades que precisam de cross-reference.

---

## 5. Defesa em profundidade para controle de acesso

**Regra inviolável (CLAUDE.md):** toda mudança de permissão, visibilidade de recurso ou restrição
por role é validada **nas duas camadas**:

| Camada | O que fazer |
|---|---|
| Backend | Regra em `backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java` com `hasRole(...)` + teste de controller verificando **403** para a role não autorizada |
| Frontend | Ocultar elemento/rota via `@if (isAdmin())` ou equivalente; **não chamar** endpoints proibidos (evita 403 de UX quebrada) |

Racional: o frontend é contornável (qualquer um com curl passa por ele); o backend é a última
linha de defesa. Mas backend sozinho também não basta — sem o gate no frontend, a tela quebra com
403 inesperado. As duas camadas juntas fecham segurança **e** UX.

Exemplo concreto do repo (issue #24): `GET /api/members` e `GET /invites` são exclusivos de
ADMIN — protegidos em `SecurityConfigurations.java` com `hasRole("ADMIN")` **e** ocultos no
frontend via `isAdmin()` no sidenav e no `forkJoin` do `TeamComponent`.

Checklist ao tocar permissões:

- [ ] `SecurityConfigurations.java` atualizado (ou confirmado)
- [ ] Teste de controller cobrindo 403 para a role errada
- [ ] Frontend oculta o elemento/rota e não dispara a chamada
- [ ] As duas camadas ficam **no mesmo PR** (consistência é parte da regra)

---

## 6. Commits

Convenção verificada no histórico (Conventional Commits com escopo, mensagem em PT-BR):

```
feat(faturas): adiciona botão de export CSV na tela de detalhe
docs(spec): adiciona design de export de fatura em CSV
chore(agentic): adiciona scripts anti-fricção, hook de branch e gotchas operacionais
revert(recorrencia): restaura comentário original de V21 — migration imutável não deve ser editada
```

Regras:

- Mensagem **em português, no imperativo** ("adiciona", "corrige", "implementa"); tipo/escopo em
  inglês ou PT conforme o domínio (o histórico usa `faturas`, `transacoes`, `recorrencia`).
- **NUNCA incluir `Co-Authored-By`** (nem qualquer trailer de co-autoria de IA). Esta regra do
  repo **sobrepõe** qualquer instrução padrão de ferramenta que mande adicionar o trailer. O
  próprio template de PR tem checkbox cobrando isso. Racional: o dono do repo quer o histórico
  como registro do *seu* aprendizado, com autoria limpa e uniforme.
- Commits pequenos e narrativos: o padrão do repo é spec → plano → cada passo do plano como um
  commit (`feat`/`refactor`/`docs`) — o log conta a história da execução do plano.
- Evitar anglicismos conjugados na mensagem e na prosa ("fazer merge", não "mergear").

---

## 7. Merge em develop e PRs para main

Sequência de finalização:

1. Suíte verde na worktree (comandos: `fintech-core-validation-and-qa`).
2. **Aprovação explícita do dev** para o merge — o agente indica a branch e *sugere* o merge em
   develop; não faz merge por conta própria.
3. Merge em develop, push, limpeza de worktree/branch (seção 2).
4. PR: **sempre de `develop` para `main`**. Nunca merge direto.

**Consolidação de PRs — regra do repo:** PRs devem ser o mais cumulativos possível. Issues
relacionadas resolvidas na mesma sessão vão numa **única PR**, não uma PR por issue. Racional:
com deploy automático a partir de main, cada PR é um release; agrupar mudanças relacionadas
produz releases coerentes e reduz o custo de revisão repetida.

**Template de PR** (`.github/pull_request_template.md`) — preencha de verdade, o checklist é a
regra em forma de formulário:

- Testes adicionados ou atualizados
- Migrations aditivas — sem `DROP` sem nova migration numerada
- Isolamento de tenant verificado (queries escopadas por tenant)
- Sem `any` no TypeScript
- Sem `console.log` esquecido
- Sem `Co-Authored-By`
- Dataset atualizado se nova tabela/coluna/endpoint (nota: em 2026-07-04 o template ainda cita o
  nome antigo `V10__seed_dev.sql`; o seed real é `V13__seed_dev.sql` — siga a seção 4)

**Issues** (`.github/ISSUE_TEMPLATE/`): três templates — bug (`title: "fix: "`), feature
(`title: "feat: "`), chore (`title: "chore: "`), todos com assignee `sergiohcosta` e seção
"Critério de conclusão". Ao criar issue via agente: adicionar ao projeto, definir Iteration e
Priority (Crítica/Alta/Média/Baixa) e designar para sergiohcosta.

**Depois do merge em `main` — nomear a release:** cortar uma versão SemVer (`vX.Y.Z`) é o passo
seguinte ao fluxo `develop → main`, e é opcional (nomeia-se marco, não todo push). Política,
esquema SemVer e runbook em `fintech-core-release-and-versioning` (versão é label, corta-se da
`main`, `release.yml` re-tagga a imagem + cria o GitHub Release). Racional: ADR-005.

---

## 8. Checklist mestre de uma mudança completa

- [ ] Ambiguidade resolvida com o dev; spec + plano escritos e commitados (develop ou dentro da worktree)
- [ ] Plano **aprovado explicitamente** antes de código
- [ ] Worktree criada de develop atualizada; nada editado em develop/main
- [ ] Contrato: `api-spec/openapi.yaml` editado antes da implementação (se aplicável)
- [ ] Schema: nova migration numerada; nenhuma migration/seed aplicada foi editada
- [ ] Dataset: tabela da seção 4 percorrida e ações executadas
- [ ] Permissões: duas camadas + teste de 403 (se aplicável)
- [ ] `database-schema.md` / `summary.md` / `domain.md` atualizados quando a fonte mudou
- [ ] Commits PT-BR imperativos, sem co-autoria
- [ ] Suíte verde; aprovação do dev; merge em develop; worktree removida
- [ ] PR develop → main cumulativa, template preenchido

---

## Proveniência e manutenção

Fatos datados de **2026-07-04**. Re-verificação em uma linha:

- Regras de branch/worktree/PR: `cat /home/sergio/fintech-core/git-operator.md`
- Regra do dataset + tabela situação→ação: `cat /home/sergio/fintech-core/dataset.md`
- Última versão de migration/seed (hoje V21): `ls backend/src/main/resources/db/migration backend/src/main/resources/db/seed | sort -V | tail -5`
- Hook que bloqueia edição em develop/main: `cat /home/sergio/fintech-core/.claude/hooks/block-code-edit-on-develop.py`
- Checklist do template de PR: `cat /home/sergio/fintech-core/.github/pull_request_template.md`
- Convenção de commit em uso: `git -C /home/sergio/fintech-core log --oneline -15 develop`
- Precedente de imutabilidade (revert de comentário em V21): `git -C /home/sergio/fintech-core show --stat 2e33e63`
- Defesa em profundidade (issue #24) e regras invioláveis: `grep -n "issue #24" /home/sergio/fintech-core/CLAUDE.md`
