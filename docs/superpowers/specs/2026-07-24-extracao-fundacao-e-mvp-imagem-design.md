# Spec: Extração Multi-Mídia — Fundação e MVP de Extração por Imagem (Fase 0 + Fase 1)

**Data:** 2026-07-24
**Status:** aprovado
**Fonte do produto:** `docs/roadmap-extracao-e-conciliacao.md` (roadmap estratégico — 6 fases + trilho Open Finance)
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto e escopo

`docs/roadmap-extracao-e-conciliacao.md` é hoje um documento de **estratégia**
(status: ideação/arquitetura). Nada dele existe no código: zero import/extração/
conciliação, `transactions` só tem `date` (sem `posting_date`), sem tabelas de batch.
"Implantar o roadmap" significa tirá-lo do papel — e a ordenação do próprio roadmap
("construir de dentro pra fora") manda começar pela **Fase 0 (Fundação)**, explicitamente
descrita como "fase de dias" e pré-requisito de todo o resto, seguida da **Fase 1 (MVP de
extração: comprovante único por imagem)**, que prova o pipeline ponta a ponta com o caso
mais simples e mais frequente.

**Escopo desta spec: só Fase 0 + Fase 1.** Planejar código para as 6 fases + trilho Open
Finance de uma vez seria absurdo — cada fase é multi-semana e depende de aprendizado das
anteriores (roadmap §3: "cada transição exige funcionalidade + qualidade + aprendizado").
Fases 2–6 (CSV/OFX em lote, PDF/templates/cobertura universal, categorização/dedup,
conciliação, refinamentos) e o trilho Open Finance ficam registrados só como
milestones/issues no GitHub — rastreáveis, mas não especificados em código aqui.

**Objetivo Fase 0:** contrato de dados correto antes de qualquer extrator real — um batch
fake inserido e consultado ponta a ponta.

**Objetivo Fase 1:** provar o pipeline `imagem → transação` com uma transação por imagem,
usando um extrator de visão **agnóstico de provider**, com adaptador **Ollama** grátis
rodando no homelab.

> **Nota pedagógica:** a Fase 1 introduz a **primeira dependência de IA generativa do
> projeto** e o **primeiro upload de arquivo** — duas fronteiras arquiteturais novas.
> Revisar com atenção especial: (a) a saída do modelo é *untrusted input*, validada sempre
> do nosso lado, independente do provider (§2.f); (b) `requires_review` é **derivado no
> código** por threshold, nunca decidido pelo modelo (§2.e); (c) compute do homelab para
> visão (GPU/latência) é risco de **ops**, não de design — a interface é config-driven, e
> dev roda Ollama local enquanto isso (§12).

## 2. Decisões arquiteturais

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Onde vive a transação extraída | Staging separado: `import_batches` + `staged_transactions`; promoção para `transactions` só no commit | Estender `transactions` com `status=DRAFT` |
| b | Confidence por campo | Coluna `fields JSONB` via `@JdbcTypeCode(SqlTypes.JSON)` | Colunas dedicadas por campo (`amount_confidence`, `date_confidence`, ...) |
| c | Datas de cartão | `posting_date DATE NULL` em `transactions` já na Fase 0 | Adiar para a Fase 5 (conciliação de cartão) |
| d | Camada de LLM | Spring AI 2.0 `ChatClient`, starter **Ollama** default | Hand-roll de cliente HTTP por provider |
| e | Provider default/fallback | **Homelab-only** (Ollama, grátis); interface swappable por config | Fallback pago (funil de custo 1.2) já nesta fase |
| f | `requires_review` | Regra de código a partir de thresholds em `application.properties` | Modelo decide/retorna `requires_review` |
| g | Confiabilidade da saída do modelo | Validação de sanidade sempre do nosso lado (guarda-corpo 1.5), independente do provider | Confiar na saída estruturada do modelo sem revalidação |

**(a) Staging separado, não `DRAFT` em `transactions`.**
`transactions` é a tabela núcleo do sistema — todo saldo, toda fatura, todo dashboard lê
dela sem esperar encontrar dado incompleto ou incerto. Se a transação extraída nascesse
ali com `status=DRAFT`, **toda query de negócio existente** (saldo de conta, dashboard,
planejamento, faturas) precisaria passar a filtrar `status<>DRAFT` — um risco de vazamento
de dado sujo (ou pior, de tenant, se o filtro de staging fosse esquecido em alguma query)
espalhado pelo núcleo inteiro. Isolar em tabelas próprias (`import_batches` +
`staged_transactions`) mantém `transactions` exatamente como está hoje: cada linha ali é
um fato confirmado. A promoção (staged → transaction) é o único ponto de contato entre os
dois mundos, e reusa o caminho de criação de transação já existente — nenhuma regra de
negócio nova para lançamento.

**(b) Confidence-por-campo em JSONB.**
O contrato normalizado (roadmap §1.3) exige `{value, confidence}` por campo, não só por
transação — a UI precisa destacar exatamente o campo duvidoso (valor é mais crítico que
descrição). Modelar isso como colunas individuais (`amount_confidence`,
`date_confidence`...) enrijece o schema a cada campo novo do contrato. O Hibernate 6 já
mapeia JSON nativamente via `@JdbcTypeCode(SqlTypes.JSON)` — **zero dependência nova**. Se
algum dia for necessário indexar/filtrar por um campo específico de confidence, esse é o
sinal para promover aquele campo a coluna própria — não antes (`ponytail:` teto).

**(c) `posting_date` desde a Fase 0.**
O roadmap (§1.3) já separa `transaction_date` (data da compra) de `posting_date` (data do
lançamento/fechamento) desde o contrato normalizado, porque a conciliação de cartão de
crédito (Fase 5) precisa das duas — sem isso, bater fatura fechada contra transações
lançadas exige granularidade que `transactions.date` sozinho não tem. Adicionar a coluna
agora (`ALTER TABLE transactions ADD COLUMN posting_date DATE NULL`, nullable, não
consumida ainda) custa uma migration trivial; adiar para a Fase 5 custaria uma migração
dolorosa em uma tabela já com volume de produção. Mesmo racional do
`recurrence_occurrence` na spec do motor de recorrência: campo estrutural barato agora,
caro depois.

**(d) LLM agnóstico via Spring AI 2.0, Ollama default.**
`TransactionExtractor` é uma porta (interface) implementada por `VisionExtractor` — o
serviço de negócio nunca fala com Ollama, OpenAI ou qualquer provider diretamente. Spring
AI 2.0 `ChatClient` dá essa abstração de fábrica: multimodal (`.media()` para anexar a
imagem) e *structured output* tipado (`.entity(NormalizedTransactionDTO.class)`, o próprio
framework força/valida o schema de saída), com menos código nosso do que orquestrar
chamadas HTTP + parsing manual por provider. Trocar de Ollama para outro provider é trocar
o starter Maven + `application.properties` — o código de `VisionExtractor` não muda.

**(e) Homelab-only agora — YAGNI deliberado.**
O roadmap (§1.2, "funil de custo") prevê eventualmente um fallback pago quando o modelo
grátis não bater a meta de qualidade. Construir esse fallback **antes** de medir o gap é
especular sobre um problema que talvez não exista — o dataset de avaliação da Fase 1 (§10)
é o que vai dizer se o Ollama local basta. A interface já é *swappable por config*
(decisão d), então adicionar um provider pago depois é configuração + starter novo, não
redesenho. Custo zero, sem segredo de API para gerenciar, sem superfície de billing nesta
fase.

**(f) `requires_review` derivado no código, nunca pelo modelo.**
Se o próprio modelo decidisse "isso precisa de revisão", o produto ficaria refém de
retreinar/reprompt-ar para ajustar a régua. Derivar por threshold em
`application.properties` (`import.review.overall-threshold`,
`import.review.amount-threshold`) dá ao produto controle direto e auditável sobre quando
uma transação exige olho humano — e o mesmo threshold serve para qualquer extrator futuro
(CSV, OFX, PDF), não só para o de visão.

**(g) Saída do modelo é *untrusted input*.**
Independente de quão bem o *structured output* do Spring AI valida o **schema** da
resposta (tipos corretos, campos presentes), ele não valida a **plausibilidade** do
conteúdo — um modelo pode alucinar um valor com formato perfeitamente válido. O guarda-
corpo do roadmap (§1.5) — "toda saída passa por validações determinísticas pós-extração,
independente da camada que extraiu" — se aplica aqui com a mesma força que se aplicaria a
um parser de CSV quebrado. Este é o mesmo princípio (erro explícito > erro silencioso) que
rege o resto do sistema; a extração via IA não ganha uma exceção.

## 3. Invariante inviolável — isolamento de tenant

A regra mais grave do projeto (CLAUDE.md: "vazamento de tenant é o bug mais grave possível
neste projeto") se aplica ao pipeline de import sem exceção:

- Toda query de `ImportService` recebe `User user` e filtra por `user.getTenant()` —
  mesmo padrão de `AccountService`/`AccountRepository`.
- `staged_transactions.tenant_id` é **denormalizado** (a linha já pertence a um
  `import_batches.tenant_id`, que por sua vez referencia o tenant). O denormalizado não é
  redundância acidental: é a defesa nº1 contra vazamento — qualquer query de leitura de
  staged filtra `tenant_id` **diretamente**, sem depender de um `JOIN` correto em
  `import_batches` estar presente em todo repository method escrito hoje ou no futuro. Um
  `JOIN` esquecido é o tipo de bug que passa despercebido em revisão; uma coluna
  `tenant_id` ausente do `WHERE` é mais fácil de pegar em teste e em code review.
- Repositórios seguem o padrão `findByIdAndTenant`/`findAllByBatchIdAndTenant` já usado em
  todo o projeto.
- Teste obrigatório (não opcional): staged do tenant A **não** pode ser visível ao tenant B
  — mesmo padrão de teste que os demais domínios do sistema já exigem.

## 4. Modelo de dados

### 4.1 Migrations

| Versão | Conteúdo |
|---|---|
| **V23** | `import_batches`, `staged_transactions`, `ALTER TABLE transactions ADD COLUMN posting_date` |
| **V24** | seed `dev` — 1 `import_batches` COMMITTED + 2 `staged_transactions` CONFIRMED referenciando transações existentes da Família Costa |

(A migration mais recente hoje é V22 — `paid_invoice_id`; V23/V24 seguem a numeração.)

### 4.2 `import_batches` (tenant-scoped)

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | UUID PK | |
| `tenant_id` | UUID FK NOT NULL | isolamento de tenant |
| `created_by` | UUID FK → `users` | autoria |
| `import_mode` | VARCHAR | `NEW_TRANSACTIONS \| RECONCILIATION` |
| `source_type` | VARCHAR | `IMAGE \| PDF_TEXT \| PDF_SCANNED \| CSV \| OFX \| AUDIO` |
| `extractor_used` | VARCHAR | proveniência (ex.: `vision_ollama_qwen2.5vl`) |
| `extractor_version` | VARCHAR | proveniência (versão do prompt/modelo) |
| `status` | VARCHAR | `PENDING \| EXTRACTED \| REVIEWED \| COMMITTED \| FAILED` |
| `created_at` / `updated_at` | TIMESTAMP | |

`CHECK` nos enums + índice `(tenant_id, status)` (consulta comum: "batches pendentes de
revisão do tenant").

### 4.3 `staged_transactions`

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | UUID PK | |
| `batch_id` | UUID FK NOT NULL → `import_batches` | |
| `tenant_id` | UUID FK NOT NULL | **denormalizado** — ver §3 |
| `fields` | JSONB | `{value, confidence}` por campo (amount, transaction_date, posting_date, description, direction, payment_method) |
| `suggested_category_code` | VARCHAR NULL | sugestão heurística |
| `suggested_category_confidence` | NUMERIC NULL | |
| `overall_confidence` | NUMERIC | agregado da transação |
| `requires_review` | BOOLEAN | **derivado no código**, não gravado pelo modelo (§2.f) |
| `duplicate_candidate_of` | UUID NULL | reservado — dedup real só na Fase 4; campo existe desde já (custo zero, mesma lógica do `posting_date`) |
| `promoted_transaction_id` | UUID NULL FK → `transactions` | preenchido no commit |
| `status` | VARCHAR | `PENDING \| CONFIRMED \| DISCARDED` |
| `created_at` | TIMESTAMP | |

Índice `(batch_id)`. `fields JSONB` mapeado via `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6
nativo — decisão b).

### 4.4 Acréscimo a `transactions`

| Coluna | Tipo | Notas |
|---|---|---|
| `posting_date` | DATE NULL | decisão c — não consumido até a Fase 5 |

### 4.5 Enums (`domain/enums/`)

```
ImportMode              : NEW_TRANSACTIONS | RECONCILIATION
ImportSourceType        : IMAGE | PDF_TEXT | PDF_SCANNED | CSV | OFX | AUDIO
ImportBatchStatus       : PENDING | EXTRACTED | REVIEWED | COMMITTED | FAILED
StagedTransactionStatus : PENDING | CONFIRMED | DISCARDED
```

### 4.6 Entidades e DTOs

- `domain/import/{ImportBatch,StagedTransaction}.java` — entidades JPA, nunca expostas em
  controller. `StagedTransaction.fields` mapeado num record/Map tipado via
  `@JdbcTypeCode(SqlTypes.JSON)`.
- `dto/import/{ImportBatchResponseDTO, StagedTransactionResponseDTO, StagedFieldValueDTO,
  NormalizedTransactionDTO}.java`. `StagedFieldValueDTO` é o par `{value, confidence}`;
  `NormalizedTransactionDTO` é o schema §5.2 — reusado como saída **tipada** do extrator na
  Fase 1 (`ChatClient.call().entity(NormalizedTransactionDTO.class)`), fechando o círculo
  entre o contrato do roadmap e o código.
- `repository/{ImportBatchRepository,StagedTransactionRepository}.java` — tenant-scoped,
  espelhando `AccountRepository`.
- `service/ImportService.java` — `createBatch`, `getBatch`, `listStaged` (Fase 0);
  estendido na Fase 1 com upload/edição/commit (§6).
- `controller/ImportController.java` — fino, delega ao service. Sob
  `.anyRequest().authenticated()` — **nenhuma** regra nova em `SecurityConfigurations`
  (import não é operação restrita a ADMIN).

## 5. Contrato de API

### 5.1 Endpoints

| Fase | Método | Rota | Descrição |
|---|---|---|---|
| 0 | POST | `/api/imports/mock` | (dev) cria batch a partir de um schema mockado — prova o ponta a ponta sem extrator real |
| 0 | GET | `/api/imports/{id}` | detalhe do batch |
| 0 | GET | `/api/imports/{id}/staged` | lista as staged do batch |
| 1 | POST | `/api/imports` | multipart `file` + `importMode` — aciona `VisionExtractor` |
| 1 | PATCH | `/api/imports/{id}/staged/{stagedId}` | edita campos de uma staged antes de lançar |
| 1 | POST | `/api/imports/{id}/commit` | promove as staged `PENDING` não descartadas a `Transaction` |

Todos `authenticated`, escopados por tenant no service — mesmo padrão de
`recurrence-rules` (sem role específica). Spec-first: `api-spec/openapi.yaml` primeiro →
`./scripts/api-sync.sh`. O endpoint de upload usa `requestBody` multipart
(`format: binary`).

### 5.2 Schema normalizado (contrato central, roadmap §1.3)

Todo extrator — o de visão desta spec e os futuros (CSV/OFX/PDF) — converge para o mesmo
schema, desacoplando extração de regra de negócio:

```json
{
  "batch_id": "uuid",
  "import_mode": "new_transactions | reconciliation",
  "source": {
    "type": "image | pdf_text | pdf_scanned | csv | ofx | audio",
    "extractor_used": "vision_v2 | pdf_template:itau_fatura | csv_template:nubank | ofx_parser",
    "extractor_version": "2026-06-01"
  },
  "transactions": [
    {
      "transaction_id": "uuid",
      "fields": {
        "amount":           { "value": 127.50,             "confidence": 0.98 },
        "transaction_date": { "value": "2026-06-28",        "confidence": 0.95 },
        "posting_date":     { "value": "2026-06-30",        "confidence": 0.90 },
        "description":      { "value": "PADARIA SAO JOSE",  "confidence": 0.90 },
        "direction":        { "value": "debit",             "confidence": 0.99 },
        "payment_method":   { "value": "pix",                "confidence": 0.85 }
      },
      "suggested_category": { "value": "alimentacao", "confidence": 0.70, "source": "heuristic" },
      "overall_confidence": 0.91,
      "requires_review": false,
      "duplicate_candidate_of": null
    }
  ]
}
```

Pontos-chave que o modelo de dados (§4) traduz para colunas: **confidence por campo** (não
só por transação — a UI destaca só o campo problemático); **`requires_review` derivado por
regra no código**, nunca decidido pelo modelo (§2.f); **proveniência completa**
(`extractor_used` + versão — toda transação sabe qual regra/modelo a gerou); **datas
distintas desde o dia 1** (`transaction_date` vs `posting_date`, §2.c).

Na Fase 1, `NormalizedTransactionDTO` é o formato de saída **tipado** que o
`VisionExtractor` pede ao `ChatClient` (`.entity(NormalizedTransactionDTO.class)`) — o
mesmo schema, só que já desserializado pelo Spring AI em vez de JSON cru.

## 6. Fluxo de promoção staged → transaction (`commit`)

Para cada `StagedTransaction` `PENDING` não descartada do batch:

1. **Validação de sanidade** (guarda-corpo §2.g) sobre os valores atuais (originais ou já
   editados via `PATCH`): schema íntegro, valor plausível, data dentro do período
   esperado.
2. **Direção → tipo:** `fields.direction = "debit"` → `TransactionType.EXPENSE`;
   `"credit"` → `TransactionType.INCOME`.
3. **Conta:** escolhida pelo usuário na tela de revisão (o schema normalizado não carrega
   `account_id` — a extração não sabe, e não deveria adivinhar, em qual conta do tenant o
   lançamento cai).
4. **Categoria:** a sugestão (`suggested_category_code`) se o usuário não sobrescreveu, ou
   a categoria escolhida por ele via `PATCH`.
5. Cria a `Transaction` **reusando o caminho de criação existente** (mesma validação, mesma
   lógica de fatura se a conta for cartão) — nenhuma regra de lançamento nova.
6. Seta `staged.promoted_transaction_id`, `staged.status = CONFIRMED`.
7. Quando todas as staged do batch estão resolvidas (confirmadas ou descartadas),
   `batch.status = COMMITTED`.

Falha na extração (Fase 1) → `batch.status = FAILED`; o fallback é o formulário manual de
transação já existente no sistema — a Fase 1 nunca bloqueia o usuário, só evita o trabalho
manual quando a extração funciona.

## 7. Frontend (Fase 1 — `features/import/`, lazy, Signals-first)

- **Upload:** Material file input / drag-drop → `POST /api/imports`.
- **Revisão:** lista editável das staged, badge destacando campos de **baixa confiança**
  (usa `requires_review`/confidence vindos do backend — a UI não recalcula threshold),
  pickers de conta/categoria, ação **Confirmar** → commit. Fallback manual visível quando
  `batch.status = FAILED`.
- Lógica pura (formatação de confidence, cálculo de badge) em `*-utils.ts`, testável sem
  `TestBed`; cliente via Orval.

## 8. Testes

- **Fase 0 (integração, `@SpringBootTest`):** inserir batch fake → ler via `GET` ponta a
  ponta; **isolamento de tenant** (staged do tenant A invisível ao tenant B) — o teste mais
  crítico desta spec.
- **Fase 1:** `VisionExtractor` testado com `ChatClient` **mockado** — a suíte nunca bate
  no Ollama real. Testes de `ImportService.commit` cobrindo direção→tipo, guarda-corpo de
  sanidade e o caminho de falha (`batch.status = FAILED`).

## 9. Dataset de testes (regra inviolável do projeto)

- **Seed `V24` (perfil `dev`):** 1 `import_batches` `COMMITTED` + 2 `staged_transactions`
  `CONFIRMED`, `promoted_transaction_id` apontando para transações já existentes do dataset
  Família Costa. `posting_date` das transações-seed permanece `NULL` (não consumido até a
  Fase 5) — seeds são migrations imutáveis, não se edita `V13`.
- **`docs/http/seed-dataset.http`:** requests para os endpoints novos (Fase 0 e Fase 1).
- Dataset de **avaliação** da Fase 1 (50–100 comprovantes reais) é separado do dataset de
  testes automatizados — não versionado no repositório (imagens potencialmente sensíveis);
  ver §11.

## 10. Critérios de saída de cada fase (do roadmap, § 2)

**Fase 0** (binários, fase de dias — não semanas):
- [ ] Schema validado contra os casos futuros: multi-transação, conciliação, granularidade
  de datas do cartão.
- [ ] Batch fake inserido e consultado ponta a ponta com dados mockados.

**Fase 1:**
- [ ] Precisão ≥95% em valor e data, num dataset próprio de 50–100 imagens reais variadas.
- [ ] Taxa de edição ≤10–15% nos campos marcados como alta confiança.
- [ ] Taxa de falha total conhecida, com fallback manual funcionando.
- [ ] Latência p95 upload→preview aceitável.
- [ ] Custo real por extração (tokens/imagem) conhecido — no homelab, custo em $ é zero;
  mede-se latência.

## 11. Riscos

| Risco | Mitigação |
|---|---|
| Spring AI 2.0 é uma *milestone* (não GA) — risco de não resolver/compilar no Spring Boot 4.0.1 | Task 0 da Fase 1 é um spike de build isolado, bloqueante leve, antes de comprometer o resto; plano B = porta OpenAI-compat via `RestClient`, atrás da mesma interface `TransactionExtractor` (o resto do pipeline não muda) |
| Compute do homelab para visão (GPU/latência) | Risco de **ops**, não de design — a interface é config-driven (`OLLAMA_BASE_URL`/`OLLAMA_MODEL`); dev roda Ollama local enquanto a infra do homelab amadurece |
| Teto de qualidade do modelo grátis (pode não bater 95% valor/data) | Trocar modelo local (qwen2.5vl / llama3.2-vision / minicpm-v) ou refinar prompt primeiro; fallback pago só entra como trabalho futuro registrado em issue, fora deste escopo (decisão e — YAGNI) |

## 12. Fora de escopo

- **Fases 2–6 do roadmap** (CSV/OFX em lote, PDF/registry de templates, categorização/dedup
  inteligente, motor de conciliação, refinamentos pós-conciliação) e o **trilho Open
  Finance** — viram milestones + issue-épica por fase no GitHub (Workstream A do plano de
  implantação), não especificados em código aqui.
- **Fallback de provider pago** (funil de custo, roadmap §1.2) — YAGNI até o dataset de
  avaliação mostrar um gap real (decisão e).
- **Dedup real** (`duplicate_candidate_of` fica como coluna reservada, sem lógica —
  Fase 4).
- **Múltiplas transações por documento** (fatura/extrato) — Fase 1 é 1 transação por
  imagem; lote é Fase 2.
- **RLS a nível de banco** — o isolamento de tenant desta spec é por filtro de query
  (padrão já usado em todo o sistema), não por *row-level security* do PostgreSQL (item em
  aberto separado no roadmap de fronteira do projeto).
