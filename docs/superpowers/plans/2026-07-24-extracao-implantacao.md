# Extração Multi-Mídia — Fundação e MVP de Imagem: Plano de Implantação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recomendado) ou superpowers:executing-plans para orquestrar as ondas deste plano.
> Workstreams B e C usam checkbox (`- [ ]`) para tracking; Workstream A é operação GitHub
> (sem código, sem worktree).

**Goal:** Tirar do papel a Fase 0 (Fundação) e a Fase 1 (MVP de extração por imagem) de
`docs/roadmap-extracao-e-conciliacao.md` — contrato de dados de staging + pipeline
`imagem → transação` provado ponta a ponta com um extrator de visão agnóstico de provider
(Spring AI 2.0 `ChatClient`, default Ollama grátis no homelab) — mais a estrutura de
tracking (milestones/issues) para as fases 2–6 e o trilho Open Finance.

**Architecture:** ver spec de referência abaixo. Resumo: staging separado
(`import_batches` + `staged_transactions`, `tenant_id` denormalizado) promovido para
`transactions` só no commit; confidence-por-campo em JSONB
(`@JdbcTypeCode(SqlTypes.JSON)`); `posting_date` em `transactions` desde já; extração via
porta `TransactionExtractor` com implementação `VisionExtractor` (Spring AI `ChatClient` +
Ollama), sempre revalidada do nosso lado antes de virar `Transaction`.

**Tech Stack:** Java 21 · Spring Boot 4.0.1 · Spring Data JPA · Flyway · Spring AI 2.0
(`spring-ai-bom` + `spring-ai-starter-model-ollama`) · Angular 21 Zoneless · Orval ·
Testcontainers · Vitest.

**Spec de referência:** `docs/superpowers/specs/2026-07-24-extracao-fundacao-e-mvp-imagem-design.md`
**Roadmap estratégico (fonte do produto):** `docs/roadmap-extracao-e-conciliacao.md`

## Global Constraints

- **Multi-tenant:** toda query de negócio filtra pelo `Tenant` do usuário autenticado.
  Vazamento de tenant é o bug mais grave do projeto. `staged_transactions.tenant_id` é
  denormalizado — defesa nº1, ver spec §3.
- **Schema só via Flyway:** migrations imutáveis; nunca `ddl-auto=update`. Próximas
  versões livres: **V23** (schema Fase 0), **V24** (seed dev Fase 0).
- **Entidade JPA nunca exposta** em controller — sempre DTO. DTOs com Bean Validation.
- **Spec-first:** editar `api-spec/openapi.yaml` primeiro → `./scripts/api-sync.sh` (spec →
  static → `generate-sources` → orval → remove `auth.service.ts` regenerado). Não executar
  os passos manualmente.
- **Exceções:** services nunca lançam `jakarta.persistence.EntityNotFoundException`; usar
  `com.fintech.api.exception.EntityNotFoundException` (404) para o `GlobalExceptionHandler`
  mapear corretamente.
- **Auth:** `.anyRequest().authenticated()` já cobre `/api/imports` — **nenhuma** alteração
  em `SecurityConfigurations.java`.
- **Dataset vivo:** mudança de banco obriga atualizar seed (`V24`, série `dev`) e
  `docs/http/seed-dataset.http` — regra inviolável de `dataset.md`.
- **PT-BR** em comentários/commits; identificadores em inglês. Commits no imperativo,
  **sem** `Co-Authored-By`.
- **Frontend Zoneless, signals-first, TS estrito (sem `any`), SCSS + Material 3.**
- **Baseline verde antes de iniciar:** `./scripts/test-summary.sh` — falha pré-existente
  vira issue imediata, não se tolera "idêntico ao baseline".
- **Worktree só nasce depois da spec + este plano estarem commitados na `develop`** —
  gotcha real do `git-operator.md` (worktree criada antes não enxerga o arquivo).

---

## Decisões-chave (revisar antes de aprovar cada onda)

| Decisão | Escolha | Porquê / alternativa |
|---|---|---|
| Onde vive a transação extraída | Tabelas de staging (`import_batches` + `staged_transactions`) | Isola dado sujo do núcleo; promove no commit. Alternativa (`status=DRAFT` em `transactions`) rejeitada: obrigaria toda query de negócio a filtrar `status<>DRAFT` — risco de vazamento. |
| Confidence-por-campo | Coluna **JSONB** (`fields`) em `staged_transactions` | Fiel ao contrato 1.3 (`{value, confidence}` por campo). Hibernate 6 mapeia nativo via `@JdbcTypeCode(SqlTypes.JSON)` — **sem nova dependência**. `ponytail:` teto — se precisar indexar por campo, promover pra coluna. |
| `posting_date` agora | `posting_date DATE NULL` em `transactions` (V23) | Roadmap 1.3: `transaction_date` (compra) vs `posting_date` (lançamento) desde o dia 1 evita migração dolorosa na Fase 5. Nullable, ainda não consumido. |
| Camada de LLM (Fase 1) | **Spring AI 2.0 `ChatClient`**, starter **Ollama** default | Agnóstico de fábrica (Ollama/OpenAI/qualquer via starter+config); multimodal (`.media`) + structured output tipado (`.entity`); menos código nosso que hand-roll. Alternativa (porta OpenAI-compat via `RestClient`) rejeitada como plano A: mais plumbing nosso pro mesmo resultado — vira plano B se o spike falhar. |
| Provider default/fallback | **Homelab-only** (Ollama, grátis) | Custo zero, sem segredo. Interface swappable por config; mede precisão no dataset; fallback pago = trabalho futuro só se o gap aparecer (YAGNI). |
| `requires_review` | Regra de código a partir de thresholds em `application.properties` | Produto controla a régua sem retreinar nada (1.3). |

---

## Workstream A — Tracking (operação GitHub, sem código)

Transforma o roadmap em backlog rastreável. Padrão de issues do projeto: adicionar ao
Project, setar Iteration + Priority, designar `sergiohcosta`.

- [ ] Criar milestones (1 por fase): `Fase 0 — Fundação`, `Fase 1 — MVP extração imagem`,
  `Fase 2 — CSV/OFX + lote`, `Fase 3 — PDF/templates/cobertura universal`,
  `Fase 4 — Categorização/dedup`, `Fase 5 — Conciliação`, `Fase 6 — Refinamentos`,
  `Trilho — Open Finance`.
- [ ] Criar **uma issue-épico por fase**, linkando a seção correspondente do roadmap e
  colando os **critérios de saída** (roadmap §2) como checklist. Prioridade: Fase 0/1 =
  Alta; demais = Média/Baixa.
- [ ] Fase 0 e Fase 1 recebem sub-issues acionáveis (as tasks dos Workstreams B e C
  abaixo).

---

## Workstream B — Fase 0 (Fundação) build

Objetivo: contrato de dados correto + batch fake consultado ponta a ponta. **Sem extrator
real ainda.**

### Backend
- [ ] **Migration `V23__import_foundation.sql`** (última hoje é V22):
  - `import_batches` (`id`, `tenant_id` FK, `created_by` FK users, `import_mode`,
    `source_type`, `extractor_used`, `extractor_version`, `status`, `created_at`,
    `updated_at`) + `CHECK` nos enums + índice `(tenant_id, status)`.
  - `staged_transactions` (`id`, `batch_id` FK, `tenant_id` FK **denormalizado**, `fields
    JSONB`, `suggested_category_code`, `suggested_category_confidence`,
    `overall_confidence`, `requires_review`, `duplicate_candidate_of` UUID null,
    `promoted_transaction_id` UUID null FK→transactions, `status`, `created_at`) + índice
    `(batch_id)`.
  - `ALTER TABLE transactions ADD COLUMN posting_date DATE NULL`.
- [ ] **Enums** (`domain/enums/`): `ImportMode` (NEW_TRANSACTIONS, RECONCILIATION),
  `ImportSourceType` (IMAGE, PDF_TEXT, PDF_SCANNED, CSV, OFX, AUDIO), `ImportBatchStatus`
  (PENDING, EXTRACTED, REVIEWED, COMMITTED, FAILED), `StagedTransactionStatus` (PENDING,
  CONFIRMED, DISCARDED).
- [ ] **Entidades** `domain/import/`: `ImportBatch`, `StagedTransaction` (JSONB via
  `@JdbcTypeCode(SqlTypes.JSON)` num record/Map tipado). Nunca expostas em controller.
- [ ] **DTOs** `dto/import/`: `ImportBatchResponseDTO`, `StagedTransactionResponseDTO`,
  `StagedFieldValueDTO` (`value`, `confidence`), `NormalizedTransactionDTO` (o schema 1.3 —
  reusado como saída tipada do extrator na Fase 1).
- [ ] **Repositories** escopados por tenant (`findByIdAndTenant`,
  `findAllByBatchIdAndTenant`), espelhando `AccountRepository`.
- [ ] **Service** `ImportService`: `createBatch(NormalizedBatch, User)` (grava batch +
  staged, deriva `requires_review` por threshold), `getBatch`/`listStaged`
  (tenant-scoped). Recebe `User user`, filtra por `user.getTenant()` (padrão
  `AccountService`).
- [ ] **Controller** `ImportController` (fino): `POST /api/imports/mock` (dev — cria batch
  de schema mockado, prova o ponta a ponta), `GET /api/imports/{id}`,
  `GET /api/imports/{id}/staged`. Sob `.anyRequest().authenticated()` — **nenhuma** regra
  nova em `SecurityConfigurations`.
- [ ] **Config** (`application.properties`): `import.review.overall-threshold=0.90`,
  `import.review.amount-threshold=0.95`.

### Contrato & dataset (obrigatórios)
- [ ] `api-spec/openapi.yaml`: paths + schemas acima; depois `./scripts/api-sync.sh`.
- [ ] Seed dev **`V24__seed_dev_import.sql`** (seeds são imutáveis — não editar V13): 1
  `import_batches` COMMITTED + 2 `staged_transactions` CONFIRMED com
  `promoted_transaction_id` apontando p/ transações existentes do dataset Família Costa.
  `posting_date` das transações-seed permanece null (não consumido até Fase 5).
- [ ] `docs/http/seed-dataset.http`: requests dos novos endpoints.

### Testes (baseline verde antes de começar)
- [ ] Integração (`@SpringBootTest`): inserir batch fake → ler via GET ponta a ponta.
- [ ] **Isolamento de tenant**: staged do tenant A **não** visível ao tenant B (invariante
  mais crítico do projeto).

### Critérios de saída (roadmap)
- [ ] Schema validado contra multi-transação, conciliação e granularidade de datas do
  cartão.
- [ ] Batch fake inserido e consultado ponta a ponta.

---

## Workstream C — Fase 1 (MVP extração: comprovante por imagem) build

Objetivo: provar o pipeline `imagem → transação` com uma transação por imagem,
**agnóstico de LLM, default Ollama grátis no homelab**.

### Backend
- [ ] **Task 0 — spike de build (bloqueante leve):** adicionar `spring-ai-bom` +
  `spring-ai-starter-model-ollama` (Spring AI **2.0**, versão pinada) ao
  `backend/pom.xml`; confirmar que resolve/compila no Spring Boot 4.0.1. Se a milestone
  escolhida não resolver, pinar a mais recente que resolva (ou cair pra porta
  OpenAI-compat via `RestClient` — plano B documentado, mesma interface).
- [ ] **Config** (`application.properties` + `application-prod`):
  `spring.ai.ollama.base-url=${OLLAMA_BASE_URL:http://localhost:11434}`,
  `spring.ai.ollama.chat.options.model=${OLLAMA_MODEL:qwen2.5vl}`. Prod aponta pro Ollama
  do homelab via env var. Sem chave de API (homelab-only).
- [ ] **`TransactionExtractor`** (interface, `service/import/`) — porta agnóstica; permite
  CSV/OFX/PDF futuros e swap de provider sem tocar no resto.
- [ ] **`VisionExtractor implements TransactionExtractor`**: injeta `ChatClient`; monta
  prompt fixo + imagem via `.user(u -> u.text(prompt).media(mimeType, bytes))`;
  `.call().entity(NormalizedTransactionDTO.class)` (structured output tipado — Spring AI
  força/valida o schema). **Sempre revalida a saída do nosso lado** (schema íntegro, valor
  plausível, data no período — guarda-corpo 1.5) independente do provider. Loga
  tokens/tempo por extração (critério de saída = custo/latência conhecidos; no homelab
  custo $ = 0, mas medir latência p95).
- [ ] **`ImportService` (extensão)**:
  - `POST /api/imports` (multipart `file` + `importMode`): valida MIME/tamanho →
    `VisionExtractor` → deriva `requires_review` no código por threshold → grava batch
    `EXTRACTED` + staged `PENDING`. Falha/erro de extração → batch `FAILED` (fallback:
    usuário lança manual no form existente).
  - `PATCH /api/imports/{id}/staged/{stagedId}`: editar campos antes de lançar.
  - `POST /api/imports/{id}/commit`: para cada staged PENDING não descartada → validação
    de sanidade → cria `Transaction` (mapeia `direction` debit→EXPENSE / credit→INCOME;
    `account` escolhido pelo usuário; `category` da sugestão ou do usuário) → seta
    `promoted_transaction_id`, `staged.status=CONFIRMED`, `batch.status=COMMITTED`. Reusa
    o caminho de criação de transação existente.
- [ ] Contrato: paths (multipart `requestBody` `format: binary`) + schemas no
  `openapi.yaml` → `api-sync.sh`.

### Frontend (`features/import/`, lazy-loaded, Signals-first)
- [ ] Componente de **upload** (Material file input / drag-drop) → `POST /api/imports`.
- [ ] Tela de **revisão** ("confirmar/editar antes de lançar"): lista editável, **badge
  destacando campos de baixa confiança** (usa `requires_review`/confidence do backend),
  pickers de conta/categoria, ação **Confirmar** (commit). Fallback manual visível quando
  batch `FAILED`.
- [ ] Lógica pura em `*-utils.ts` (formatação de confidence, threshold) testável sem
  TestBed; cliente via Orval.

### Dataset de avaliação (critério de saída; não versionar imagens sensíveis)
- [ ] Coletar 50–100 comprovantes reais variados; harness medindo **precisão ≥95% em
  valor e data** e **taxa de edição ≤10–15%** em campos de alta confiança, **contra o
  modelo Ollama escolhido**. Se o modelo free não bater a meta: trocar modelo local
  (qwen2.5vl / llama3.2-vision / minicpm-v) ou refinar prompt — só então reavaliar
  provider pago (fica registrado na issue, não neste escopo).

### Critérios de saída (roadmap)
- [ ] Precisão ≥95% valor/data no dataset (com modelo local).
- [ ] Taxa de edição ≤10–15%.
- [ ] Fallback manual funcionando.
- [ ] Latência p95 upload→preview aceitável.
- [ ] Custo/latência por extração conhecidos.

---

## Ordem SDD, branches e PRs

Fluxo do projeto (`git-operator.md`): **spec + este plano commitados na `develop` ANTES da
worktree.**

1. Spec `docs/superpowers/specs/2026-07-24-extracao-fundacao-e-mvp-imagem-design.md` +
   este plano → commit na `develop` (feito nesta sessão).
2. Workstream A (milestones/issues) — operação GitHub, em paralelo, sem worktree.
3. **Fase 0** em worktree `feat/import-fase0-fundacao` → PR próprio (fundação, baixo
   risco). SemVer **MINOR**.
4. **Fase 1** em worktree `feat/import-fase1-mvp-imagem` (a partir da `develop` já com
   Fase 0) → PR próprio. SemVer **MINOR**.

Fase 0 e Fase 1 são PRs separados (fronteiras distintas: schema vs. dependência de IA +
upload + frontend); não bundlar.

---

## Arquivos críticos

- Migrations: `db/migration/V23__import_foundation.sql`; seed
  `db/seed/V24__seed_dev_import.sql`.
- Domínio: `domain/enums/{ImportMode,ImportSourceType,ImportBatchStatus,
  StagedTransactionStatus}.java`; `domain/import/{ImportBatch,StagedTransaction}.java`.
- DTO/Service/Controller: `dto/import/*`,
  `service/import/{ImportService,TransactionExtractor,VisionExtractor}.java`,
  `controller/ImportController.java`,
  `repository/{ImportBatchRepository,StagedTransactionRepository}.java`.
- Contrato: `api-spec/openapi.yaml` (+ `./scripts/api-sync.sh`).
- Deps/Config: `backend/pom.xml` (spring-ai-bom 2.0 + `spring-ai-starter-model-ollama`,
  pinados); `application.properties` (+ `application-prod`): thresholds +
  `spring.ai.ollama.*`.
- Frontend: `frontend/src/app/features/import/` (+ rota lazy), cliente Orval regenerado.
- Docs: spec SDD; `summary.md`/`domain.md`/`database-schema.md` atualizados;
  `docs/http/seed-dataset.http`.
- Infra (homelab): serviço Ollama com um modelo de visão em `~/homelab-k8s` (fora do
  escopo de código deste plano; pré-requisito de ops p/ prod — dev usa Ollama local).

## Reuso de padrões existentes (não reinventar)

- Escopo de tenant: serviços recebem `User user`, filtram `user.getTenant()` — espelhar
  `AccountService`/`AccountRepository`.
- Erros: relançar via `com.fintech.api.exception.EntityNotFoundException` →
  `GlobalExceptionHandler` (nunca `jakarta...EntityNotFoundException`).
- DTO nas bordas; entidade JPA nunca no controller.
- JSONB: `@JdbcTypeCode(SqlTypes.JSON)` do Hibernate 6 (sem lib extra).
- Auth: `.anyRequest().authenticated()` já cobre `/api/imports` — não mexer em
  `SecurityConfigurations`.

## Verificação (end-to-end)

- [ ] **Baseline verde** antes de iniciar: `./scripts/test-summary.sh` (falha
  pré-existente → abrir issue, não tolerar).
- [ ] **Fase 0**: `./mvnw -f backend/pom.xml -Dtest=ImportServiceTest test` (fake batch
  ponta a ponta + isolamento de tenant); `GET /api/imports/{id}` via `seed-dataset.http`
  retornando o batch seed V24.
- [ ] **Fase 1** (Ollama de pé — dev `localhost:11434` com modelo de visão puxado;
  backend em :8080): `curl -F file=@comprovante.jpg -F importMode=NEW_TRANSACTIONS
  .../api/imports` → GET staged mostra valor/data extraídos com confidence →
  `POST /commit` → transação em `GET /api/transactions` com valor/data corretos. Extrator
  com `ChatClient` **mockado** no teste unitário (não bate no Ollama na suíte).
- [ ] **Frontend**: `npm start`, subir imagem → preview com badges de baixa confiança →
  Confirmar → transação na lista. Specs via `./scripts/test-summary.sh frontend` (nunca
  `npx vitest` cru).
- [ ] **Extração**: rodar o harness do dataset contra o modelo Ollama (≥95% valor/data;
  latência logada).

## Riscos / atenção

- **Spring AI 2.0 é milestone** → Task 0 (spike de build) valida a resolução no Boot
  4.0.1 antes de comprometer; plano B = porta OpenAI-compat via `RestClient` (mesma
  interface `TransactionExtractor`).
- **Compute do homelab p/ visão** (GPU/latência) é pré-requisito de ops — não bloqueia o
  design (config-driven; dev roda local).
- **Teto de qualidade do modelo free**: se não bater 95% valor/data, trocar modelo local
  ou refinar prompt; fallback pago fica registrado como trabalho futuro (funil 1.2), fora
  deste escopo.

## Impacto SemVer

**MINOR** por PR (endpoints/campos novos, retrocompatíveis no contrato; pré-1.0 → feature
= MINOR). Registrar no campo "Impacto SemVer" de cada PR.

---

## Execução multiagente (orquestração)

Dependência dura: **spec commitada na `develop` antes de criar worktree** (senão o agente
não enxerga o arquivo — gotcha do `git-operator.md`). Fase 1 depende do schema da Fase 0.
Logo, não é blast paralelo — são ondas.

**Equipe:**

| Agente | Modelo | Onda | Escopo | Isolamento |
|---|---|---|---|---|
| **spec-agent** | Sonnet 5 | 0 | Escreve a spec + este plano a partir do plano aprovado; commita ambos na `develop` | direto na `develop` (regra SDD) |
| **tracking-agent** | Sonnet 5 | 1 (∥) | Milestones Fase 0..6 + Open Finance; issue-épico por fase (link roadmap + critérios de saída); add ao Project, Iteration+Priority, assignee `sergiohcosta` | GitHub only, sem código |
| **fase0-agent** | Opus 4.8 | 1 (∥) | Fase 0 completa: migration V23, enums, entidades JSONB, DTOs, repos, `ImportService`, mock controller, openapi+`api-sync`, seed V24, testes (incl. **isolamento de tenant**). Roda a suíte. | worktree `feat/import-fase0-fundacao` |
| **fase1-agent** | Opus 4.8 | 2 | Fase 1: spike Spring AI 2.0, `VisionExtractor` (ChatClient/Ollama), endpoints upload/commit, feature frontend `import/`, openapi+`api-sync`+orval, testes (ChatClient mockado) | worktree `feat/import-fase1-mvp-imagem` |

**Ondas:**
- **Onda 0** — spec-agent (sequencial; desbloqueia worktrees). *(Concluída nesta sessão.)*
- **Onda 1** — tracking-agent ∥ fase0-agent (paralelo real).
- **Onda 2** — fase1-agent, **após** Fase 0 estar na `develop`.

**Modelos/efforts:** o Agent tool expõe só `model` (não `effort`). Intenção codificada via
escolha de modelo — **Opus 4.8** onde correção é crítica (schema, isolamento de tenant,
integração IA); **Sonnet 5** para doc e ops mecânica. Prompts exigem rigor alto onde
importa.

**Política de merge:** agentes **implementam + testam em worktree isolada**; **merge em
`develop`, push e PRs ficam retidos para revisão do dev** (nunca auto-merge/push). Entre
Onda 1 e Onda 2, o merge Fase 0 → `develop` passa por aprovação.
