# ADR-007: Modelo de visão local (Ollama) para extração de comprovantes

## Status

Proposto — 2026-10-05.

Decisão registrada para aplicação futura. Não implementada neste documento — é um guia de
mudança que o time aplica quando priorizar. A troca em si é **só configuração** (nenhuma linha
de código Java muda), por isso o ADR carrega o passo-a-passo completo.

Depende do pipeline de importação existente (porta `VisionModelClient`, `VisionExtractor`,
fallback Gemini→Ollama) e do homelab descrito no ADR-004.

## Contexto

A extração de dados a partir de **imagem de comprovante** (Pix, recibo, nota, fatura de compra)
usa um LLM de visão atrás da porta `VisionModelClient`, com dois providers em funil:

- **Gemini** (`gemini-2.5-flash`, Google AI Studio free tier) — primário, `@Order(10)`.
- **Ollama** (homelab próprio) — fallback, `@Order(20)`.

O fallback do Gemini para o Ollama só dispara por falha de **disponibilidade** (429/5xx/
timeout/401/403/400 — `VisionProviderUnavailableException`), nunca por qualidade de leitura.
Como o free tier do Gemini tem rate-limit baixo, o 429 (cota) é o caminho de fallback **mais
comum** — ou seja, o Ollama é o provider efetivo de uma fração relevante das extrações, não um
mero plano B raro.

O modelo Ollama configurado hoje é `llama3.2-vision` (11B):

```properties
# backend/src/main/resources/application.properties:45
# backend/src/main/resources/application-prod.properties:43
spring.ai.ollama.chat.options.model=${OLLAMA_MODEL:llama3.2-vision}
```

Hardware do homelab: **GTX 1080 Ti (11 GB VRAM), 32 GB RAM.**

Dois problemas foram identificados com o modelo atual para este caso de uso específico:

1. **O modo imagem do Llama 3.2 Vision é oficialmente só inglês.** A página do modelo no
   Ollama declara que a entrada imagem+texto funciona **apenas em inglês**; português só vale
   no modo texto puro. O domínio aqui é 100% comprovante brasileiro, com prompt em PT-BR
   (`VisionExtractor.PROMPT`). O modelo está sendo usado fora da língua suportada para visão —
   candidato direto a parte da fragilidade de leitura que motivou a arquitetura de fallback.
2. **Llama 3.2 Vision é especialista em raciocínio sobre cena, não em transcrição de dígitos.**
   A tarefa aqui é ler `R$ 1.234,56` corretamente — OCR de número, não descrição de imagem. É
   justamente a classe de erro mais perigosa num app financeiro: um dígito trocado passa pelos
   guarda-corpos de sanidade do `ImportService` (que só pegam valor zero/data implausível) e
   vira transação após o commit.

Contexto da decisão de NÃO usar OCR gerenciado pago: avaliamos AWS Textract `AnalyzeExpense`
(US$ 0,01/página, confiança por campo calibrada, opt-out de treino via AWS Organizations). Foi
**descartado por ora** — não se justifica adicionar custo variável por uso enquanto o SaaS não
tem previsão de receita que o pague. Reavaliar quando houver volume real e receita (ver
"Alternativas avaliadas"). Isso mantém o custo da extração em **zero** (Gemini free tier +
Ollama homelab) e o teto passa a ser o hardware, não a conta.

## Decisão

**Trocar o modelo Ollama de `llama3.2-vision` para `minicpm-v4.5` (8B).** Mantém a arquitetura
de funil (Gemini primário → Ollama fallback) intacta; muda apenas qual modelo o provider
Ollama carrega.

Racional de `minicpm-v4.5` sobre `llama3.2-vision`, nos três eixos que importam aqui:

- **Idioma:** suporta 30+ idiomas na visão (resolve o problema do PT-BR do Llama).
- **Especialização:** família construída para **documento/OCR** (OCRBench líder segundo o
  fabricante), não para raciocínio sobre cena — alinhado a "ler o número certo".
- **Hardware:** download ~6.1 GB; com o overhead do projetor de visão + tokens de imagem + KV
  cache (regra prática: download + 1–2 GB) cabe com folga nos 11 GB da 1080 Ti.

### Aplicação (passo a passo)

1. No homelab, puxar o modelo:
   ```bash
   ollama pull minicpm-v4.5
   ```
   Conferir a versão mínima do Ollama exigida pelo modelo antes (um pull que falha com HTTP 412
   é "Ollama desatualizado", não erro de rede).

2. Atualizar o default do modelo (dois arquivos, mesmo valor):
   ```properties
   # backend/src/main/resources/application.properties
   # backend/src/main/resources/application-prod.properties
   spring.ai.ollama.chat.options.model=${OLLAMA_MODEL:minicpm-v4.5}
   ```
   Em produção o valor efetivo vem da env var `OLLAMA_MODEL` (ConfigMap/Secret do k3s) — basta
   apontá-la para `minicpm-v4.5`; o default no properties é a rede de segurança.

3. Nenhuma mudança de código Java. A porta `VisionModelClient` + `OllamaVisionClient` já
   abstraem o modelo; `extractorVersion`/`extractorModel` (proveniência V28) passam a gravar o
   novo modelo automaticamente a partir das properties.

4. Validar com um comprovante PT-BR real contra o Ollama (forçando o caminho de fallback, ex.:
   sem `GEMINI_API_KEY`) e conferir no log de `VisionExtractor` o `model=minicpm-v4.5` e o
   `overallConfidence`.

### Reversão

Reverter é trocar o valor de volta para `llama3.2-vision` nas properties/env e redeploy. O
modelo antigo pode continuar puxado no Ollama sem custo relevante de disco. Risco de reversão
baixo — é configuração, não schema nem contrato de API.

## Alternativas avaliadas

1. **Manter `llama3.2-vision` — rejeitada.** Modo imagem só-inglês é incompatível com o
   domínio PT-BR; modelo com ~1 ano, não especialista em OCR.
2. **`qwen3-vl:8b` (6.1 GB) — viável, não escolhida.** Excelente VLM generalista, OCR em 32
   idiomas, cabe nos 11 GB. Preterido porque é mais *generalista* que especialista em
   documento — para transcrição de comprovante, a família MiniCPM-V é a aposta mais direta.
   Candidato nº1 se o `minicpm-v4.5` decepcionar em teste real. Exige Ollama ≥ 0.12.7.
3. **`glm-ocr` (0.9B, 2.2 GB) — não escolhida isolada.** Especialista puro de OCR (topo do
   OmniDocBench segundo o fabricante), minúsculo. Transcreve muito bem mas **não raciocina**
   sobre a página (ex.: inferir `direction` debit/credit de um Pix). Melhor uso seria um
   pipeline de dois estágios (glm-ocr transcreve → modelo generalista interpreta) — arquitetura
   superior em tese, mas mais código e mais VRAM simultânea. Não vale enquanto um único modelo
   não for provado insuficiente (lazy: modelo melhor primeiro, pipeline depois se precisar).
4. **AWS Textract `AnalyzeExpense` como primário — adiada, não rejeitada em definitivo.**
   US$ 0,01/página, confiança por campo **calibrada** (diferente da auto-reportada do LLM) e
   opt-out de uso para treino via AI services opt-out policy do AWS Organizations. É a escolha
   mais defensável para dado financeiro de cliente, mas introduz custo variável por uso.
   **Reavaliar quando:** houver receita do SaaS que pague o custo, OU quando o volume de fotos
   de comprovante justificar OCR calibrado. A arquitetura já suporta plugá-lo depois como um
   `VisionModelClient` novo com `@Order` menor, sem tocar no pipeline (ver nota de risco de
   privacidade abaixo: o opt-out de treino é pré-requisito bloqueante).

## Riscos e ressalvas

- **Benchmarks são auto-reportados pelos fabricantes.** OCRBench/OpenCompass do MiniCPM-V,
  OmniDocBench do glm-ocr — todos números dos próprios criadores, sem avaliação independente em
  hardware local. Trate as margens como **direcionais**, não absolutas. A única prova que vale
  é medir nos comprovantes reais do projeto.
- **Falta sinal de acurácia real.** Hoje gravamos `extractionLatencyMs` e `overallConfidence`
  por batch (V28), mas `overallConfidence` é **auto-reportado pelo modelo** — e modelos fracos
  são mal-calibrados (reportam alta confiança em leitura errada). Não há métrica objetiva de
  "o modelo leu certo?". Trabalho futuro sugerido: comparar o valor extraído com o valor que o
  usuário **corrigiu na revisão** (patch da staged) para medir acurácia por modelo com dado
  próprio, em vez de benchmark de fabricante. Isso permitiria decidir entre modelos
  empiricamente.
- **Privacidade do Gemini free tier (independente desta decisão).** O AI Studio free tier
  tipicamente usa os dados enviados para treino. Para dado financeiro de cliente num SaaS
  multi-tenant, isso é um risco a **verificar e, no mínimo, documentar** — e é um argumento
  adicional a favor de manter o processamento local (Ollama) como caminho preferencial sempre
  que a qualidade permitir, não só como fallback de disponibilidade.
- **Mitigação barata recomendada em paralelo (fora do escopo deste ADR, mas relacionada):**
  forçar `requires_review` quando a extração caiu no fallback (`fallbackFrom != null`) — ou
  seja, quando rodou no modelo local, até haver confiança medida na sua acurácia. Análogo ao
  que o caminho de extrato (#194) já faz forçando review incondicional.

## Consequências

- A extração por imagem passa a usar um modelo adequado ao idioma (PT-BR) e à tarefa (OCR de
  documento), com custo mantido em zero.
- Nenhuma mudança de contrato de API, schema de banco ou comportamento observável pelo
  frontend — é troca de configuração do provider de visão.
- A proveniência estruturada (V28) passa a registrar `minicpm-v4.5` em `extractor_model`,
  tornando consultável por `GROUP BY` qual modelo processou cada batch — base para a futura
  medição de acurácia por modelo.
- O homelab precisa ter o modelo puxado (`ollama pull`) antes do deploy que muda a property;
  `spring.ai.ollama.init.pull-model-strategy=never` significa que a aplicação **não** puxa o
  modelo sozinha — um deploy apontando para um modelo não-puxado falha a extração até o pull
  manual.
