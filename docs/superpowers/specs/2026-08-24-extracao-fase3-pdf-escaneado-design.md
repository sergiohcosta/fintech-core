# Spec: PDF escaneado via rasterização (Fase 3, fatia 4)

**Data:** 2026-08-24
**Status:** aprovado (implementado nesta sessão)

## Contexto

`PdfTextExtractor` (fatias 1-3) reconhece qualquer PDF pelo magic number, mas só extrai
transação quando o documento tem camada de texto (`PDFTextStripper`). PDF escaneado (foto
de fatura digitalizada, sem texto vetorial) falhava explicitamente: "PDF parece ser uma
imagem digitalizada... suporte ainda não está disponível". Era a lacuna deliberadamente
adiada da fatia 1 ("suporte a PDF escaneado é fatia futura da Fase 3").

A peça que faltava já existia pronta: `VisionExtractor` (Fase 1) processa imagens (JPEG/
PNG/GIF/WEBP) com fallback Gemini→Ollama e detecção multi-transação (#194, extrato como
lista de lançamentos). Faltava só a ponte entre "PDF sem texto" e "imagem" — rasterização.

## Decisões

- **Delegação interna no `PdfTextExtractor`, não um extrator novo no `ExtractionRouter`.**
  Abordagem descartada — `ScannedPdfVisionExtractor` com `@Order(20)` próprio: a distinção
  "tem texto ou não" só é possível DEPOIS de abrir o PDF no PDFBox (mesmo argumento já
  usado pela fatia 1 pra `supports()` aceitar qualquer PDF). Um extrator externo duplicaria
  o parse do documento (uma vez pra decidir a rota, outra pra extrair) sem ganho real.

- **Delegação página a página ao `VisionExtractor`, não um payload multi-página novo.**
  Cada página rasterizada vira um `ExtractionInput` de imagem PNG sintética, processado
  pelo `VisionExtractor.extract()` já existente — reusa o prompt, o fallback Gemini→Ollama
  e a detecção `multipleTransactionsDetected` (#194) sem duplicar nenhuma lógica de visão.
  Abordagem descartada — despachar as N páginas como um único payload multi-imagem: exigiria
  um schema/prompt novo no `VisionExtractor` só pra este caminho, maior acoplamento, sem
  ganho de precisão evidente (o modelo já lida bem com 1 imagem por chamada).

- **Dependência tipada pela PORTA (`TransactionExtractor`), não pela classe concreta
  `VisionExtractor`.** Achado durante a implementação (não previsto no plano original): o
  bean Spring `visionExtractor` já é sobrescrito por nome em `ImportFailureReasonTest`
  (`@MockitoBean(name = "visionExtractor") TransactionExtractor extractor`) — tipar o
  construtor pela classe concreta quebra esse teste (`BeanNotOfRequiredTypeException`, o
  mock é da interface). Corrigido pra `@Qualifier("visionExtractor") TransactionExtractor
  visionExtractor` — mais desacoplado por natureza (só o contrato `extract()` é usado) e
  compatível com o padrão de teste já estabelecido no pacote.

- **Fail-fast de páginas ANTES de qualquer renderização ou chamada de IA.**
  `import.pdf-scanned.max-pages` (default 10) é checado assim que o `PDDocument` abre —
  documento acima do limite nunca gasta CPU renderizando nem tokens de LLM.

- **All-or-nothing por página, sem estado parcial persistido.** Falha de UMA página
  (visão indisponível, conteúdo implausível) derruba o documento inteiro — nenhuma
  transação das páginas anteriores é aproveitada. Abordagem descartada — persistir as
  páginas que deram certo e marcar as demais como falha: um extrato com metade das
  transações reais seria pior que nenhuma (o usuário confia que o import é completo).

- **`requiresReview=true` forçado em toda transação do caminho escaneado.**
  Mesmo staged rollout do caminho de extrato do #194: zero dado de produção sobre acurácia
  do modelo em página rasterizada (ângulo de câmera, qualidade de digitalização variável),
  então a confiança por campo sozinha não é suficiente pra dispensar revisão humana.

- **Proveniência (`extractorProvider`/`extractorModel`/`fallbackFrom`/`fallbackReason`)
  capturada da PRIMEIRA página, uniformemente.** Achado da design review multi-LLM: um
  rascunho inicial misturava "provider da ÚLTIMA página" com "fallback da PRIMEIRA página"
  — inconsistência que passaria despercebida numa auditoria (`GROUP BY provider` mentiria).
  Corrigido pra uma única regra: tudo da primeira página.

- **`sourceType` correto em batch `FAILED` — `ScannedPdfExtractionException`.**
  Achado tardio (revisão de código pós-implementação, confiança 85): `TransactionExtractor
  .sourceType()` é fixo por extrator (`PDF_TEXT` pro `PdfTextExtractor`) e só cobre o caso
  "`extract()` nunca chegou a decidir o sub-caminho" — não cobre "decidiu escaneado e
  falhou depois" (limite de páginas, página ilegível). Sem correção, TODO `FAILED` do
  caminho escaneado gravaria `PDF_TEXT`, o oposto do que a proveniência V28 existe pra
  rastrear. `ScannedPdfExtractionException` (subtipo de `ExtractionException`, pacote-
  privada) sinaliza a origem; `ImportService.createFromFile` checa o tipo antes de gravar
  `sourceType`. Mensagem e causa originais preservadas (#193: nunca perder o motivo PT-BR).

- **Sem template bancário para PDF escaneado.** Mesmo princípio já aplicado à fatia 2
  (templates são "cache compilado" por volume real, não desenvolvimento preventivo) — a
  primeira parada é sempre o prompt genérico de visão, que já decide sozinho comprovante
  único vs. lista de lançamentos.

- **Zero migration, zero mudança de contrato.** `ImportSourceType.PDF_SCANNED` e as colunas
  de proveniência V28 (`extractor_provider`, `extractor_model`, `fallback_from`,
  `fallback_reason`, `extraction_latency_ms`) já existiam no schema desde antes desta
  entrega, sem uso — a spec do discover multi-LLM chegou a sugerir colunas novas
  (`cost_micros`, `input_tokens`) que **não existem** no schema real e não foram criadas.

## Contrato técnico

```
PdfTextExtractor.extract(input)
  └── PDDocument aberto UMA VEZ (Loader.loadPDF, try-with-resources)
        ├── meaningfulChars >= MIN_TEXT_CHARS → caminho de texto (templates/heurística, inalterado)
        └── meaningfulChars <  MIN_TEXT_CHARS → extractScanned(document, input)
              ├── pageCount > scannedMaxPages? → ScannedPdfExtractionException (fail-fast)
              ├── visionExtractor == null?     → ScannedPdfExtractionException (config ausente)
              └── para cada página 0..N-1:
                    ├── PDFRenderer.renderImageWithDPI(page, scannedRenderDpi, RGB) → BufferedImage
                    ├── ImageIO.write(..., "png") → byte[]
                    └── visionExtractor.extract(ExtractionInput(pngBytes, filename, "image/png", mode))
                          └── falha de QUALQUER página → recapturada como ScannedPdfExtractionException
                                (mesma mensagem/causa) e propagada — all-or-nothing
              └── agrega transações + latência (soma) + proveniência (1ª página) + fallback (1º sinal)
              └── força requiresReview=true em toda transação
              └── retorna NormalizedBatchDTO(sourceType=PDF_SCANNED, extractorUsed="vision_pdf_scanned_<provider>_<model>", ...)
```

**Configuração nova** (`application.properties`):
- `import.pdf-scanned.max-pages=10` — fail-fast, chute inicial sem calibração.
- `import.pdf-scanned.render-dpi=150` — qualidade da rasterização, chute inicial (trade-off
  heap vs. acuidade visual do modelo, não calibrado contra fatura brasileira real).

## Teste

Cobertura em `PdfTextExtractorTest` (22 testes na classe, dublê `FakeVisionExtractor` sem
Mockito — evita o inline mock maker, frágil em alguns sandboxes de execução):
- Delega ao `VisionExtractor` quando PDF não tem texto extraível, grava `sourceType=PDF_SCANNED`.
- Agrega transações de múltiplas páginas, soma latência.
- Fail-fast: PDF acima do limite de páginas nunca chama o `VisionExtractor`.
- Propaga falha de qualquer página (all-or-nothing), reembrulhada em `ScannedPdfExtractionException`.
- Caminho de texto (fatias 1-3, templates, heurística) inalterado — 18 testes preexistentes continuam verdes.

Suíte completa do backend: 383 testes, 0 falhas (`./mvnw test`).

## Fora de escopo

- **Template bancário para escaneado** — sem volume real ainda (mesmo princípio da fatia 2).
- **Reconciliação soma×total cross-página** — a validação contábil do #194 é intra-página;
  somar todas as páginas contra um total impresso na capa é fatia futura, se necessário.
- **Processamento assíncrono/fila** — permanece síncrono dentro do request HTTP, como todo
  o resto do pipeline; documento de 10 páginas pode levar dezenas de segundos (2 chamadas
  de visão por página no pior caso, comprovante + extrato — ver `VisionExtractor#extract`).
  Timeout de proxy/gateway em produção precisa acomodar isso.
- **Calibração de DPI** — 150 é ponto de partida, não validado contra amostra real.
- **Telemetria de drift/custo por formato (P1b do roadmap)** — decisão consciente de
  desacoplar desta entrega (evita PR inchado com critérios de aceite conflitantes).
