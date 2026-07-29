# Extração por visão — Gemini primário / Ollama fallback: Plano de Execução

> **For agentic workers:** Ondas com checkbox (`- [ ]`) para tracking. **A Onda 1 é bloqueante
> para todas as demais** — é refatoração sem comportamento novo e precisa fechar com a suíte
> verde antes de qualquer dependência nova entrar no `pom.xml`.

**Goal:** Usar o Gemini (Google AI Studio, tier free) como provider primário de extração por
visão, mantendo o Ollama do homelab como fallback automático quando a cota acabar ou o provider
gerenciado falhar — sem que falha de *conteúdo* (imagem ilegível, extrato multi-transação) dispare
fallback, e sem quebrar quem roda o projeto sem nenhuma chave de API.

**Architecture:** o `ExtractionRouter` (funil por formato) **não muda**. Dentro do
`VisionExtractor` — que segue sendo o único `TransactionExtractor` de `IMAGE` — entra uma porta
nova `VisionModelClient`, injetada como `List<>` ordenada. A porta devolve o
`LlmReceiptExtractionDTO` **cru**; o guarda-corpo de plausibilidade roda uma vez depois da cadeia,
ficando estruturalmente fora do fallback.

**Tech Stack:** Java 21 · Spring Boot 4.0.1 · Spring AI 2.0.0-M2 ·
`spring-ai-starter-model-google-genai` (dependência nova) · `spring-ai-starter-model-ollama` ·
JUnit 5 + Mockito + AssertJ.

**Spec de referência:** `docs/superpowers/specs/2026-07-29-extracao-gemini-primario-ollama-fallback-design.md`
**Épico raiz:** #154 · **Issue:** #197
**Impacto SemVer:** MINOR (capacidade nova; `api-spec/openapi.yaml` inalterado)

## Global Constraints

- **Schema só via Flyway, migrations imutáveis.** Próximas versões livres: **V28** (schema —
  proveniência estruturada) e **V29** (seed dev). **Não editar V23/V24/V25/V26/V27.**
- **Dataset vivo:** V28 adiciona colunas em tabela existente → seed V29 na **mesma entrega**
  (regra inviolável de `dataset.md`). Como V24/V27 já foram aplicados, o V29 faz `UPDATE` nos
  batches seed — precedente do V18 sobre o V16.
- **`api-spec/openapi.yaml` não muda** → **não** rodar `./scripts/api-sync.sh`, não tocar no
  frontend. Se algo nesta feature exigir mudança de contrato, **pare**: virou outra spec.
- **Nenhuma alteração em `SecurityConfigurations.java`** — `/api/imports` já é `authenticated()`.
- **Segredo nunca versionado:** `GEMINI_API_KEY` só por env var. Nunca logar a chave, nem
  parcialmente, nem dentro de mensagem de erro.
- **Extratores não conhecem tenant** — recebem bytes, devolvem `NormalizedBatchDTO`. Continua
  valendo para a porta nova.
- **Exceções:** `com.fintech.api.exception.*` e `ExtractionException` — nunca deixar exceção de
  infra (ou mensagem de provider) cruzar a borda da API.
- **PT-BR** em comentários/commits, imperativo, identificadores em inglês, **sem**
  `Co-Authored-By`.
- **Baseline verde antes de iniciar:** `./scripts/test-summary.sh backend`. Falha pré-existente
  vira issue imediata.
- **Suíte backend demora >7 min:** rodar em background; feedback rápido com
  `-Dtest=VisionExtractorTest`.
- **Worktree só depois** de spec + este plano commitados na `develop`.

---

## Decisões-chave (revisar antes de aprovar)

| Decisão | Escolha | Porquê / alternativa |
|---|---|---|
| Onde vive o fallback | Porta `VisionModelClient` **dentro** do `VisionExtractor` | O router escolhe por formato; dar a ele retry entre modelos mistura dois eixos e contamina CSV/OFX. |
| O que a porta devolve | `LlmReceiptExtractionDTO` **cru** | Mantém o guarda-corpo (multi-transação, `amount`) fora do laço de fallback **por construção** — impossível tentar o Ollama porque o Gemini disse "isto é um extrato". |
| Ordem dos providers | `@Order` (Gemini 10, Ollama 20) | Mesmo mecanismo que o `ExtractionRouter` já usa. Consistência > flexibilidade de uma property de ordem que ninguém vai mexer. |
| Quando cair pro próximo | Só falha de **disponibilidade** (429/5xx/timeout/401/403/400) | Falha de conteúdo repetida no outro modelo paga latência dobrada pra mesma conclusão e mascara o `failureReason` de #193. |
| 401/403 | Cai pro fallback, mas loga **ERROR** | Degradar é melhor UX que falhar; silenciar faria o primário morrer sem ninguém notar. O ERROR torna a degradação visível. |
| Gemini sem chave | Bean não existe (`@ConditionalOnProperty`) | Clone novo, CI e suíte rodam sem segredo, com comportamento bit a bit igual ao de hoje. Reversível em prod por env var, sem redeploy. |
| Modelo Gemini | Flash mais recente do free tier, **por env var** | `gemini-2.0-flash` foi aposentado em mar/2026. ID de modelo tem prazo de validade; assar no código garante quebra futura anunciada. |
| Proveniência | Colunas estruturadas (V28) **além** do `extractor_used` | `LIKE 'vision_gemini_%'` não é `GROUP BY` honesto, e "caiu no fallback?" é indecidível pela string. Ver spec §5.1. |
| "Houve fallback?" | `fallback_from IS NULL` | Booleano separado + provider seriam dois campos capazes de divergir. NULL já responde. |
| Proveniência na API | **Não** exposta nesta entrega | Dado operacional, consulta é SQL. Mostrar "IA local vs. gerenciada" ao usuário é UX — pertence à metade B da Fase 2. |
| Prompt | O mesmo para os dois providers | Divergir prompts por modelo duplica a superfície de manutenção antes de haver evidência de que é preciso (#191 é quem dará essa evidência). |

---

## Onda 1 — Porta `VisionModelClient` (BLOQUEANTE, sem comportamento novo)

Refatoração pura: extrair a chamada ao modelo do `VisionExtractor` para uma porta, com **uma só**
implementação (Ollama). Nenhuma dependência nova, nenhum provider novo. O valor é isolar o risco:
se algo quebrar aqui, quebrou no refactor, não na integração com o Google.

- [ ] **1.1** Criar `VisionModelClient` (interface) em `service/imports/vision/`:
      `extract(String prompt, MimeType, Resource)`, `providerId()`, `modelId()`. Javadoc
      explicando por que devolve o DTO cru (spec §3.1).
- [ ] **1.2** Criar `OllamaVisionClient` (`@Component`, `@Order(20)`): move para cá o
      `chatClient.prompt()...entity(...)`, o `ByteArrayResource` anônimo com `getFilename()`
      (o comentário sobre o zlib error do multipart vai junto — é conhecimento caro) e o
      `@Value` do modelo. `providerId()` → `"ollama"`.
- [ ] **1.3** `VisionExtractor` passa a receber `List<VisionModelClient>` no construtor; usa o
      primeiro da lista. Mantém prompt, `supports()`, `toNormalizedBatch()` e todo o guarda-corpo
      onde estão. `extractorUsed` passa a ser montado de
      `"vision_" + client.providerId() + "_" + client.modelId()` — mesmo formato de hoje.
- [ ] **1.4** Ajustar `VisionExtractorTest`: os deep stubs de `ChatClient` saem (viram um fake de
      `VisionModelClient`, bem mais simples) e migram para um `OllamaVisionClientTest` novo, que
      cobre só a mecânica do `ChatClient`. Todos os casos existentes (mapeamento, multi-transação,
      `amount` inválido, `supports()`) continuam passando **sem alteração de asserção**.
- [ ] **1.5** `VisionAiConfig`: atualizar o javadoc que hoje afirma "trocar provider é trocar o
      starter" — passou a ser meia-verdade (spec §1.1). Deixar o `ChatClient` como está.

**Gate:** `./scripts/test-summary.sh backend` verde. `git diff` não mostra mudança de
comportamento observável — `extractorUsed` gerado é idêntico ao de antes.
**Commit:** `refactor(import): extrai porta VisionModelClient do extrator de visão`

---

## Onda 2 — Cliente Gemini e convivência de dois `ChatModel`

- [ ] **2.1** `pom.xml`: adicionar `spring-ai-starter-model-google-genai` (versão pelo BOM
      `2.0.0-M2` já existente).
- [ ] **2.2** ⚠️ **Verificar primeiro, codificar depois:** com dois starters no classpath, a
      auto-config de `ChatClient.Builder` do Spring AI pode deixar de resolver (é condicional a
      candidato único de `ChatModel`). Subir a app e confirmar. Se quebrar, `VisionAiConfig` passa
      a expor **dois** `ChatClient` qualificados, construídos de `ChatModel` específicos
      (`OllamaChatModel` / `GoogleGenAiChatModel`), cada um `@ConditionalOnBean` do seu modelo —
      e cada `VisionModelClient` injeta o seu por `@Qualifier`. **Não seguir para 2.3 antes de a
      app subir.**
- [ ] **2.3** Criar `GeminiVisionClient` (`@Component`, `@Order(10)`,
      `@ConditionalOnProperty(name = "spring.ai.google.genai.api-key")`). `providerId()` →
      `"gemini"`. Mesma assinatura, mesmo prompt vindo do extrator.
- [ ] **2.4** Properties em `application.properties`, no mesmo estilo comentado do bloco Ollama:
      ```
      spring.ai.google.genai.api-key=${GEMINI_API_KEY:}
      spring.ai.google.genai.chat.options.model=${GEMINI_MODEL:<flash-do-free-tier>}
      spring.ai.google.genai.chat.options.temperature=0.1
      ```
      **Confirmar o ID exato do modelo no Google AI Studio no momento da implementação**
      (spec §4) — e **não** definir `project-id`/`location`, que forçam o modo Vertex AI e fazem
      a chave do AI Studio ser rejeitada com 400.
- [ ] **2.5** `application-prod.properties`: `GEMINI_API_KEY` sem default (obrigatória via env),
      mesmo padrão do `OLLAMA_BASE_URL`. Documentar a env var onde as demais estão documentadas.
- [ ] **2.6** Teste: com a property de chave ausente, o contexto sobe e a lista de
      `VisionModelClient` tem só o Ollama (prova o `@ConditionalOnProperty` — é o que garante que
      um clone novo do repo funciona).

**Gate:** app sobe com e sem `GEMINI_API_KEY`; suíte verde nos dois modos.
**Commit:** `feat(import): adiciona cliente de visão Gemini como provider opcional`

---

## Onda 3 — Proveniência estruturada (V28 + seed V29)

O schema vem **antes** da lógica de fallback de propósito: assim a Onda 4 já grava no lugar
definitivo, em vez de produzir a informação e voltar para persistir depois.

- [ ] **3.1** `V28__import_batch_provenance.sql` (aditiva): `extractor_provider VARCHAR(30)`,
      `extractor_model VARCHAR(100)`, `fallback_from VARCHAR(30)`, `fallback_reason VARCHAR(200)`,
      `extraction_latency_ms INTEGER` — todas nullable. Comentário SQL em cada coluna, no padrão
      do V23/V26.
- [ ] **3.2** Backfill no mesmo V28: `extractor_provider` derivado do `extractor_used` existente
      (`vision_ollama_%` → `ollama`, `csv_%` → `csv`, `ofx_%` → `ofx`). `fallback_*` e latência
      ficam NULL nos legados — não foram medidos, e fingir o contrário seria pior que o NULL.
- [ ] **3.3** `ImportBatch`: campos novos com `@Column`. **Não** expor no
      `ImportBatchResponseDTO` (spec §5.1.1) — nenhuma mudança em `openapi.yaml`.
- [ ] **3.4** `NormalizedBatchDTO` ganha os campos de proveniência que o extrator conhece
      (`provider`, `model`, `latencyMs`, e o par de fallback). O `ImportService` persiste o que
      recebe — **quem mede é o extrator**, quem grava é o service: a fronteira atual (extrator não
      toca banco) não muda.
- [ ] **3.5** Seed `V29__seed_dev_import_provenance.sql`: `UPDATE` nos batches do V24 e V27
      populando `extractor_provider`/`extractor_model` coerentes com o `extractor_used` que já
      têm, e um deles com `fallback_from`+`fallback_reason` preenchidos — dado real para a query
      de estatística da spec §6 não voltar vazia no ambiente de dev.
- [ ] **3.6** `database-schema.md`: linhas V28 e V29 na tabela de versões, com o *porquê*
      (padrão das entradas existentes, que explicam a motivação e não só o DDL).

**Gate:** `docker compose up` + app sobe (Flyway aplica V28/V29 sem erro); a query de §6 da spec
roda e devolve linha para os batches seed.
**Commit:** `feat(import): grava proveniência estruturada do extrator no batch`

---

## Onda 4 — Política de fallback, classificação de falha e observabilidade

O coração da feature. Ver tabela da spec §3.2.

- [ ] **4.1** Criar `VisionProviderUnavailableException` (falha de disponibilidade) — distinta de
      `ExtractionException` (falha de conteúdo/final). Cada `VisionModelClient` classifica sua
      própria exceção nativa e lança a certa; a classificação vive **no cliente**, não no extrator
      (só o cliente sabe o que um erro do seu SDK significa).
- [ ] **4.2** `VisionExtractor.extract`: iterar a lista; capturar **só**
      `VisionProviderUnavailableException` para tentar o próximo; qualquer outra exceção propaga
      como hoje. Esgotada a lista, `ExtractionException` com o motivo do **último** erro —
      mensagem redigida por nós, jamais o texto cru do provider.
- [ ] **4.3** Logs: INFO por extração bem-sucedida com `provider`, `model`, `latencyMs`,
      `overallConfidence` (formato já existente, ganha o `provider`); WARN em cada fallback
      informando de quem para quem e por quê; **ERROR** em 401/403. Nenhum log inclui a chave.
- [ ] **4.4** Testes do `VisionExtractor` com dois fakes de `VisionModelClient`:
      - primário lança `VisionProviderUnavailableException` → resultado vem do secundário e
        `extractorUsed` é o do secundário;
      - primário devolve DTO com `multipleTransactionsDetected=true` → `ExtractionException` e o
        secundário **nunca é chamado** (`verify(never())` — é a asserção que trava a regra central);
      - primário devolve `amount` inválido → idem, sem fallback;
      - todos indisponíveis → `ExtractionException` carregando o motivo do último;
      - lista vazia → falha clara, não `NullPointerException`.
- [ ] **4.5** Gravar a proveniência do fallback nas colunas da Onda 3: `fallback_from` =
      provider que falhou, `fallback_reason` = classificação + detalhe curto (nunca o texto cru do
      provider, nunca a chave), `extraction_latency_ms` = tempo da chamada que venceu.
- [ ] **4.6** Teste de classificação em `GeminiVisionClientTest`: 429 e 5xx →
      `VisionProviderUnavailableException`; e um teste garantindo que a mensagem propagada não
      contém a API key.

**Gate:** suíte verde; os cenários de §6 da spec cobertos por teste — inclusive um que verifica
que o batch resultante de um fallback tem `fallback_from` preenchido — exceto a medição de
latência real, que é da Onda 5.
**Commit:** `feat(import): adiciona fallback de provider de visão por falha de disponibilidade`

---

## Onda 5 — Validação real, documentação e encerramento

- [ ] **5.1** Validação manual com `GEMINI_API_KEY` real: 3-5 comprovantes reais (incluindo um
      difícil — foto torta/baixa luz) extraídos pelos **dois** providers no mesmo arquivo.
      Registrar precisão percebida e latência lado a lado no comentário da issue. É a mitigação da
      assimetria de prompt da spec §5.3 — e o insumo mais concreto que existe hoje para justificar
      (ou dispensar) o #191.
- [ ] **5.2** Validação do fallback de verdade: derrubar o Gemini (chave inválida temporária) e
      confirmar batch concluído com `extractorUsed=vision_ollama_*` + ERROR no log.
- [ ] **5.3** `summary.md`: atualizar o parágrafo "Extrator (Fase 1)" — hoje afirma que o provider
      é o Ollama e que trocar é trocar starter+properties. Passa a descrever a cadeia
      Gemini→Ollama, a política de fallback e a proveniência em `extractorUsed`.
- [ ] **5.4** `tech.md`: registrar o starter novo. `docs/roadmap-extracao-e-conciliacao.md`:
      nota de que a camada de cobertura universal passou a ter provider gerenciado primário —
      relevante para o dimensionamento de cota da Fase 3 (spec §4).
- [ ] **5.5** Nota de privacidade (spec §5.4) registrada onde o dev a reencontre: a imagem do
      comprovante passa a sair do homelab. Decisão consciente, não efeito colateral.
- [ ] **5.6** Suíte completa verde (`./scripts/test-summary.sh`), branch indicada, merge em
      `develop` **sugerido** ao dev (nunca executado por conta própria), worktree removida com
      `./scripts/clean-worktrees.sh` a partir da raiz estável.

**Commit:** `docs: atualiza referências pós-integração Gemini na extração por visão`

---

## Riscos e o que fazer

| Risco | Sinal | Resposta |
|---|---|---|
| Auto-config do `ChatClient.Builder` quebra com dois `ChatModel` | App não sobe na Onda 2 | Beans qualificados explícitos (passo 2.2) — já previsto, não é surpresa |
| Spring AI 2.0.0-M2 é **milestone**: o starter do Gemini pode ter API instável ou bug | Compilação/runtime estranhos | Fixar o comportamento em teste; se o starter estiver inviável, **parar e reportar** — não contornar com chamada HTTP crua ao Gemini (perderia o `.entity()` tipado, que é metade do valor do Spring AI aqui) |
| ID do modelo Flash mudou/foi aposentado | 404 do provider | É exatamente por isso que o ID é env var (passo 2.4) — trocar a env, não o código |
| Gemini interpreta o prompt de forma diferente e piora um campo | Detectado em 5.1 | Registrar na issue e no #191. **Não** ajustar o prompt no impulso: sem dataset de avaliação, "melhorar" o prompt para um caso é apostar contra os outros |
| Cota free estourando no uso normal | 429 frequente no log | Reavaliar §7 da spec (contagem própria/circuit breaker) — não antes |
