# Spec: Extração — Fase 3 (fatia 1): extrator de texto de PDF

**Data:** 2026-07-31
**Status:** proposto (aguardando aprovação)
**Fonte do produto:** `docs/roadmap-extracao-e-conciliacao.md` — Fase 3 ("PDF, registry de
templates e a camada de cobertura universal")
**Spec anterior:** `docs/superpowers/specs/2026-07-30-extracao-fase2-revisao-em-lote-design.md`
(Fase 2, metade B)
**Issue:** #205 (sub-issue do épico **#176** — Fase 3)
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto e escopo

A Fase 2 está entregue por completo (metade A: parsers CSV/OFX + pipeline N-transações;
metade B: revisão em lote). A Fase 3 do roadmap tem quatro entregas bem distintas: extrator
de PDF texto, extração via visão para PDF escaneado, registry de templates bancários e
telemetria por formato — cada uma com risco e dependência de dados diferentes.

**Por que fatiar aqui.** Registry de templates depende de dados de volume real por banco que
ainda não existem (a Fase 2 deixou isso como aprendizado pendente, sem uso em produção
suficiente para medir). PDF escaneado depende de estender o `VisionExtractor` para lidar com
páginas de PDF como imagem — escopo próprio. A fatia que **não** depende de nenhuma das duas
é o extrator de texto puro: PDFs com camada de texto (a maioria de extratos/faturas gerados
digitalmente, não fotografados) já resolvem uma fração relevante do problema com heurística
genérica, no mesmo espírito do `CsvExtractor` da Fase 2.

**Escopo desta spec:**
- `PdfTextExtractor`: extrai texto de PDF via Apache PDFBox e reconhece transações por
  heurística de linha (data + descrição + valor).
- Falha explícita e legível quando o PDF não tem texto extraível (é escaneado) — **não**
  tenta OCR nem roteia para IA nesta fatia.
- Reaproveita 100% do pipeline genérico existente (`ExtractionRouter`, guard-rails de
  sanidade, dedup) — nenhuma mudança no núcleo do `ImportService`.

**Fora de escopo desta fatia** (§11 detalha): PDF escaneado via visão, registry de
templates, validação soma × total declarado, telemetria por formato.

> **Nota pedagógica.** Esta fatia é o primeiro extrator do funil que **não tem** um contrato
> estruturado por trás (OFX tem tags, CSV tem colunas). Texto de PDF é só uma sequência de
> linhas sem separador estrutural — a heurística de reconhecimento é necessariamente mais
> frágil, e isso é esperado, não um defeito de implementação. O ponto a observar durante a
> execução: a taxa de reconhecimento vai ser baixa até o registry de templates (próxima
> fatia) entrar — e isso é o dado que justifica priorizar templates, exatamente como a Fase 2
> deixou "distribuição de bancos" como aprendizado para esta fase.

## 2. Decisões arquiteturais

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Biblioteca de extração de texto | Apache PDFBox (`org.apache.pdfbox:pdfbox`) | iText (AGPL/comercial — incompatível com SaaS fechado sem licença paga); Apache Tika (mais pesado, usa PDFBox internamente pra PDF — traria uma camada extra sem necessidade) |
| b | Posição no funil | `@Order(30)` — entre `CsvExtractor(20)` e `VisionExtractor(LOWEST_PRECEDENCE)` | Antes do CSV (PDF não compete com CSV/OFX — arquivos vêm com extensão/conteúdo distintos; ordem só importa para o caso raro de ambiguidade) |
| c | PDF sem texto (escaneado) | `ExtractionException` explícita ("PDF parece ser digitalizado — suporte a PDF escaneado ainda não implementado"); batch `FAILED` | Tentar OCR agora / rotear silenciosamente para `VisionExtractor` (que não sabe converter página de PDF em imagem) |
| d | Parsing sem registry de templates | Heurística de linha por regex (data + descrição + valor), confiança `0.7` nos campos inferidos — mesmo espírito do `CsvExtractor` genérico | Esperar o registry de templates antes de entregar qualquer extração de PDF (adiaria a fatia inteira sem necessidade — o pipeline genérico já tem o guard-rail de "zero linhas aproveitáveis → FAILED" para o caso de heurística não casar) |
| e | Validação "soma × total declarado" | Fora desta fatia | Depende de detectar a linha de "total" em texto livre — heurística adicional por si só; melhor tratada junto do registry de templates, quando já soubermos o layout de bancos específicos |

**(a) PDFBox.**
Apache License 2.0, puro Java, sem dependência nativa, mantido ativamente pela Apache
Software Foundation. `PDFTextStripper` extrai o texto na ordem de leitura — é exatamente a
API mínima necessária para esta fatia (não precisamos de manipulação de PDF, formulários ou
assinatura digital). iText é AGPL a partir da v5/v7 community (obrigaria a abrir o código do
projeto ou comprar licença comercial — inviável para um SaaS fechado). Tika resolveria o
mesmo problema, mas por baixo já delega para PDFBox no caso de PDF — trazer Tika seria
adicionar uma dependência maior (detecção de MIME de dezenas de formatos que não usamos) por
cima de uma que já resolve.

**(b) `@Order(30)`, entre CSV e Vision.**
O funil do roadmap §1.2 é: padrão universal → template conhecido → parser genérico → IA.
PDF texto é "parser genérico" (como CSV), mas para um formato diferente — não há
sobreposição real de `supports()` entre eles (magic bytes de PDF `%PDF-` nunca casam com
CSV/OFX). A posição exata entre 20 e `LOWEST_PRECEDENCE` só importa para deixar claro, no
código, que PDF ainda não tem "template conhecido" (isso é a próxima fatia, que entraria
com `@Order` menor que 30) e que a IA continua sendo o único fallback universal.

**(c) PDF escaneado falha explícito, não tenta IA.**
"PDF escaneado → extração via visão" é a **outra** entrega da Fase 3 (roadmap §2, linha
"Extrator de texto de PDF **+** extração via visão para PDF escaneado" — são dois itens
distintos na mesma fase). O `VisionExtractor` hoje só sabe processar bytes de imagem
(`ChatClient` com prompt de comprovante) — não sabe renderizar uma página de PDF como
imagem. Rotear um PDF escaneado para ele hoje seria mandar bytes de PDF para um prompt que
espera JPEG/PNG: falha pior e mais cara que a falha explícita. Mesma lógica da decisão (f)
da spec da Fase 2 ("formato não reconhecido falha, ainda não cai na IA") — aqui o formato
*é* reconhecido (é um PDF), mas o **conteúdo** (sem texto) ainda não tem extrator. A
diferença para o usuário é cosmética (mensagem mais específica: "este PDF parece ser uma
imagem digitalizada" em vez de "formato não reconhecido"), mas np pipeline é a mesma
filosofia: **erro explícito > erro silencioso**, fallback continua sendo o formulário
manual.

**(d) Heurística de linha, sem registry.**
Sem colunas (CSV) nem tags (OFX), o único sinal estrutural disponível em texto de PDF é o
padrão típico de uma linha de extrato: uma data, seguida de descrição, seguida de um valor
monetário no fim da linha (ou em posição fixa). A regex reconhece esse padrão por linha,
igual em espírito à heurística de coluna do `CsvExtractor` — com a mesma escala de
confiança já estabelecida na Fase 2 (§2.d daquela spec): `1.0` campo lido diretamente por
match de padrão inequívoco, `0.7` campo inferido (ex: direção pelo sinal do valor), `0.0`
campo ausente/não parseável. Esperar o registry de templates antes de entregar qualquer
coisa adiaria a fatia inteira sem necessidade: o guard-rail já existente no `ImportService`
("zero linhas aproveitáveis → `FAILED` com motivo") já cobre o caso de um PDF cujo layout a
heurística genérica não reconhece — o usuário nunca fica com dado errado calado, só com
falha explícita + formulário manual, igual ao CSV com header irreconhecível.

**(e) Soma × total fora desta fatia.**
Documentos que declaram total de forma consistente (rodapé de fatura, `LEDGERBAL` do OFX)
são o caso de uso real dessa validação — e coincide com os formatos que só o registry de
templates vai saber onde procurar (a posição do "total" varia por banco). Implementar agora,
sem saber onde o total aparece em cada layout, seria heurística sobre heurística — melhor
adiar para quando o registry já tiver por banco as posições conhecidas.

## 3. Invariante inviolável — isolamento de tenant

Nenhuma mudança no modelo estabelecido. `PdfTextExtractor` segue a mesma regra dos demais
extratores: recebe bytes (`ExtractionInput`), devolve `NormalizedBatchDTO` — **não conhece
tenant**. Todo acesso a banco (dedup por hash, persistência de batch/staged) continua
exclusivamente no `ImportService`, que já filtra por `user.getTenant()`.

## 4. Modelo de dados

**Nenhuma migration nova.** `ImportSourceType.PDF_TEXT` e o valor correspondente no `CHECK`
constraint do banco (`V23__import_foundation.sql`) já existem desde a fundação — reservados
para esta fase. Os campos extraídos (`amount`, `transaction_date`, `description`,
`direction`) já têm chave correspondente no `fields` JSONB existente; nenhuma chave nova é
necessária (diferente da Fase 2, que somou `external_id`/`currency` do OFX).

## 5. Contrato de API

**Nenhuma mudança de forma.** `POST /api/imports` já é multipart genérico — o roteamento por
conteúdo (`ExtractionRouter`) absorve o novo tipo sem tocar no endpoint. Único ajuste de
documentação: a descrição do endpoint em `api-spec/openapi.yaml` passa a citar PDF (com
texto) entre os formatos aceitos.

Spec-first: editar `api-spec/openapi.yaml` → `./scripts/api-sync.sh`.

## 6. Fluxo

### 6.1 Roteamento

```
POST /api/imports (multipart)
  ↓ bytes + filename + mimeType
ExtractionRouter.route(input)
  ├─ OfxExtractor.supports()      (@Order 10)
  ├─ CsvExtractor.supports()      (@Order 20)
  ├─ PdfTextExtractor.supports()  (@Order 30) → magic bytes "%PDF-"
  ├─ VisionExtractor.supports()   (@Order LOWEST_PRECEDENCE) → magic number de imagem
  └─ nenhum casa                  → ExtractionException("formato não reconhecido") → FAILED
  ↓
NormalizedBatchDTO (0..N transações reconhecidas)
  ↓
ImportService.createBatch (inalterado)
```

`supports()` do `PdfTextExtractor` reconhece **qualquer** PDF (pelo magic number), não só os
com texto — a distinção "tem texto ou não" acontece dentro de `extract()`, porque só ali o
PDFBox efetivamente abre o documento. Isso é intencional: se `supports()` fosse restrito a
"tem texto extraível", um PDF escaneado cairia no `VisionExtractor` (que não sabe processá-lo
hoje) em vez de receber a mensagem explicativa certa.

### 6.2 Extração e guard-rail de PDF escaneado

1. `PDFTextStripper` extrai o texto bruto do documento inteiro.
2. Se o texto extraído for vazio ou insignificante (abaixo de um limiar mínimo de
   caracteres não-whitespace) → `ExtractionException("Este PDF parece ser uma imagem
   digitalizada (sem texto extraível). Suporte a PDF escaneado ainda não está disponível —
   use o formulário manual ou envie como imagem.")` → batch `FAILED`.
3. Texto presente → parsing linha a linha pela heurística (§6.3).

### 6.3 Heurística de reconhecimento de transação

Por linha do texto extraído:
- Regex identifica um padrão de data (`DD/MM/YYYY`, `DD/MM/YY` ou `YYYY-MM-DD`) e um valor
  monetário (formato `1.234,56` ou `1234.56`, com sinal opcional) na mesma linha.
- Linha sem os dois padrões não vira transação (não é erro — pode ser cabeçalho, rodapé,
  linha de saldo).
- `description` = texto restante da linha após remover a data e o valor reconhecidos
  (confiança `0.7` — posição inferida, não coluna nomeada).
- `direction` = sinal do valor, ou palavra-chave (`débito`/`crédito`) se presente
  (confiança `0.7`).
- `amount`/`transaction_date` reconhecidos pelo padrão → confiança `1.0` (o padrão bateu
  exatamente); `overall_confidence` = menor confiança entre os dois campos críticos — mesma
  regra da Fase 2, sem exceção no núcleo.

### 6.4 Guard-rail de baixo reconhecimento

Reaproveita o guard-rail já existente no `ImportService`: se **nenhuma** linha do documento
gerar uma transação reconhecida, o batch falha explicitamente ("não foi possível reconhecer
transações neste PDF") em vez de retornar um batch vazio silencioso. Uma taxa de
reconhecimento parcial (algumas linhas reconhecidas, outras não) **não** falha o batch —
mesma filosofia da linha ruim de CSV (Fase 2, decisão e): o que não foi reconhecido
simplesmente não vira transação, o resto segue normalmente para staging.

### 6.5 Dedup e sanidade — sem mudança

`PdfTextExtractor` não introduz chave de dedup própria (sem `external_id` como o OFX) — cai
no mesmo trio (data, valor, descrição) do CSV. Guard-rails de `max-transactions`, janela de
data e valor não-zero são os mesmos do `ImportService`, sem alteração.

## 7. Frontend

**Nenhuma mudança funcional.** O input de upload já aceita arquivos genéricos; ajuste apenas
de rótulo/`accept` para incluir `.pdf` — mesmo padrão da Fase 2 (`accept=".csv,.ofx,.pdf,
image/*"`). A tela de revisão (item a item ou em lote, já entregue na metade B) processa o
batch normalmente, sem saber que a origem foi PDF.

## 8. Testes

| Camada | Cobertura |
|---|---|
| `PdfTextExtractor` (puro, sem Spring) | PDF com texto e transações reconhecíveis (fixture sintética gerada via PDFBox); PDF com texto mas sem nenhuma linha reconhecível (`ExtractionException`); PDF sem camada de texto/escaneado simulado (`ExtractionException` com mensagem específica); `supports()` aceita qualquer PDF pelo magic number, incluindo o caso escaneado |
| `ExtractionRouter` | PDF roteia para `PdfTextExtractor` mesmo com `Content-Type` genérico (`application/octet-stream`) |
| `ImportService` | batch de PDF com N staged; dedup por trio (data, valor, descrição) funciona igual ao CSV |
| Integração | upload de fixture PDF com texto → `GET /staged` → `commit` → transações criadas |

Fixtures em `backend/src/test/resources/imports/` (`pdf_texto_reconhecivel.pdf`,
`pdf_texto_sem_transacoes.pdf`, `pdf_sem_camada_texto.pdf` — geradas sinteticamente via
PDFBox num script de teste ou fixture pré-gerada, nunca extrato real de alguém).

## 9. Dataset de testes

Feature de backend sem tabela nova — **sem obrigação de seed** por si só (nenhuma coluna
nova, nenhuma entidade nova). Avaliar durante a execução se vale a pena um batch PDF no seed
`dev` (V28) para o frontend ter material real de revisão — decisão fica para a onda de
fechamento, não bloqueia o design.

## 10. Critérios de saída

Mapeamento com os critérios do épico #176 — esta fatia contribui para "zero bloqueio por
formato desconhecido" (parcialmente: cobre PDF com texto; PDF escaneado é fatia futura) e
para o "aprendizado" de taxa de reconhecimento por heurística genérica (entrada para decidir
templates).

Desta fatia (verificáveis):
- [ ] PDF com texto (fatura/extrato gerado digitalmente) processado ponta a ponta: upload →
      staged → commit.
- [ ] PDF escaneado (sem texto) falha explicitamente com mensagem legível, sem tentar
      extrair dado nenhum.
- [ ] PDF com texto mas layout não reconhecido pela heurística falha explicitamente (zero
      linhas aproveitáveis), não retorna batch vazio silencioso.
- [ ] Zero regressão nos extratores existentes (OFX, CSV, imagem).
- [ ] **Aprendizado registrado na issue #205:** taxa de reconhecimento da heurística genérica
      contra PDFs reais (quando disponíveis) — entrada para a decisão de templates.

## 11. Fora de escopo

- **PDF escaneado via visão/OCR** — próxima fatia da Fase 3 (mesmo épico #176).
- **Registry de templates bancários** — depende de dados de volume real, ainda não
  coletados; fatia própria da Fase 3.
- **Validação soma × total declarado** — depende do registry (§2.e).
- **Telemetria por formato** (volume, custo, taxa de casamento) — fatia própria.
- **Multi-transação por imagem** — #194, trilho separado (imagem, não PDF).

## 12. Riscos

| Risco | Mitigação |
|---|---|
| Heurística de linha casa muito pouco em PDFs reais (layout muito variado) | Esperado — é exatamente o dado que a fase existe para produzir. Usuário nunca fica sem saída: falha explícita + formulário manual. Registrar taxa na issue #205 |
| PDFBox falha ao abrir PDF corrompido/protegido por senha | Capturar exceção do PDFBox e converter em `ExtractionException` com mensagem legível — mesmo padrão do restante do pipeline (nenhuma exceção de infra cruza a borda da API) |
| Falso positivo: heurística reconhece "data + valor" numa linha que não é transação (ex: linha de saldo/rodapé com valor) | Aceitável nesta fatia — vira staged que o usuário descarta na revisão (mecanismo de descarte já entregue na metade B da Fase 2); pior caso é uma linha a mais para revisar, nunca dado incorreto lançado sem revisão |
| Limiar de "texto insignificante" (PDF escaneado) mal calibrado | Calibrar com fixture real de PDF escaneado durante a execução; limiar generoso (poucas dezenas de caracteres) é preferível a falso negativo (tentar parsear um PDF sem texto de verdade) |

## 13. Impacto SemVer

**MINOR** — `api-spec/openapi.yaml` ganha capacidade retrocompatível (novo tipo de arquivo
aceito). Nenhum campo removido ou renomeado; nenhum cliente existente quebra.

## 14. Ordem de execução sugerida

1. **Dependência `pdfbox`** no `backend/pom.xml` (versão pinada) — sozinha, sem código novo,
   suíte verde antes de prosseguir.
2. **`PdfTextExtractor`** com `supports()` (magic number) + guard-rail de PDF sem texto
   (`ExtractionException` explícita) — sem parsing de transação ainda, só prova o
   roteamento e a falha explícita.
3. **Heurística de linha** (data + descrição + valor) + fixtures de PDF com texto
   reconhecível e não reconhecível.
4. **`openapi.yaml` + `api-sync.sh`** + ajuste de `accept`/rótulo no frontend.
5. **Teste de integração** ponta a ponta (upload → staged → commit).
6. **Documentação**: `summary.md` (seção de Importação), `docs/roadmap-extracao-e-conciliacao.md`
   (marcar entrega parcial da Fase 3), aprendizado registrado na issue #205.

O passo 2 isola o roteamento e a falha explícita do PDF escaneado **antes** de qualquer
heurística de parsing entrar — se o guard-rail de "sem texto" e o parser de transação
quebrarem juntos, não se sabe qual foi.
