# ADR-007: Política de provider de visão — local-first (Ollama) para extração de comprovantes

## Status

Proposto — 2026-10-08 (reescrita; a versão anterior, de 2026-10-05, focava apenas na troca do
modelo do Ollama).

Decisão registrada para aplicação **faseada**: a política de provider é o alvo; a escolha do
modelo local e a inversão do funil são **gateadas pelo experimento de acurácia** descrito na
spec `2026-10-08-extracao-visao-local-first-design.md`. Nenhuma linha de código Java muda para o
preparo de ambiente (é configuração), mas a inversão do funil é mudança de comportamento.

Depende do pipeline de importação existente (porta `VisionModelClient`, `VisionExtractor`,
funil Gemini→Ollama) e do homelab descrito no ADR-004.

## Contexto

A extração a partir de **imagem de comprovante** (Pix, recibo, nota, fatura de compra) e de
**PDF escaneado** usa um LLM de visão atrás da porta `VisionModelClient`, com dois providers em
funil:

- **Gemini** (`gemini-2.5-flash`) — primário, `@Order(10)`.
- **Ollama** (homelab próprio) — fallback, `@Order(20)`.

O fallback dispara **apenas por indisponibilidade** (429/5xx/timeout/401/403/400 —
`VisionProviderUnavailableException`, classificada por `VisionProviderErrorClassifier`), nunca
por qualidade de leitura. A ordem dos clients define que, no caminho normal, o documento é
enviado ao Gemini.

**Escopo real:** só imagem/foto e PDF escaneado passam por esse LLM. PDF de texto
(`PdfTextExtractor` com templates de banco), OFX e CSV **não** usam LLM — não há dependência
externa a cortar nesses fluxos.

### Propósito desta decisão

O objetivo declarado é **cortar custo e dependência de LLM externo**, mantendo a performance de
extração. Dois esclarecimentos que reposicionam a decisão:

1. **Não há economia direta atual a obter.** O custo por chamada já é zero (Gemini free tier +
   Ollama local). O ganho real é **dependência/privacidade** — dado financeiro de cliente
   multi-tenant enviado ao Gemini free tier, que tipicamente usa o conteúdo para treino — e
   evitar custo futuro ao estourar o free tier. Energia/amortização/operação do homelab não estão
   quantificadas.
2. **Trocar o modelo do Ollama não corta a dependência.** Enquanto o Gemini for primário, a
   extração normal continua passando por ele. O que corta dependência é a **política de
   provider** (inverter o funil ou desativar o Gemini), não a qualidade do fallback.

### Hardware (verificado em 2026-10-08)

- Homelab `deathstar-server`: k3s single-node, **2× GTX 1080 Ti** (Pascal, compute 6.1,
  11.264 MiB cada, ~22 GB), i7-7700K (4c/8t), **30 GiB RAM**, Ollama 0.24.0 **fora do k8s**
  (systemd), `OLLAMA_MODELS=/mnt/ollama-data/models` (disco a **93%**, ~18 GB livres).
- **Nenhuma GPU NVIDIA é usada pelo k8s** (sem device plugin/GPU operator/`nvidia.com/gpu`); o
  Jellyfin transcodifica na **iGPU Intel** via `/dev/dri`. As duas 1080 Ti ficam livres para o
  Ollama; hoje o systemd usa só `CUDA_VISIBLE_DEVICES=1`.
- **Pascal é teto de qualidade, não só de espaço:** sem tensor cores, FP16 roda fração do FP32
  (quantizar economiza VRAM, não acelera); flash attention funciona sem kernel tensor-core;
  CUDA 13 removeu Pascal e o Ollama contorna com runner `cuda_v12` (validar que o runner ativo é
  GPU — `ollama ps` não pode mostrar 100% CPU).
- **Multi-GPU no Ollama é layer-split sequencial** (sem tensor-parallel): a 2ª GPU dá capacidade
  e paralelismo, não menor latência de uma requisição.

### Disponibilidade do provider local

Hoje o modelo Ollama é `llama3.2-vision` (11B). Config:

```properties
# backend/src/main/resources/application.properties:45
# backend/src/main/resources/application-prod.properties:43
spring.ai.ollama.chat.options.model=${OLLAMA_MODEL:llama3.2-vision}
```

Em produção o valor efetivo vem da env `OLLAMA_MODEL` no ConfigMap `fintech-config` dos overlays
(`homelab-k8s`), hoje `llama3.2-vision`; o default no properties é a rede de segurança.

Dois problemas motivaram revisar o modelo local:

1. **`llama3.2-vision` só suporta imagem+texto em inglês** (declarado na página do modelo no
   Ollama). O domínio é comprovante PT-BR, com prompt em PT-BR (`VisionExtractor.PROMPT`) — uso
   fora da língua suportada para visão.
2. **`llama3.2-vision` é generalista de raciocínio de cena, não transcritor de dígitos.** A
   tarefa é ler `R$ 1.234,56` corretamente — OCR de número. Um dígito trocado passa pelos
   guarda-corpos do `ImportService` (que só pegam valor zero/data implausível) e vira transação
   após o commit.

## Decisão

**(1) Política de provider: local-first.** O Ollama passa a ser o provider preferencial do
sub-funil de visão. O Gemini permanece apenas como **fallback de disponibilidade em deploys onde
o processamento externo esteja explicitamente autorizado**; sem essa autorização, o Gemini é
desativado (sem `GEMINI_API_KEY`, o bean é condicional e só o Ollama atende). A inversão de
`@Order`/desativação só se aplica **depois** do experimento de acurácia (abaixo).

**(2) Escolha do modelo local: decidida por medição, não a priori.** Candidatos no Ollama, para
o hardware atual (Q4, contexto ~12K, KV `q4_0`):

| Modelo (tag real) | Tamanho | 1×11 GB | 2×22 GB | Evidência |
|---|---|---|---|---|
| `glm-ocr` (0.9B) | 2,2 GB | ✅ | ✅ | transcritor puro, **avaliação independente forte** (OmniDocBench v1.5 ~94); PT-BR a validar |
| `qwen3-vl:8b` | 6,1 GB | ⚠️ apertado | ✅ | generalista multilingual/OCR (exige Ollama ≥ 0.12.7; temos 0.24.0) |
| `openbmb/minicpm-v4.5:8b` | 6,1 GB | ⚠️ apertado | ✅ | só claims do fabricante; sem avaliação independente equivalente |
| `llama3.2-vision` (atual) | 7,8 GB | ⚠️ | ✅ | baseline |
| `qwen3-vl:30b/32b`, `mistral-small3.1:24b` | 15–21 GB | ❌ | ❌ | inviáveis na Pascal com 12K (peso + KV ≈ 25 GB) |

A troca de modelo, quando decidida, é **só configuração** (`OLLAMA_MODEL` no properties e no
ConfigMap do k3s); a porta `VisionModelClient` abstrai o modelo e a proveniência V28 passa a
gravar o novo nome automaticamente.

**(3) Meta mensurável.** Antes de inverter/remover o Gemini: no corpus pareado (spec §4),
acerto exato de `valor` e `data` ≥ 95% e `direção` ≥ 95%, sem inferioridade frente ao baseline
atual do Ollama, dentro de orçamento de latência acordado; zero documento de cliente enviado ao
Gemini para medir.

### Aplicação (faseada)

1. **Preparo do ambiente (host):** backup do override do systemd; liberar disco em
   `/mnt/ollama-data`; `CUDA_VISIBLE_DEVICES=0,1`; manter `FLASH_ATTENTION=1`, `KV_CACHE_TYPE=q4_0`,
   `CONTEXT_LENGTH=12288`; `ollama pull` dos candidatos; validar runner em GPU.
2. **Experimento de acurácia:** backend local **Ollama-only** (sem `GEMINI_API_KEY`), corpus
   pareado com ground truth humano, mesmo prompt/schema; medir por campo, latência p95, VRAM.
3. **Decisão:** escolher o modelo e a política (inverter `@Order` / desativar Gemini / manter).
   Corrigir `requires_review` para derivar de **`extractorProvider == "ollama"`** (não de
   `fallbackFrom`) e preservar o piso no `PATCH`.
4. **Rollout:** apontar `OLLAMA_MODEL` no properties/ConfigMap; redeploy. O homelab precisa ter o
   modelo puxado antes — `spring.ai.ollama.init.pull-model-strategy=never` significa que a app
   **não** puxa sozinha.

### Reversão

Reverter é trocar a ordem dos providers / reativar o Gemini (`GEMINI_API_KEY`) — configuração,
não schema nem contrato de API. O modelo antigo pode continuar puxado sem custo relevante de
disco. Risco de reversão baixo.

## Alternativas avaliadas

1. **Manter `llama3.2-vision` — rejeitada.** Modo imagem só-inglês incompatível com PT-BR; não é
   especialista em OCR de dígitos.
2. **Manter Gemini-first e só melhorar o fallback (versão anterior desta ADR) — rejeitada.**
   Melhora a qualidade do fallback, mas **não** cumpre o propósito de cortar dependência.
3. **`glm-ocr` (0.9B) — candidato a transcritor; não decidido.** Evidência independente forte em
   OCR, minúsculo. Limite: PT-BR não avaliado e não raciocina sobre a página (não infere
   débito/crédito sozinho). Uso provável em pipeline de dois estágios só se um modelo único se
   provar insuficiente.
4. **`qwen3-vl:8b` / `minicpm-v4.5:8b` — candidatos a generalista; não decididos.** Cabem
   (apertado em 1 GPU, folgado em 2). Decididos no corpus.
5. **Desativar Gemini já, sem medir — rejeitada para o curtíssimo prazo.** É a opção mais forte
   em privacidade, mas troca dependência por qualidade não medida; o experimento precede.
6. **AWS Textract `AnalyzeExpense` como primário — adiada.** US$ 0,01/página (confirmado; varia
   por região), opt-out de treino via AWS Organizations (confirmado). Fornece score **por campo
   de 0–100** — atenção: **não é "confiança calibrada"**, exige threshold próprio. Introduz custo
   variável e egressão; reavaliar com receita/volume. A arquitetura já o plugaria como um
   `VisionModelClient` novo com `@Order` menor.

## Riscos e ressalvas

- **Benchmarks de fabricante.** OCRBench/OmniDocBench do MiniCPM-V/glm-ocr/qwen são números dos
  próprios criadores (glm-ocr tem reprodução independente; MiniCPM-V, não localizada). Trate como
  direcionais; a prova é o corpus do projeto.
- **Sem sinal de acurácia em produção.** `overallConfidence` é auto-reportado e mal-calibrado em
  modelos fracos. O `PATCH` do frontend envia todos os campos preenchidos das linhas prontas, então
  "campo no PATCH" **não** é correção. A medição objetiva exige preservar extraído × final aceito
  (issue de follow-up).
- **Semântica de proveniência.** A proveniência V28 (`extractor_provider`, `extractor_model`,
  `fallback_from`, `fallback_reason`, `extraction_latency_ms`) atribui o provider vencedor;
  `extractor_version` e `overall_confidence` são **V23** (`import_foundation`/`staged_transactions`),
  não V28. `fallback_from != null` **não** significa "rodou no Ollama" após uma inversão.
- **Privacidade do Gemini free tier.** O AI Studio free tier tipicamente usa os dados enviados
  para treino; verificar os termos aplicáveis e, no mínimo, documentar. Argumento adicional a
  favor do processamento local como caminho primário.
- **Disponibilidade.** Local-only faz do homelab um SPOF da visão; manter Gemini como fallback de
  disponibilidade onde autorizado, com importação manual como último recurso.
- **Capacidade do host.** RAM (30 GiB, com swap em uso), disco (93%) e disputa do Ollama com o
  OpenWebUI no k8s são limites operacionais a medir sob carga.

## Consequências

- A extração por imagem passa a ter política explícita de provider e um caminho local medido, em
  vez de uma troca de modelo justificada por benchmark de fabricante.
- Nenhuma mudança de contrato de API nem de schema; a proveniência V28 registra o modelo efetivo,
  habilitando `GROUP BY` para acompanhamento.
- O ganho de privacidade é proporcional à fração roteada localmente; a de disponibilidade depende
  da política escolhida pós-experimento.
- A ADR passa a declarar metas mensuráveis e a **ausência de economia direta atual**, corrigindo a
  expectativa criada pela versão anterior.
