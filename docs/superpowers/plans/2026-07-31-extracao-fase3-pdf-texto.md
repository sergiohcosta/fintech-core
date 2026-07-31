# Extração Fase 3 (fatia 1) — Extrator de texto de PDF: Plano de Execução

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recomendado) ou superpowers:executing-plans. Ondas com checkbox (`- [ ]`) para tracking.
> **A Onda 1 é bloqueante** — isola dependência nova e roteamento antes de qualquer heurística
> de parsing.

**Goal:** Estender o pipeline de importação para reconhecer PDFs com camada de texto (não
escaneados), reconhecendo transações por heurística de linha (data + descrição + valor), com
falha explícita para PDF escaneado — sem tocar em registry de templates ou visão/OCR (fatias
futuras da Fase 3).

**Architecture:** Novo `PdfTextExtractor implements TransactionExtractor` (`@Order(30)`, entre
`CsvExtractor(20)` e `VisionExtractor(LOWEST_PRECEDENCE)`), plugado no `ExtractionRouter`
existente sem nenhuma mudança no `ImportService` ou no núcleo do pipeline. Usa Apache PDFBox
para extrair texto bruto; parsing por regex de linha.

**Tech Stack:** Java 21 · Spring Boot 4.0.1 · Apache PDFBox (nova dependência) · Angular 21
Zoneless · Vitest.

**Spec de referência:** `docs/superpowers/specs/2026-07-31-extracao-fase3-pdf-texto-design.md`
**Issue:** #205 (sub-issue do épico #176, milestone *Fase 3*)
**Roadmap estratégico:** `docs/roadmap-extracao-e-conciliacao.md` — Fase 3

## Global Constraints

- **Multi-tenant:** `PdfTextExtractor` não conhece tenant — recebe bytes, devolve
  `NormalizedBatchDTO`. Todo acesso a banco continua no `ImportService`.
- **Sem migration nova:** `ImportSourceType.PDF_TEXT` e o `CHECK` constraint já existem desde
  a V23. Nenhuma alteração de schema nesta fatia.
- **Spec-first:** editar `api-spec/openapi.yaml` primeiro → `./scripts/api-sync.sh` (nunca os
  passos manuais).
- **Erro explícito > erro silencioso:** PDF escaneado, layout não reconhecido e PDF corrompido
  falham com `failureReason` legível — nunca retornam dado incorreto ou batch vazio calado.
- **PT-BR** em comentários/commits; identificadores em inglês; imperativo; **sem**
  `Co-Authored-By`.
- **Baseline verde antes de iniciar:** `./scripts/test-summary.sh`. Falha pré-existente vira
  issue imediata.
- **Suíte backend demora >7 min:** rodar em background ou via `test-summary.sh`.
- **Worktree só depois** de spec + este plano commitados na `develop`.

---

## Decisões-chave (revisar antes de aprovar cada onda)

| Decisão | Escolha | Porquê / alternativa |
|---|---|---|
| Biblioteca de PDF | Apache PDFBox | Apache 2.0, puro Java, sem dependência nativa. iText é AGPL/comercial; Tika é mais pesado e usa PDFBox por baixo. |
| Posição no funil | `@Order(30)`, entre CSV(20) e Vision(LOWEST_PRECEDENCE) | PDF texto é "parser genérico" como o CSV, mas sem sobreposição de `supports()` (magic bytes distintos). |
| PDF escaneado | Falha explícita (`ExtractionException`), **não** tenta OCR/IA | `VisionExtractor` hoje só processa bytes de imagem — rotear PDF pra ele seria pior que falha explícita. Fatia futura. |
| Parsing sem registry | Heurística de linha (regex data+descrição+valor), confiança `0.7` nos campos inferidos | Esperar templates adiaria a fatia inteira sem necessidade — guard-rail de "zero linhas aproveitáveis" já cobre o caso de heurística não casar. |
| Soma × total declarado | Fora desta fatia | Depende de saber onde o total aparece por banco — isso é o registry (fatia futura). |

---

## Onda 1 — Dependência e esqueleto do extrator (BLOQUEANTE)

Objetivo: provar o roteamento e a falha explícita de PDF escaneado **antes** de qualquer
heurística de parsing de transação.

- [ ] Adicionar `org.apache.pdfbox:pdfbox` (versão pinada) ao `backend/pom.xml`.
- [ ] `PdfTextExtractor implements TransactionExtractor` (`@Order(30)`):
      - `supports()`: magic bytes `%PDF-` nos primeiros bytes do arquivo — reconhece
        qualquer PDF, escaneado ou não (a distinção acontece em `extract()`).
      - `sourceType()` = `ImportSourceType.PDF_TEXT`.
      - `extractorUsed = "pdf_text_v1"`; `extractorVersion` por property
        (`import.pdf-text.extractor-version:v1`), mesmo padrão de `OfxExtractor`/`CsvExtractor`.
- [ ] `extract()` (esqueleto): `PDFTextStripper` extrai o texto bruto do documento inteiro.
      Guard-rail: texto vazio/insignificante (limiar mínimo de caracteres não-whitespace) →
      `ExtractionException("Este PDF parece ser uma imagem digitalizada (sem texto
      extraível). Suporte a PDF escaneado ainda não está disponível — use o formulário
      manual ou envie como imagem.")`.
      Exceção do PDFBox ao abrir arquivo corrompido/protegido → capturada e convertida em
      `ExtractionException` com mensagem legível (nenhuma exceção de infra cruza a borda).
- [ ] Sem parsing de transação ainda — `extract()` retorna lista vazia quando há texto (só
      prova o caminho feliz do roteamento nesta onda).
- [ ] Testes: `supports()` aceita qualquer PDF pelo magic number; PDF sem texto →
      `ExtractionException` com a mensagem certa; PDF corrompido → `ExtractionException`
      sem vazar stacktrace/mensagem de infra; `ExtractionRouter` roteia PDF para
      `PdfTextExtractor` mesmo com `Content-Type` genérico (`application/octet-stream`).

**Gate:** `./scripts/test-summary.sh backend` verde + upload de PDF (com texto, ainda sem
transações reconhecidas) e de PDF escaneado simulado processados ponta a ponta, com o
segundo caindo em `FAILED` com a mensagem certa.

---

## Onda 2 — Heurística de reconhecimento de transação

- [ ] Regex de linha: identifica data (`DD/MM/YYYY`, `DD/MM/YY`, `YYYY-MM-DD`) e valor
      monetário (`1.234,56` ou `1234.56`, sinal opcional) na mesma linha.
- [ ] `description` = texto restante da linha após remover data e valor reconhecidos
      (confiança `0.7`).
- [ ] `direction` = sinal do valor ou palavra-chave (`débito`/`crédito`) se presente
      (confiança `0.7`).
- [ ] `amount`/`transaction_date` reconhecidos pelo padrão → confiança `1.0`;
      `overall_confidence` = menor confiança entre os dois campos críticos (mesma regra da
      Fase 2, `deriveRequiresReview` sem exceção no núcleo).
- [ ] Linha sem os dois padrões (data + valor) simplesmente não vira transação — não é erro
      de linha, é ausência de sinal (cabeçalho, rodapé, linha de saldo).
- [ ] Guard-rail: zero transações reconhecidas no documento inteiro → `ExtractionException`
      ("não foi possível reconhecer transações neste PDF") — reaproveita o guard-rail já
      existente no `ImportService` (batch `FAILED` com motivo), sem duplicar lógica.
- [ ] Fixtures em `backend/src/test/resources/imports/` (geradas sinteticamente via PDFBox,
      dados fictícios): `pdf_texto_reconhecivel.pdf` (algumas transações reconhecíveis),
      `pdf_texto_sem_transacoes.pdf` (texto presente, layout não reconhecido pela
      heurística), `pdf_sem_camada_texto.pdf` (simulação de escaneado — PDF só com imagem
      embutida, sem texto).
- [ ] Testes do parser puros (sem Spring): cada fixture; linha com valor mas sem data (não
      reconhece); linha com data mas sem valor (não reconhece); confiança correta por campo.

**Gate:** PDF com texto reconhecível → `NormalizedBatchDTO` com N transações corretas, em
teste unitário. PDF com texto mas sem nenhuma linha reconhecível → `ExtractionException`.

---

## Onda 3 — Contrato, frontend mínimo e fechamento

- [ ] `api-spec/openapi.yaml`: descrição de `POST /api/imports` passa a citar PDF (com texto)
      entre os formatos aceitos. Nenhuma mudança de schema/parâmetro.
- [ ] `./scripts/api-sync.sh` (nunca os passos manuais; conferir que `auth.service.ts`
      regenerado foi removido).
- [ ] Frontend (`features/import/`): `accept=".csv,.ofx,.pdf,image/*"`; rótulo que inclua PDF
      entre os formatos aceitos. Nenhuma mudança de lógica de revisão — o batch de PDF
      trafega pelo mesmo caminho de staged já existente.
- [ ] Teste de integração (`@SpringBootTest`): upload de fixture PDF com texto → `GET
      /staged` → `commit` → transações criadas com conta/categoria corretas.
- [ ] Avaliar durante a execução se um batch PDF no seed `dev` (V28) agrega valor para o
      frontend — decisão registrada na issue #205, não bloqueia a onda se não houver ganho
      claro (sem tabela/coluna nova, não é obrigação do `dataset.md`).
- [ ] `docs/http/seed-dataset.http`: request de upload de PDF.

**Gate:** `./scripts/test-summary.sh` verde (back + front) e fluxo manual ponta a ponta com
PDF real (com texto) e PDF escaneado real (falha explícita correta).

---

## Onda 4 — Documentação e fechamento

- [ ] `summary.md`: seção de Importação/Extração — adicionar PDF texto aos tipos aceitos,
      citar a falha explícita de PDF escaneado e a heurística de linha (sem registry ainda).
- [ ] `docs/roadmap-extracao-e-conciliacao.md`: marcar na Fase 3 a entrega desta fatia
      (extrator de texto de PDF) e o que resta (PDF escaneado via visão, registry de
      templates, soma × total, telemetria).
- [ ] **Aprendizado registrado na issue #205:** taxa de reconhecimento da heurística
      genérica contra PDFs reais (quando disponíveis) — entrada para a decisão de quais
      templates construir na próxima fatia.
- [ ] Merge em `develop` (após aprovação) + limpeza da worktree
      (`./scripts/clean-worktrees.sh`, a partir da raiz estável).

---

## Impacto SemVer

**MINOR** — `api-spec/openapi.yaml` ganha capacidade retrocompatível (novo tipo de arquivo
aceito na descrição do endpoint). Nenhum campo removido ou renomeado; nenhum cliente
existente quebra.

## Riscos e sinais de alerta

| Sinal durante a execução | O que significa | Ação |
|---|---|---|
| Heurística de linha casa muito pouco em PDFs reais | Esperado — é o dado que a fase existe para produzir | Registrar taxa na issue #205; usuário nunca fica sem saída (falha explícita + formulário manual) |
| PDFBox falhando em PDFs protegidos por senha com frequência | Formato real de uso inclui PDFs protegidos | Avaliar suporte a senha como extensão futura, não bloquear esta fatia |
| Limiar de "texto insignificante" gerando falso positivo/negativo | Calibração inicial imprecisa | Ajustar com fixture real de PDF escaneado durante a execução; preferir limiar generoso (evita tentar parsear PDF sem texto de verdade) |
| Falso positivo de linha reconhecida (ex: linha de saldo com data+valor) | Esperado, não é bug | Vira staged que o usuário descarta na revisão (mecanismo já entregue na metade B da Fase 2) |
