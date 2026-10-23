# Spec: Métricas de uso de LLM na extração (tokens, custo, latência decomposta)

**Data:** 2026-10-15
**Status:** proposto (aguardando aprovação do dev)
**Issue:** a criar — referencia ADR-007 (governança de provider) e follow-up #240
(installação correta prospectiva — feature distinta).
**Épicos relacionados:** #176 (Fase 3 — extração multi-mídia), ADR-007
(`docs/adr/ADR-007-modelo-visao-ollama-homelab.md`).
**Relacionado:** V28 (proveniência `import_batches`); #241 (endurecimento do extrator local,
commit `cd92980`); spec `2026-10-08-extracao-visao-local-first-design.md` (gate de medição da
ADR-007); `2026-10-09-parcelas-somente-faturas-abertas-design.md` (formato de referência).
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema

## 1. Contexto e escopo

O sub-funil de visão (`VisionExtractor`) hoje persiste em `import_batches` (V28) apenas
**latência** (`extraction_latency_ms`) e proveniência estruturada (`extractor_provider`,
`extractor_model`, `fallback_from`, `fallback_reason`). O `VisionExtractor.java:390` registra
literalmente a decisão consciente:

> "Tokens não são expostos pelo caminho `.entity()`; latência é a métrica que importa aqui."

Essa decisão foi tomada quando o Ollama era **único** provider (Fase 1) e o custo era
uniformemente zero. Hoje o funil é Gemini primário (free tier) + Ollama fallback, e a
comparação de **custo real entre providers** — explicitamente requerida pela ADR-007 — depende
de **tokens por chamada**, não só de tempo. Sem eles, não dá para:

1. Comparar Gemini free tier e Ollama local em custo (R$/$) por tipo de documento.
2. Detectar **quando** o prompt virou pesado (imagem base64 → prompt com N KB de mídia).
3. Sustentar o **gate (a) da ADR-007** (corpus real maior) com métrica defensável por chamada.
4. Diagnosticar degradações de modelo ao longo do tempo (`extraction_latency_ms` sozinho
   mistura upload, serialização, network e inferência — vide último atendimento: 9,7s vs 42s
   no mesmo modelo).

**Escopo afetado:** somente o caminho `VisionExtractor` (imagem/PDF escaneado). CSV/OFX/PDF de
texto (`PdfTextExtractor` + templates) **não** passam por LLM — não há token a coletar.
`PdfBankTemplate` recebe a string parseada, não chama o modelo.

**Fora de escopo (ver §10):** instrumentação do conteúdo (recibos, comprovantes) para
treinamento ou auditoria; cálculo de custo em R$ (depende de tabela de preços dinâmica);
integração síncrona com a tela do usuário.

## 2. Comportamento alvo

**Por chamada LLM (`VisionExtractor.extract*`):

| Dado | Origem | Persistência |
|---|---|---|
| `input_tokens` | `ChatResponse.getMetadata().getUsage().getPromptTokens()` | `import_batches.input_tokens` |
| `output_tokens` | `getCompletionTokens()` | `import_batches.output_tokens` |
| `total_tokens` | `getTotalTokens()` | `import_batches.total_tokens` |
| `cost_usd` | tabela interna (por `extractor_provider` × `extractor_model`) — NULL enquanto não houver | `import_batches.cost_usd` |
| `latency_breakdown_ms` (opcional) | segmentação futura — upload/preprocess/call/parse | NÃO nesta entrega (só schema conceitual) |

Todos os campos novos são **nullable**: provider antigo não reporta tokens; clientes que
desabilitam o caminho Ollama (futuro) continuam funcionando.

**Em Micrometer** (bônus, exposto em `/actuator/metrics/`):
- `llm.calls` — counter, tag `provider`, `model`, `outcome` (`success`/`unavailable`/`content_error`)
- `llm.input.tokens` — counter, mesma tag
- `llm.output.tokens` — counter, mesma tag
- `llm.latency` — timer (p50/p95 derivável), tag `provider`, `model`

Micrometer é independente do DB — entrega imediata sem migration. **A mesma observação
alimenta DB e Micrometer** via um único ponto de captura (`LlmExtractionObserver`),
evitando divergência entre as duas fontes.

## 3. Decisões

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| **D1** | Porta `VisionModelClient` | Muda de `<T> extract(...)` para `<T> LlmExtractionResult<T> extract(...)`. O `LlmExtractionResult` carrega entity + `Usage` + flags de erro. | Manter `<T>` e capturar tokens via side-channel (thread-local / Micrometer global). Perde o tipo de retorno explícito e vaza o ciclo de vida do request para estado compartilhado. |
| **D2** | Origem do `Usage` | Spring AI 2 emite spans com semconv `gen_ai.usage.*` automaticamente (não-streaming). Os clients (`GeminiVisionClient`, `OllamaVisionClient`) passam a devolver o `ChatResponse` cru, filtram o entity, e propagam o `Usage` no `LlmExtractionResult`. | Tentar extrair tokens do `String` de resposta. Frágil, depende do formato de cada provider. |
| **D3** | Persistência | DB-first (`import_batches` + migration V39, todos nullable). DB é **fonte de verdade de billing/auditoria**. | Micrometer-only. Análise histórica exige Prometheus/Grafana; inconsistente com proveniência V28 (que é DB). |
| **D4** | Micrometer | Bônus no mesmo PR. Mesmo observador (que produz `LlmExtractionResult`) emite gauges em paralelo via `MeterRegistry`. | Adiar Micrometer para entrega futura. Custo marginal agora é trivial (já temos o `Usage` em mãos). |
| **D5** | Compatibilidade LangFuse | **Não adotamos LangFuse agora.** A captura usa **Spring AI Observation** (Micrometer Observation + bridge OTel/OTLP), que é o caminho oficial LangFuse recomenda. Trocar backend é mudar 2 env vars (`OTEL_EXPORTER_OTLP_ENDPOINT` + Basic auth). Nada do nosso código precisa mudar quando LangFuse for adotado. | Adotar LangFuse Cloud agora. Bloqueio: custo fixo mensal + retenção 30d no Hobby (perdemos histórico); residência de dado (EU/US/JP); opt-in de conteúdo sensível é decisão que ainda não tomamos; SDK Java "oficial" de tracing não existe — o caminho via OTel é equivalente ao que já faremos. |
| **D6** | Privacidade | Conteúdo (prompt/completion) **nunca** é logado nem enviado a backend de tracing. `spring.ai.chat.observations.log-prompt=false` e `log-completion=false` (default). Tokens vão (contagem, não conteúdo). | Logar conteúdo "para auditoria". Risco: PII de cliente (recibos com nome/CPF/CNPJ/valor) sai do cluster, viola multi-tenant isolation. |
| **D7** | Tenant isolation em observabilidade | Todo span gerado pelo caminho de visão recebe `tenant_id` como atributo OTel (via `ObservationFilter`). LangFuse ou qualquer backend OTLP recebe o atributo; queries futuras filtram por tenant. | Tag global sem filtro — vaza o `tenant_id` para qualquer agregador e expõe o particionamento do banco. |

## 4. Schema — migration V39

Migration aditiva, todas as colunas **nullable**, sem backfill (não há como recalcular tokens de
chamadas passadas; fingir seria pior que `NULL`, alinhado à V28 §"extraction_latency_ms"):

```sql
-- V39 — métricas de uso de LLM no batch (tokens + custo)
ALTER TABLE import_batches ADD COLUMN input_tokens  BIGINT;
ALTER TABLE import_batches ADD COLUMN output_tokens BIGINT;
ALTER TABLE import_batches ADD COLUMN total_tokens  BIGINT;
ALTER TABLE import_batches ADD COLUMN cost_usd      NUMERIC(10,6);

COMMENT ON COLUMN import_batches.input_tokens  IS
    'Tokens de entrada consumidos pelo provider vencedor (prompt + imagem como mídia). NULL quando indisponível (provider não reporta; chamada < 2026-10-15).';
COMMENT ON COLUMN import_batches.output_tokens IS
    'Tokens de saída consumidos pelo provider vencedor (completion / JSON estruturado). NULL nas mesmas condições.';
COMMENT ON COLUMN import_batches.total_tokens  IS
    'Soma input + output. Pode ser fornecido pelo provider ou computado na aplicação se faltar.';
COMMENT ON COLUMN import_batches.cost_usd IS
    'Custo estimado em USD da chamada vencedora. NULL enquanto não houver tabela de preços mantida no app. Não usar como fonte de cobrança sem isso.';
```

Sem nova tabela de preços nesta entrega (D3). Se for útil manter preços internos, abrir
`pricing_reference (provider, model, effective_from, input_usd_per_1k, output_usd_per_1k)` como
issue separada — não bloqueia a medição.

## 5. Backend — desenho

### 5.1 Novo record `LlmExtractionResult<T>`

```java
public record LlmExtractionResult<T>(
        T entity,
        Usage usage,                       // org.springframework.ai.chat.metadata.Usage
        String providerId,
        String modelId,
        long latencyMs,
        boolean unavailable) {             // espelha VisionProviderUnavailableException
    public Integer inputTokens()  { return usage != null ? usage.getPromptTokens()     : null; }
    public Integer outputTokens() { return usage != null ? usage.getCompletionTokens() : null; }
    public Integer totalTokens()  { return usage != null ? usage.getTotalTokens()      : null; }
}
```

A flag `unavailable` evita que o `VisionExtractor` (orquestrador) tenha que distinguir via
try/catch entre "provider falhou por disponibilidade" e "falhou por outro motivo" só para
emitir a métrica certa.

### 5.2 `VisionModelClient` (porta)

```java
public interface VisionModelClient {
    <T> LlmExtractionResult<T> extract(String prompt, MimeType mimeType,
                                        Resource imageResource,
                                        Class<T> responseType,
                                        Integer maxOutputTokens);
    String providerId();
    String modelId();
}
```

Implementações (`GeminiVisionClient`, `OllamaVisionClient`): passam de `.entity(Class)` para
`.chatResponse()`, filtram `getResult().getOutput().getContent()` em formato JSON-DO
correspondente ao DTO, e capturam `getMetadata().getUsage()` antes de devolver. `extract()`
do caminho de **extrato** (`LlmStatementExtractionDTO`) usa o mesmo wrapper — uma única
implementação por provider.

### 5.3 `VisionExtractor` (orquestrador)

- Recebe `LlmExtractionResult` no lugar do DTO cru.
- Extrai tokens e propaga para `NormalizedBatchDTO` (campos opcionais, nullable).
- `ImportBatch` ganha os 4 campos novos via builder.
- O ponto único que mede `System.nanoTime()` para `extraction_latency_ms` permanece o mesmo
  (já existente, linhas 330–344 do `VisionExtractor.java`).
- O contador Micrometer é emitido **uma vez por chamada vencedora** (não por tentativa de
  fallback — usar label `outcome=success|unavailable|content_error`).

### 5.4 `NormalizedBatchDTO` + `ImportBatchResponseDTO`

Ambos ganham campos opcionais:

```java
Integer inputTokens, outputTokens, totalTokens, costUsd;
```

Default `null` para clientes existentes (proveniência determinística CSV/OFX/PDF texto não
preenche).

## 6. Micrometer

Adicionar dependência: `spring-boot-starter-actuator` (já presente para `/actuator/health`;
verificar se `MeterRegistry` está disponível). Sem `micrometer-registry-prometheus` nesta
entrega (não temos Prometheus deployado; se quisermos, abrir issue separada).

Config (`application.properties`):

```
management.endpoints.web.exposure.include=health,metrics
management.observations.annotations.enabled=true
# NÃO ligar log-prompt/log-completion (D6)
```

Counter/timer registrados em um `@Component LlmExtractionObserver` injetado no `VisionExtractor`:

```java
public void record(LlmExtractionResult<?> result, String outcome) {
    Counter.builder("llm.calls")
           .tag("provider", result.providerId())
           .tag("model", result.modelId())
           .tag("outcome", outcome)
           .register(registry).increment();
    if (result.inputTokens() != null) {
        Counter.builder("llm.input.tokens")...register(registry).increment(result.inputTokens());
    }
    // ... idem output_tokens
    Timer.builder("llm.latency")
         .tag("provider", result.providerId())
         .tag("model", result.modelId())
         .register(registry).record(Duration.ofMillis(result.latencyMs()));
}
```

## 7. Caminho futuro — LangFuse Cloud (forward compatibility)

**Não adotar agora.** Esta seção documenta por que as escolhas D5/D7 facilitam a adoção
quando for hora. Decisão fica para issue/ADR futura.

### 7.1 O que é o LangFuse Cloud

Plataforma SaaS de observabilidade para LLM. Captura traces, tokens, latência, custo
estimado, prompt/completion (opt-in). Modelo de cobrança: **por trace + observation** (não
por token). 50k units/mês grátis (Hobby), retenção 30d; Core US$29/mês com 100k units,
retenção 90d. Tem região EU (Frankfurt) — adequado para dado BR sensível.

### 7.2 Por que NÃO adotar agora

1. **Custo fixo mensal para volume ainda baixo.** Últimos 14 dias em dev: 5 batches. Em prod:
   1 batch/mês. Hobby tier cobre, mas retenção de 30d não atende auditoria financeira.
2. **Conteúdo opt-in é decisão que ainda não tomamos.** LangFuse permite logar
   prompt/completion (opt-in). Para nosso caso (recibos com nome/CPF/CNPJ/valor),
   recomendação é não ligar — mas isso é decisão de governança de dados, não técnica.
3. **SDK Java "oficial" não existe para tracing.** O caminho é OpenTelemetry OTLP.
   Funciona, mas é o mesmo caminho que usaríamos para qualquer backend (Datadog, Honeycomb,
   Grafana Tempo, self-host LangFuse). Não há lock-in técnico.
4. **Self-host viável mas operacional.** Docker Compose com 5 serviços (web, worker,
   Postgres, ClickHouse 25.12, Redis, MinIO). Em homelab roda, mas ClickHouse consome
   RAM/disco — é outro serviço para manter.

### 7.3 O que esta entrega já prepara para LangFuse

- `LlmExtractionResult` expõe o `Usage` (o que LangFuse precisa para `gen_ai.usage.*`).
- Observabilidade via **Spring AI Observation** (semelhante ao Micrometer) + bridge OTel =
  caminho oficial recomendado pela LangFuse.
- Atributos `tenant_id`, `batch_id` injetados via `ObservationFilter` (D7) — LangFuse lê
  esses atributos como metadata sem código adicional.
- DB de tokens é **fonte de verdade de billing**, independente do que LangFuse calcula — bom:
  LangFuse calcula custo via tabela interna (lookup por `gen_ai.response.model`); nossa
  tabela interna (quando existir) tem prioridade.

### 7.4 Custo de adotar LangFuse quando for hora

- **~1 dia de implementação**: adicionar `spring-boot-starter-opentelemetry`, configurar
  `OTEL_EXPORTER_OTLP_ENDPOINT` + headers Basic auth; plugar um `ObservationFilter` para
  `tenant_id`.
- **Zero código de domínio alterado** — `LlmExtractionResult`, `VisionModelClient`,
  `NormalizedBatchDTO`, `ImportBatch` permanecem.
- **Risco de nuance**: streaming. Não usamos (caminho `.entity()`), então nada a ajustar;
  mas vale um PoC curto com Gemini e Ollama para confirmar que spans carregam como esperado.

### 7.5 Trigger para reabrir essa discussão

Qualquer um dos três: (a) volume mensal > ~5k chamadas; (b) incidente de produção que
exija análise trace-by-trace; (c) auditoria externa exigir retenção > 30d de histórico de
LLM. Abertura: issue + ADR-008 (política de observabilidade de LLM).

## 8. Riscos e ressalvas

| Risco | Mitigação |
|---|---|
| Spring AI 2.0.1 vs Google GenAI SDK: `getUsage()` pode vir `null` em chamadas que falham | Gravar tokens como nullable; o caminho de erro já é logado (`VisionExtractor.log`) |
| Ollama expõe tokens via API HTTP, mas binding Spring AI 2 pode não mapear para todos os providers — **verificar** na PoC que `getPromptTokens()`/`getCompletionTokens()` retornam não-nulo para Ollama | Smoke-test local com `qwen3-vl:8b-instruct` antes de subir a migration |
| `cost_usd` NULL em todas as linhas atuais cria falsa impressão de "gratuito" | Documentar no comentário da coluna e em `domain.md`; abrir issue de follow-up com tabela de preços |
| Performance: emitir counter Micrometer por chamada tem custo; Ollama local processa 1 chamada por vez hoje, então negligível | Medir antes/depois com o mesmo upload sintético |
| Privacidade do `tenant_id` em agregadores | `ObservationFilter` injeta o atributo; sem PII, só o UUID do tenant. Mesmo nível que já existe nos logs (MDC `tenantId`) |
| LangFuse futuro: residência de dado se LangFuse Cloud for adotado | Preferir região EU (Frankfurt) ou self-host; política documentada em ADR futura |

## 9. Teste

**Unit (lógica pura):**
- `LlmExtractionResultTest`: helpers `inputTokens()/outputTokens()/totalTokens()` retornam
  `null` quando `usage == null`; propagam corretamente quando presente.
- `VisionExtractorTest` (atual): ajustar fixtures para usar `LlmExtractionResult`. Casos:
  - chamada com `Usage` válido → tokens propagados para `NormalizedBatchDTO`
  - chamada com `Usage == null` (Gemini quota error) → tokens `null`, batch não quebra
  - fallback por `unavailable` → counter `outcome=unavailable` para o provider que falhou;
    counter `outcome=success` para o vencedor
  - content error (não cai em fallback) → counter `outcome=content_error` uma vez
- `ImportBatchMapperTest` ou equivalente: DTO → entity propaga os 4 campos nullable.

**Integração (`@SpringBootTest` contra Postgres local):**
- Subir uma imagem real no caminho de visão, confirmar:
  - `import_batches.input_tokens > 0`, `output_tokens > 0`, `total_tokens == input + output`
  - `actuator/metrics/llm.calls` retorna counter com a tag certa
- Imagen de Gemini primário: tokens aparecem mesmo no caminho de fallback (só do vencedor).

**Frontend:** sem mudança nesta entrega. `ImportBatchResponseDTO` ganha campos; UI pode
exibir em detalhe do batch numa entrega futura (não é blocker).

## 10. Fora de escopo

- Adotar LangFuse Cloud (decisão futura, §7).
- Adotar Prometheus/Grafana para visualizar métricas Micrometer (issue separada; enquanto
  isso, `curl /actuator/metrics/llm.calls` no debug).
- Tabela interna de preços por provider/model (issue separada — sem ela, `cost_usd` fica
  sempre `NULL`).
- Instrumentar **conteúdo** (prompt/completion) para auditoria ou replay — risco de PII.
- Cobrir `PdfTextExtractor`/templates determinísticos (não usam LLM).
- Logar tokens em log estruturado (já temos `extraction_latency_ms` no log; tokens são
  métrica, não evento).
- Retrofit de batches antigos com tokens estimados (impossível sem rerodar extração).

## 11. Critério de conclusão

- [ ] Migration V39 commitada; suíte Flyway verde em dev/hmg/prod.
- [ ] `LlmExtractionResult<T>` introduzido; `VisionModelClient.extract(...)` muda de
      assinatura; ambas implementações (`GeminiVisionClient`, `OllamaVisionClient`)
      adaptadas.
- [ ] `NormalizedBatchDTO`, `ImportBatchResponseDTO`, `ImportBatch` ganham 4 campos
      nullable; testes verdes.
- [ ] `LlmExtractionObserver` emite counters/timer em Micrometer;
      `/actuator/metrics/llm.*` retorna valores após uma extração real.
- [ ] `tenant_id` propagado como atributo OTel via `ObservationFilter` (ponto único).
- [ ] `application.properties`: `management.endpoints.web.exposure.include` inclui `metrics`;
      `log-prompt=false`, `log-completion=false` (D6).
- [ ] Subir 1 imagem real em dev, confirmar tokens no DB e counters no `/actuator/metrics`.
- [ ] Atualizar `docs/superpowers/specs/...` (este doc); `summary.md` (campos novos no
      endpoint); `database-schema.md` (V39); `domain.md` (campo opcional em proveniência).
- [ ] Suíte completa verde (`./scripts/test-summary.sh backend`).
- [ ] SemVer bump registrado (PATCH; sem mudança de contrato).

## 12. Impacto SemVer

**PATCH** — sem mudança de contrato em `api-spec/openapi.yaml`. `ImportBatchResponseDTO`
ganha 4 campos opcionais (quebrar-clientes existentes é impossível — campos nullable são
aditivos). DB: V39 aditiva, todas as colunas nullable. Não há mudança comportamental
observável do import.

---

## Apêndice A — Esqueleto da classe `LlmExtractionObserver`

```java
@Component
public class LlmExtractionObserver {
    private final MeterRegistry registry;
    public LlmExtractionObserver(MeterRegistry r) { this.registry = r; }

    public void recordSuccess(LlmExtractionResult<?> r) { record(r, "success"); }
    public void recordUnavailable(LlmExtractionResult<?> r) { record(r, "unavailable"); }
    public void recordContentError(String provider, String model) {
        Counter.builder("llm.calls")
               .tag("provider", provider).tag("model", model)
               .tag("outcome", "content_error")
               .register(registry).increment();
    }

    private void record(LlmExtractionResult<?> r, String outcome) {
        Counter.builder("llm.calls")
               .tag("provider", r.providerId()).tag("model", r.modelId())
               .tag("outcome", outcome)
               .register(registry).increment();
        if (r.inputTokens()  != null) tokensCounter("llm.input.tokens",  r).increment(r.inputTokens());
        if (r.outputTokens() != null) tokensCounter("llm.output.tokens", r).increment(r.outputTokens());
        Timer.builder("llm.latency")
             .tag("provider", r.providerId()).tag("model", r.modelId())
             .register(registry).record(Duration.ofMillis(r.latencyMs()));
    }

    private Counter.Builder tokensCounter(String name, LlmExtractionResult<?> r) {
        return Counter.builder(name)
               .tag("provider", r.providerId()).tag("model", r.modelId());
    }
}
```

## Apêndice B — Esqueleto da alteração em `GeminiVisionClient.extract`

```java
@Override
public <T> LlmExtractionResult<T> extract(
        String prompt, MimeType mimeType, Resource imageResource,
        Class<T> responseType, Integer maxOutputTokens) {
    long start = System.nanoTime();
    try {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(u -> u.text(prompt).media(mimeType, imageResource));
        if (maxOutputTokens != null) {
            spec = spec.options(GoogleGenAiChatOptions.builder()
                    .maxOutputTokens(maxOutputTokens)
                    .thinkingLevel(GoogleGenAiThinkingLevel.LOW));
        }
        ChatResponse response = spec.call().chatResponse();
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        T entity = parseEntity(response, responseType);    // extrai o JSON do conteúdo
        return new LlmExtractionResult<>(entity, response.getMetadata().getUsage(),
                providerId(), modelId(), latencyMs, false);
    } catch (Exception e) {
        String reasonCode = classifyAvailability(e);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        if (reasonCode != null) {
            throw new VisionProviderUnavailableException(reasonCode,
                    "Gemini indisponível (" + VisionProviderErrorClassifier.friendlyReason(reasonCode) + ").",
                    e);
        }
        throw new ExtractionException("Falha ao extrair dados da imagem via modelo de visão (Gemini).", e);
    }
}
```

`OllamaVisionClient` segue o mesmo padrão com `OllamaChatOptions`/`numPredict` em vez de
`GoogleGenAiChatOptions`/`thinkingLevel`.