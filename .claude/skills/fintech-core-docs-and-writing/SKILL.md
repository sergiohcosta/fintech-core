---
name: fintech-core-docs-and-writing
description: >-
  Manter os documentos de registro e o estilo da casa no fintech-core: mapa fonte-única
  (openapi.yaml, summary.md, domain.md, database-schema.md, specs SDD, planos, ADRs), quando
  atualizar cada documento, convenções de nome (spec YYYY-MM-DD-{feature}-design.md,
  ADR-00N-slug.md), estrutura interna e seções obrigatórias de spec e ADR, templates .github
  (issue/PR), coleções .http, estilo PT-BR de escrita e ritual de encerramento de sessão.
  Gatilhos: documentação, docs, atualizar summary, atualizar domain.md, formato/estrutura/nome
  de spec ou plano, novo ADR, template de issue, template de PR, estilo, PT-BR,
  seed-dataset.http, onde documentar, fonte de verdade, session wrap-up, encerrar sessão.
  NÃO cobre o processo SDD em si (quando escrever spec, aprovação, worktree, merge — use
  fintech-core-change-control): aqui é o COMO escrever e ONDE guardar, não o SE/QUANDO.
---

# fintech-core — Docs e Escrita

> Skill da biblioteca `.claude/skills/` (índice vinculante em `_index.md`).
> Casa dos fatos: **mapa de documentos de registro + estilo PT-BR da casa**.
> Verificado contra o repositório em **2026-07-04**.

## Quando NÃO usar

| Situação | Use em vez desta |
|---|---|
| Processo de mudança (ciclo SDD, worktree, branch, merge, convenção de commit) | `fintech-core-change-control` |
| Conteúdo técnico do domínio (saldo, fatura, RRULE, Modelo A) | `fintech-domain-reference` |
| Regra "dataset faz parte da entrega" (situação → ação) | `fintech-core-change-control` |
| Convenções de teste e o que conta como evidência | `fintech-core-validation-and-qa` |

Esta skill responde **onde cada fato mora, quando atualizar cada documento e como escrever**
— não decide *se* uma mudança pode acontecer.

---

## 1. Mapa fonte-única (do CLAUDE.md — vinculante)

O projeto segue Spec-Driven Development: **cada fato tem UMA fonte**. Nunca duplique um
fato em dois documentos; referencie a casa dele.

| Fato | Fonte de verdade |
|---|---|
| Contrato de API | `api-spec/openapi.yaml` (spec-first) |
| Estado atual de endpoints e regras | `summary.md` (raiz do repo) |
| Modelo de domínio + enums | `domain.md` |
| Schema / migrations (V1–V21 em 2026-07-04) | `database-schema.md` |
| Racional de design por feature | `docs/superpowers/specs/` |
| Planos de execução | `docs/superpowers/plans/` |
| Decisões arquiteturais | `docs/adr/` (ADR-001 a ADR-003 em 2026-07-04) |
| Histórico (o quê / quando mudou) | git |
| Roadmap e tarefas | GitHub Issues / Project |

**Regra dura:** o `CLAUDE.md` é apenas *princípios + invariantes + roteador*. Ele **não**
mantém changelog nem lista de features ("o que já existe" mora em `summary.md` + git).
Se você se pegar adicionando "implementamos X" ao CLAUDE.md, pare — o lugar é `summary.md`.

Documentos satélites da raiz encadeados pelo CLAUDE.md: `tech.md` (stack), `architecture.md`
(camadas), `commands.md` (como rodar), `git-operator.md` (workflow git), `dataset.md`
(dataset de testes), `structure.md` (árvore de diretórios). Existem ainda
`docs/commercialization-plan.md` e `docs/innovation-roadmap.md` (planejamento de produto,
fora do ciclo SDD).

---

## 2. Runbook: tipo de mudança → documentos a tocar

Atualização de docs **faz parte da entrega**, não é etapa posterior. Ao concluir uma
mudança, percorra esta tabela de cima para baixo e toque tudo que se aplica:

| Mudança realizada | Documentos a atualizar |
|---|---|
| Endpoint novo ou alterado (rota, parâmetro, DTO) | `api-spec/openapi.yaml` (primeiro — spec-first) + `summary.md` (seção do domínio) + request em `docs/http/seed-dataset.http` |
| Regra de negócio nova/alterada (sem mudar contrato) | `summary.md` |
| Migration nova | linha na tabela de `database-schema.md` (+ constraints relevantes se houver) |
| Entidade, campo de domínio ou enum novo | diagrama/enums em `domain.md` |
| Feature nova (antes de codar) | spec em `docs/superpowers/specs/` + plano em `docs/superpowers/plans/` — commitados na `develop` **antes** da worktree (ver change-control) |
| Decisão arquitetural (stack, infra, padrão estrutural) | novo `docs/adr/ADR-00N-slug.md` |
| Restrição por role nova | `summary.md` (bloco "Segurança") |
| Mudança de stack/versão de ferramenta | `tech.md` |
| Comando/script novo de dev | `commands.md` |
| Diretório novo relevante | `structure.md` |
| Dados de seed alterados | ver regra completa em `fintech-core-change-control` (dataset.md é a fonte) |
| Refatoração sem mudança de comportamento | nenhum doc (o histórico é o git) |

Anti-padrão observado e proibido: registrar a mesma regra em `summary.md` **e** no
CLAUDE.md **e** na spec. A spec guarda o *racional da decisão* (fotografia, imutável após
aprovada); `summary.md` guarda o *estado atual* (vivo, sempre editável).

---

## 3. Convenções de nome e estrutura interna

### 3.1 Specs — `docs/superpowers/specs/YYYY-MM-DD-{feature}-design.md`

33 specs no diretório em 2026-07-04, de `2026-05-26-account-management-design.md` a
`2026-07-01-export-fatura-csv-design.md`. Nomes em kebab-case, PT-BR ou inglês conforme a
feature (ex: `motor-de-recorrencia-nucleo`, `transaction-timeline`). Duas exceções
históricas sem o sufixo `-design` existem (`2026-06-08-transaction-filters-ux-redesign.md`,
`2026-06-25-motor-de-recorrencia.md`) — **não** as imite; o padrão é `-design.md`.

Estrutura interna típica (verificada em `2026-07-01-export-fatura-csv-design.md`):

```markdown
# Spec: {Título da feature}

**Data:** YYYY-MM-DD
**Status:** aprovado

## Contexto           ← o que existe hoje e por que a feature entra agora
## Decisões           ← bullets; inclui "Abordagem descartada — X: motivo" para cada alternativa
## {seções técnicas}  ← colunas, componentes afetados, contratos (com trechos de código)
## Teste              ← casos que provam a spec
## Fora de escopo     ← lista explícita do que NÃO entra
```

O que faz uma spec boa aqui: **decisões com alternativas descartadas e o porquê** (contrato
de mentoria, ver §6), escopo negativo explícito e trechos de código concretos — não prosa
vaga.

### 3.2 Planos — `docs/superpowers/plans/YYYY-MM-DD-{feature}.md`

Mesmo prefixo de data e slug da spec correspondente, **sem** o sufixo `-design`
(ex: spec `2026-07-01-export-fatura-csv-design.md` ↔ plano `2026-07-01-export-fatura-csv.md`).
São grandes (10–90 KB): servem de ledger de execução para agentes. Estrutura verificada:
cabeçalho "For agentic workers" (sub-skill superpowers), **Goal**, **Architecture**,
**Tech Stack**, **Global Constraints**, e tarefas com checkboxes `- [ ]` que são marcadas
durante a execução.

### 3.3 ADRs — `docs/adr/ADR-00N-slug.md`

Numeração sequencial com zero à esquerda; slug em kebab-case
(`ADR-001-avaliacao-arquitetural.md`, `ADR-002-billing-stripe.md`, `ADR-003-infra-flyio.md`).
Estrutura verificada (ADR-002/003 seguem o formato clássico; ADR-001 é uma "fotografia
arquitetural" com cabeçalho estendido **Status / Data / Escopo / Migrations aplicadas**):

```markdown
# ADR-00N: {Título}

## Status          ← Aceito | Proposto | Substituído por ADR-00M
## Contexto        ← problema + opções avaliadas (com prós/contras de cada)
## Decisão         ← a escolha e a arquitetura resultante (diagramas ASCII são usados)
## Consequências   ← Positivas / Negativas
## Riscos          ← tabela Risco → Mitigação (presente em ADR-002)
```

ADR é para decisões **estruturais** (provedor de billing, PaaS, avaliação arquitetural) —
decisão local de uma feature vai na spec da feature, não em ADR.

---

## 4. `docs/http/` — coleções HTTP

- `docs/http/seed-dataset.http` — coleção IntelliJ HTTP Client / VS Code REST Client com o
  dataset Família Costa em 9 blocos ordenados (auth → contas → categorias → membros →
  transações → parcelamentos → transferências → faturas → verificações). Variáveis fluem
  entre blocos via `client.global.set`.
- `docs/http/README.md` — pré-requisitos e ordem de execução.

**Quando adicionar request:** todo endpoint novo ou parâmetro novo de endpoint ganha um
request na coleção (regra de `dataset.md`; exemplo real: commit `ad3b409` adicionou o
endpoint `reactivate` à coleção junto com a feature). Mantenha o request no bloco temático
correto e reaproveite as variáveis globais existentes.

**Atenção (2026-07-04):** o `docs/http/README.md` ainda cita `V10__seed_dev.sql` — o seed
foi renomeado para `V13` (ver `database-schema.md`). Ao tocar esse README, corrija a
referência.

---

## 5. Templates `.github/` (verificados em 2026-07-04)

### Issues — `.github/ISSUE_TEMPLATE/`

Três templates, todos em PT-BR, com `assignees: sergiohcosta` e prefixo de título
Conventional Commits:

| Arquivo | Título | Label | Seções |
|---|---|---|---|
| `bug_report.md` | `fix: ` | `bug` | Problema · Comportamento esperado · Como reproduzir · Causa raiz (se conhecida) · Área afetada · Arquivos suspeitos · Critério de conclusão |
| `feature_request.md` | `feat: ` | `enhancement` | O que precisa ser feito · Por que é necessário · Comportamento proposto · Escopo (Backend/Frontend/BD/Testes) · Arquivos/módulos · Critério de conclusão |
| `chore.md` | `chore: ` | `chore` | O que fazer · Por que agora · Escopo · **O que NÃO muda** · Critério de conclusão |

`config.yml`: `blank_issues_enabled: true` + contact link para Discussions do repo
("Dúvida técnica").

Ao escrever uma issue, preencha **Critério de conclusão** com itens verificáveis — é a
seção que os agentes usam como definição de pronto.

### PR — `.github/pull_request_template.md`

Seções: "O que faz", "Issues resolvidas" (`Closes #`), "Escopo de mudanças" e um
**checklist das regras invioláveis**: testes atualizados, migrations aditivas, isolamento
de tenant verificado, sem `any`, sem `console.log`, sem `Co-Authored-By`, dataset
atualizado. (O checklist ainda cita `V10__seed_dev.sql`; o seed atual é `V13` — corrija a
citação se editar o template.) Preencha "Observações para o revisor" com o trade-off não
óbvio da entrega — nunca deixe em branco em PR com decisão técnica.

---

## 6. Estilo da casa

Regras do CLAUDE.md, aplicadas a **todo texto** produzido no repo:

- **PT-BR** em: explicações, comentários pedagógicos no código, mensagens de commit,
  documentação, issues, PRs.
- **Inglês** em: nomes de variáveis, classes, métodos, identificadores (padrão da indústria).
  Nomes de arquivo de spec podem misturar (ex: `motor-de-recorrencia` convive com
  `transaction-timeline`).
- **Sem verbos abrasileirados:** "fazer merge" (não "mergear"), "fazer o push" (não "pushar").
- **Comentários só com valor pedagógico:** registram o *porquê não óbvio* de uma decisão.
  Nunca comente o óbvio ("incrementa contador").
- **Frontend:** Prettier com `printWidth: 100`, `singleQuote: true`, parser `angular` para
  HTML (verificado em `frontend/package.json`, chave `prettier`).
- **Commits:** Conventional Commits em PT-BR imperativo, sem co-autoria — a convenção
  completa é casa de `fintech-core-change-control`; aqui só o lembrete de que specs e
  planos ganham commits próprios `docs(spec): ...` / `docs(plan): ...` (padrão real do
  `git log`, ex: `4355f95`, `d693161`).

### Contrato de mentoria → como isso muda a escrita

O CLAUDE.md define o projeto como **jornada de aprendizado**: a IA atua como mentor, planeja
antes de codar e **justifica escolhas técnicas**. Consequência direta para documentação:

1. **Racional sempre registrado.** Toda spec lista as alternativas consideradas e por que
   foram descartadas (padrão real: seções "Abordagem descartada — X" nas specs). Um doc que
   só diz *o que* foi feito, sem *por quê*, está incompleto nesta casa.
2. **Plano explícito antes de código.** Feature com mais de um arquivo ou conceito novo
   exige spec + plano aprovados (e commitados) antes da execução — o documento não é
   burocracia posterior, é o portão de entrada.
3. **Decisões reversíveis documentadas como tal.** "Fora de escopo" e "sub-projetos
   futuros" são seções obrigatórias de fato nas specs — registram o que foi conscientemente
   adiado, para a decisão poder ser revisitada.

---

## 7. Ritual de encerramento de sessão

Existe a skill **`/session-wrap-up`** (listada nas skills do projeto): ao encerrar uma
sessão de desenvolvimento, ela garante que **memórias, docs, issues do GitHub e commits**
estejam atualizados antes de fechar. Use-a como gatilho do runbook da seção 2 — o wrap-up é
o último momento em que "atualizar o summary.md" ainda faz parte da entrega em vez de
virar dívida.

---

## Proveniência e manutenção

Fatos verificados em **2026-07-04** contra o repositório local. Re-verificação em uma linha:

```bash
ls docs/superpowers/specs/ | tail -5                      # specs mais recentes + padrão de nome
ls docs/superpowers/plans/ | tail -5                      # planos correspondentes (sem -design)
ls docs/adr/                                              # ADRs existentes (ADR-001..003)
ls .github/ISSUE_TEMPLATE/ && head -8 .github/pull_request_template.md   # templates
grep -n 'V10' docs/http/README.md .github/pull_request_template.md      # citações defasadas ainda presentes?
grep -A3 '"prettier"' frontend/package.json               # printWidth/singleQuote
git log --oneline -10                                     # padrão real de commits docs(spec)/docs(plan)
```

Se a tabela fonte-única do CLAUDE.md mudar, esta skill deve ser atualizada no mesmo ciclo
(regra da memória "Documentar regras nas specs").
