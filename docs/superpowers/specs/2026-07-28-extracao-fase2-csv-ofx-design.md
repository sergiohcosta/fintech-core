# Spec: Extração — Fase 2 (fatia backend): CSV/OFX e batch multi-transação

**Data:** 2026-07-28
**Status:** proposto (aguardando aprovação)
**Fonte do produto:** `docs/roadmap-extracao-e-conciliacao.md` — Fase 2 ("CSV/OFX e revisão em lote")
**Spec anterior:** `docs/superpowers/specs/2026-07-24-extracao-fundacao-e-mvp-imagem-design.md` (Fases 0 e 1)
**Épico:** #175 — [Épico] Fase 2 — CSV/OFX e revisão em lote (milestone *Fase 2*). Esta spec
cobre a **metade A** do épico (backend); a metade B (UX de revisão em lote) fica no mesmo
épico, com spec própria.
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto e escopo

A Fase 0 (fundação: `import_batches` + `staged_transactions` + contrato normalizado) e a
Fase 1 (MVP de imagem: `VisionExtractor` sobre Spring AI/Ollama, revisão, commit) estão
entregues. O sistema hoje sabe fazer **uma** coisa: uma imagem → uma transação em staging
→ uma `Transaction`.

A Fase 2 do roadmap cobre "formatos que trazem muitas transações de uma vez" e tem duas
metades bem separáveis: **(A) os parsers de arquivo + o pipeline aguentando N transações
por batch** e **(B) a UX de revisão em lote** (seleção múltipla, edição de categoria em
massa, paginação). Esta spec cobre **só a metade A**.

**Por que fatiar aqui.** A metade B é trabalho de frontend cuja forma certa depende de ver
um batch real de 50–200 linhas na tela; especificar a UX antes de ter o que exibir é
desenhar no escuro. A metade A, ao contrário, é autocontida, testável sem UI (fixtures de
arquivo → assertions no batch normalizado) e é o que produz o **aprendizado que o roadmap
declara como critério de transição da Fase 2**: a distribuição real de bancos/formatos, que
define quais templates construir na Fase 3.

**Escopo desta spec:**
- Roteamento determinístico de arquivo → extrator (o "funil de decisão" do roadmap §1.2).
- `OfxExtractor` (padrão único, vários bancos).
- `CsvExtractor` genérico (heurística de colunas — **sem** registry de templates; isso é Fase 3).
- Generalização do pipeline de import para N transações por batch.
- Dedup intra-batch (re-upload do mesmo arquivo + linhas duplicadas dentro do arquivo).
- Validações de sanidade embrionárias (roadmap §1.5).
- Frontend: **só** o mínimo para o fluxo existente aceitar arquivo (`accept`, rótulos,
  estado de erro). A tela de revisão continua item a item.

**Premissa aberta (revisar antes de executar).** O critério de saída da Fase 1 (precisão
≥95% em valor e data, taxa de edição ≤10–15%) ainda **não foi medido**: #191 (*Dataset de
avaliação — 50-100 comprovantes + harness de precisão/latência*, milestone *Fase 1*) segue
aberta. O roadmap §3 é explícito — "cada transição exige funcionalidade + qualidade +
aprendizado; avançar sem os três é acumular risco silencioso". Esta fatia é defensável
mesmo assim, porque **não depende** do extrator de visão (CSV/OFX são determinísticos, e o
caminho de imagem só é tocado pela refatoração da porta no passo 2 de §14), mas a decisão
de rodar #191 antes, em paralelo ou depois é do desenvolvedor, e deve ser consciente.

> **Nota pedagógica.** O ponto mais interessante desta fase é medir o que a Fase 0 comprou:
> o contrato normalizado já é uma **lista** (`NormalizedBatchDTO.transactions`), o
> `createBatch` já itera, o `commit` já recebe `items[]`. Se o desenho da fundação estava
> certo, "suportar N transações" deve ser quase de graça no núcleo, e o trabalho real fica
> todo nas bordas novas (parsers, roteamento, dedup). Vale conferir isso explicitamente
> durante a execução — se aparecer muita cirurgia no `ImportService`, é sinal de que algum
> acoplamento a "1 transação" passou despercebido na Fase 1.
>
> O segundo ponto a revisar com atenção: **confiança em extrator determinístico**. Um campo
> lido de uma coluna de CSV não é probabilístico — mas o contrato inteiro (e o
> `requiresReview`) é construído sobre `confidence`. A resposta (§2.d) é o que mantém os
> dois mundos no mesmo trilho sem exceções especiais no núcleo.

## 2. Decisões arquiteturais

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Seleção do extrator | `ExtractionRouter` + `supports()` em cada extrator, roteando por **sniff de conteúdo** | `if/else` de mimeType dentro do `ImportService` |
| b | Assinatura da porta | `TransactionExtractor.extract(ExtractionInput)` — record com bytes, filename, mimeType, mode | Manter `extract(byte[], String, ImportMode)` e ir somando parâmetros |
| c | Bibliotecas | CSV: `commons-csv`. OFX: parser próprio enxuto (só `<STMTTRN>`) | CSV caseiro / OFX via `ofx4j` |
| d | Confiança em extrator determinístico | Escala fixa e documentada: `1.0` campo lido, `0.7` campo inferido por heurística, `0.0` campo ausente | Confiança `1.0` em tudo que veio de arquivo |
| e | Linha inválida no meio do arquivo | Vira staged marcada (confiança 0 no campo problemático), não derruba o batch. Batch só falha se **nenhuma** linha for aproveitável | Rejeitar o arquivo inteiro na primeira linha ruim |
| f | Formato não reconhecido | Falha explícita com `failureReason` legível | Cair na IA (isso é a cobertura universal da **Fase 3**) |
| g | Re-upload do mesmo arquivo | `source_hash` (SHA-256) no batch → **409** com o id do batch anterior; `?force=true` reimporta | Bloquear silenciosamente / permitir duplicata calada |
| h | Duplicata dentro do arquivo | Marca `duplicate_candidate_of` (coluna já reservada desde a Fase 0); nada é descartado | Descartar a linha repetida na hora |

**(a) Roteador com `supports()`, não `if/else` no service.**
O roadmap §1.2 define um **funil de decisão por código, nunca pelo modelo**: padrão
universal (OFX) → template conhecido → parser genérico → IA. Esse funil vai ganhar degraus
nas fases seguintes (templates na 3, PDF na 3). Se a escolha do extrator morar num `if` do
`ImportService`, cada fase nova edita o coração do pipeline — exatamente o arquivo que não
deveria mudar quando só a periferia cresce. Um `ExtractionRouter` que percorre uma lista
ordenada de `TransactionExtractor` (Spring injeta todas as implementações; ordem por
`@Order`) transforma "adicionar um extrator" em "adicionar uma classe". A ordem **é** o
funil, e fica declarada num só lugar.

Detecção por **sniff de conteúdo**, não por `Content-Type` do navegador: `.ofx` costuma
chegar como `application/octet-stream`, e `.csv` como `text/plain` ou
`application/vnd.ms-excel` dependendo do sistema operacional do usuário. O `filename` e o
mimeType entram como pistas secundárias; o que decide é o conteúdo (OFX: cabeçalho
`OFXHEADER:` ou tag `<OFX>` nos primeiros bytes; imagem: magic number; CSV: texto com
delimitador consistente).

**(b) `ExtractionInput` em vez de mais parâmetros.**
A assinatura atual (`byte[] imageBytes, String mimeType, ImportMode mode`) já nomeia o
parâmetro como *image* — ela nasceu 1:1 com a Fase 1. Agora precisamos do `filename` (pista
de detecção e proveniência) e, em fases seguintes, provavelmente de charset detectado e de
conta-alvo sugerida. Um record `ExtractionInput(byte[] content, String filename, String
mimeType, ImportMode mode)` absorve isso sem quebrar assinatura a cada fase. Custo: uma
refatoração pequena no `VisionExtractor` e no `ImportService` agora, enquanto há um único
chamador.

**(c) `commons-csv` para CSV; parser próprio para OFX.**
Decisões opostas de propósito, e o critério é o mesmo — *onde está a dificuldade real?*

- **CSV** parece trivial e não é: aspas, delimitador dentro de campo entre aspas, escape de
  aspas por duplicação, quebra de linha dentro de campo. Um `split(";")` caseiro é o
  clássico bug que passa em teste e quebra com arquivo real ("PADARIA SAO JOSE; LTDA").
  `org.apache.commons:commons-csv` resolve RFC 4180 corretamente, é estável, sem
  dependências transitivas relevantes.
- **OFX 1.x é SGML, não XML** — tags sem fechamento (`<TRNAMT>-127.50` seguido de nova
  linha) fazem qualquer parser XML engasgar. Precisamos de um subconjunto minúsculo:
  `<STMTTRN>` com `DTPOSTED`, `TRNAMT`, `MEMO`/`NAME`, `FITID`, `TRNTYPE` (+ `CURDEF` e
  `ACCTID` do envelope). Um leitor tolerante de ~150 linhas cobre 1.x e 2.x (o 2.x é XML
  bem-formado, e o mesmo leitor tolerante o lê). A alternativa (`ofx4j`) traz um cliente de
  *banking* inteiro — conexão a instituições, perfis, agregação — para usar 5% dela, e é uma
  dependência com manutenção irregular. **Ponto a revisar com atenção:** se durante a
  execução aparecerem variações de banco que o leitor enxuto não cobre, a decisão certa é
  reavaliar a lib, não empilhar remendos.

**(d) Escala de confiança explícita para extrator determinístico.**
O contrato normalizado (roadmap §1.3) exige `confidence` por campo, e o `requiresReview` é
derivado dele (§2.f da spec anterior) — regra que vale para **qualquer** extrator, não só
para o de visão. Um parser de arquivo não "acha" nada, mas nem todo campo dele nasce igual:

| Situação | Confiança | Exemplo |
|---|---|---|
| Campo lido diretamente de coluna/tag identificada | `1.0` | `TRNAMT` do OFX; coluna `Valor` casada por header |
| Campo **inferido** por heurística | `0.7` | direção deduzida do sinal do valor; coluna de descrição escolhida por "a maior coluna textual" |
| Campo ausente ou não parseável | `0.0` | data em formato irreconhecível numa linha específica |

`overall_confidence` = **menor** confiança entre os campos críticos (`amount`,
`transaction_date`) — o elo mais fraco decide, não a média (média esconde exatamente o campo
que precisa de atenção). O resultado prático é que um OFX bem formado gera staged sem badge
de revisão, e um CSV cujas colunas foram adivinhadas gera staged marcadas — sem nenhuma
regra especial no `ImportService`: a mesma `deriveRequiresReview` de sempre.

**(e) Uma linha ruim não derruba o extrato.**
Um extrato de 200 linhas com 1 linha estranha (rodapé, linha de saldo, encoding quebrado)
não pode custar ao usuário o arquivo inteiro. Mas o princípio do roadmap é **erro explícito
> erro silencioso**: a linha não é descartada em silêncio, ela vira uma staged com o campo
problemático em confiança `0.0` → `requiresReview=true` → aparece marcada na revisão, e o
guarda-corpo do `commit` (já existente) bloqueia o lançamento se o usuário não corrigir.
Batch só vira `FAILED` quando **nenhuma** linha foi aproveitável — aí o `failureReason` diz
por quê.

**(f) Formato não reconhecido falha, ainda não cai na IA.**
"Nenhum formato desconhecido bloqueia o usuário" é critério de saída da **Fase 3**, não
desta. Encaminhar um CSV desconhecido para o `VisionExtractor` hoje seria mandar texto para
um modelo de visão com prompt de comprovante — falha pior e mais cara que a falha explícita.
Aqui o usuário recebe um motivo legível ("Não reconhecemos o formato deste arquivo…") e o
fallback continua sendo o formulário manual, igual à Fase 1. A cascata completa entra na
Fase 3, junto com a generalização do prompt.

**(g) Re-upload é 409, não silêncio nem duplicata.**
"Mesmo arquivo importado 2× não duplica" é critério de saída da Fase 2. A forma mais barata
e mais honesta de garantir isso é hash do conteúdo (`SHA-256`) gravado no batch, escopado
por tenant. Reenviar o mesmo arquivo responde **409** apontando o batch anterior — o
frontend mostra "este arquivo já foi importado em <data>" com link. Bloquear de vez seria
errado (reimportar deliberadamente é caso legítimo: o usuário descartou o batch anterior),
então `?force=true` reimporta explicitamente. As duas alternativas descartadas violam o
princípio central: ignorar calado esconde trabalho perdido; duplicar calado corrompe o
saldo — o pior desfecho possível num app financeiro.

**(h) Duplicata dentro do arquivo é marcada, não removida.**
Duas linhas idênticas no mesmo extrato podem ser duas compras iguais no mesmo dia (caso real
e frequente: dois cafés). Descartar automaticamente perderia uma transação verdadeira sem
aviso. O OFX resolve isso com autoridade: `FITID` é o identificador único da transação
naquele banco — dois `STMTTRN` com o mesmo `FITID` são a mesma transação, e aí sim é
duplicata de verdade. No CSV, sem identificador, a igualdade de (data, valor, descrição) é
só um **candidato**: grava `duplicate_candidate_of` apontando para a staged anterior (coluna
reservada desde a Fase 0, §4.3 da spec anterior) e a UI marca. Decisão fica com o usuário.
O algoritmo de similaridade de verdade — e o dedup import × import — é Fase 4, e esta fase
não antecipa nada dele.

## 3. Invariante inviolável — isolamento de tenant

Nada muda no modelo já estabelecido, e dois pontos novos entram sob a mesma regra:

- `ExtractionRouter` e os extratores **não conhecem tenant** — recebem bytes, devolvem
  `NormalizedBatchDTO`. Todo acesso a banco continua no `ImportService`, que recebe `User` e
  filtra por `user.getTenant()`.
- A checagem de `source_hash` (decisão g) é **escopada por tenant**:
  `findByTenantAndSourceHash`. Um hash nunca pode revelar que outro tenant importou o mesmo
  arquivo — o mesmo extrato do mesmo banco pode legitimamente existir em dois tenants.
  **Teste obrigatório:** tenant A importa o arquivo X; tenant B importa o arquivo X e é
  aceito normalmente (sem 409).
- Staged de N linhas segue com `tenant_id` denormalizado por linha (defesa nº1).

## 4. Modelo de dados

### 4.1 Migrations

| Versão | Conteúdo |
|---|---|
| **V26** | `ALTER TABLE import_batches ADD COLUMN source_hash VARCHAR(64) NULL` + `ADD COLUMN source_filename VARCHAR(255) NULL` + índice `(tenant_id, source_hash)` |
| **V27** | seed `dev` — 1 batch `CSV` `EXTRACTED` com 3 `staged_transactions` `PENDING` (uma delas com `duplicate_candidate_of` preenchido) |

(A migration mais recente hoje é V25 — `failure_reason`.)

`source_filename` entra junto porque proveniência é barata agora e cara depois (mesmo
racional do `posting_date` na Fase 0): sem ele, o batch da lista futura de importações não
sabe dizer *qual arquivo* foi importado, só quando. Nullable — batches pré-V26 (imagem)
seguem válidos.

**Dataset (regra inviolável):** V26 adiciona coluna a tabela existente → o seed novo (V27)
popula os campos, e o batch do V24 permanece com `source_hash` NULL (migration aplicada é
imutável — não se edita V24). O V27 dá ao frontend um batch multi-linha real para
desenvolver contra, que hoje não existe no seed.

### 4.2 Sem tabela nova

Nenhuma entidade nova. Os campos que os parsers produzem e que não têm coluna dedicada
entram no `fields` JSONB já existente, com as chaves do contrato normalizado mais duas:

| Chave | Origem | Confiança típica |
|---|---|---|
| `external_id` | `FITID` do OFX | `1.0` (ausente no CSV) |
| `currency` | `CURDEF` do OFX / coluna do CSV | `1.0` / `0.7` (default `BRL` inferido) |

`external_id` no JSONB em vez de coluna própria segue a decisão (b) da spec anterior: promove
a coluna quando (e se) precisar indexar/filtrar — o dedup import × import da Fase 4 é o
candidato natural a exigir isso, não esta fase.

## 5. Contrato de API

### 5.1 Mudanças em endpoint existente

`POST /api/imports` **não muda de forma** — já é multipart genérico (`file` + `importMode`).
Mudam a semântica e a documentação:

| Item | Antes | Depois |
|---|---|---|
| Tipos aceitos | `image/*` | imagem, CSV, OFX (detectados por conteúdo) |
| Transações por batch | sempre 1 | N |
| `400` | "não é imagem" | "formato de arquivo não reconhecido" |
| `409` (novo) | — | arquivo já importado (corpo aponta o `batchId` anterior) |
| `force` (novo) | — | query param opcional, `boolean`, default `false` |

Os demais endpoints (`GET /{id}`, `GET /{id}/staged`, `PATCH .../staged/{stagedId}`,
`POST /{id}/commit`) ficam **inalterados no contrato** — todos já operam sobre listas ou
sobre uma staged identificada. É a evidência de que a fundação estava certa.

Spec-first, sem exceção: editar `api-spec/openapi.yaml` → `./scripts/api-sync.sh`.

### 5.2 Limite conhecido: `GET /{id}/staged` sem paginação

Um extrato de 300 linhas retorna 300 objetos numa resposta só. Aceitável nesta fatia porque
(i) há um teto duro de linhas por arquivo (§6.3) e (ii) paginação sem UX de lote não serve a
ninguém. Fica **registrado como limite** e entra junto com a metade B (§11).

## 6. Fluxo

### 6.1 Roteamento (`ExtractionRouter`)

```
POST /api/imports (multipart)
  ↓ bytes + filename + mimeType
ExtractionRouter.route(input)
  ├─ OfxExtractor.supports()     → cabeçalho OFXHEADER: ou <OFX> nos primeiros bytes
  ├─ CsvExtractor.supports()     → texto + delimitador consistente + header plausível
  ├─ VisionExtractor.supports()  → magic number de imagem
  └─ nenhum casa                 → ExtractionException("formato não reconhecido") → batch FAILED
  ↓
NormalizedBatchDTO (1..N transações)
  ↓
ImportService.createBatch (inalterado — já itera)
```

A ordem da lista **é** o funil do roadmap §1.2: padrão universal primeiro (OFX), genérico
depois (CSV), IA por último. Na Fase 3, "template conhecido" entra entre OFX e CSV genérico,
e a IA passa de "último degrau" a "rede de segurança que nunca deixa cair".

### 6.2 Dedup

1. **Antes de extrair:** `sha256(bytes)` → `findByTenantAndSourceHash`. Achou e
   `force != true` → **409** com `{ batchId, createdAt, filename }`. (Antes de extrair, não
   depois: não gasta compute nem tokens para descobrir que era repetido.)
2. **Durante a extração:** dentro do mesmo batch, `FITID` repetido (OFX) ou trio
   (data, valor, descrição) repetido (CSV) → a segunda staged recebe
   `duplicate_candidate_of = id da primeira`. Nenhuma linha é descartada (decisão h).

### 6.3 Validações de sanidade (guarda-corpo, roadmap §1.5)

Aplicadas **pelo pipeline**, iguais para CSV, OFX e visão — o guarda-corpo é comum por
desenho, não por extrator:

| Regra | Property | Ação ao violar |
|---|---|---|
| Tamanho do arquivo | `spring.servlet.multipart.max-file-size` (10MB, já existe) | 400 do Spring |
| Máx. de transações por arquivo | `import.file.max-transactions` (default 500) | `FAILED` com motivo ("arquivo com N lançamentos excede o limite") |
| Data plausível | janela `[hoje − 10 anos, hoje + 1 ano]` | campo com confiança `0.0` → staged marcada |
| Valor parseável e ≠ 0 | — | campo com confiança `0.0` → staged marcada |
| Ao menos 1 linha aproveitável | — | `FAILED` com motivo |

"Soma × total declarado" (roadmap §1.5) fica para a Fase 3 — CSV/OFX genéricos raramente
declaram total, e faturas de PDF (onde isso importa de verdade) são daquela fase.

### 6.4 Commit

**Sem mudança de lógica.** `POST /{id}/commit` já recebe `items[]` com `accountId` e
`categoryId` por staged, valida sanidade dos valores atuais e reusa
`TransactionService.create`. Um batch de 40 linhas é 40 itens na mesma chamada, dentro da
mesma `@Transactional` — atômico por desenho (o usuário não fica com metade do extrato
lançado). Vale medir o tempo dessa transação com 500 itens durante a execução; se doer,
o ajuste é `saveAll`/batch de JDBC no caminho de criação, não quebrar a atomicidade.

## 7. Frontend (mínimo)

Escopo deliberadamente pequeno — a tela de revisão permanece item a item:

- `accept=".csv,.ofx,image/*"` no input e rótulos que não digam mais "imagem".
- Estado `409` tratado: mensagem com data do batch anterior + botão "importar mesmo assim"
  (reenvia com `force=true`).
- Aviso na linha com `duplicateCandidateOf` preenchido (badge, mesmo padrão do
  `requiresReview`).
- A tela hoje já renderiza N linhas (`ReviewRow[]`); confirmar isso com um batch de 40 linhas
  do seed V27 faz parte da execução — se travar ou ficar impraticável, **é dado de entrada
  para a metade B**, não motivo para inchar esta spec.

Lógica pura em `import-utils.ts` (já existe), testável sem `TestBed`.

## 8. Testes

| Camada | Cobertura |
|---|---|
| Parser OFX (puro, sem Spring) | fixture 1.x (SGML) e 2.x (XML); `FITID` duplicado; `TRNAMT` negativo → `debit`; arquivo sem `STMTTRN` |
| Parser CSV (puro) | delimitador `,` e `;`; decimal `,` e `.`; datas `DD/MM/YYYY` e `YYYY-MM-DD`; BOM UTF-8; campo com aspas e delimitador dentro; header irreconhecível → não `supports()` |
| `ExtractionRouter` | cada tipo roteia para o extrator certo **pelo conteúdo**, com mimeType mentindo (`application/octet-stream`) |
| `ImportService` | batch com N staged; linha inválida → staged marcada (não derruba); zero linhas → `FAILED` com motivo; dedup por hash (409) e `force=true` |
| **Tenant (obrigatório)** | mesmo arquivo em dois tenants → ambos aceitos; staged de A invisível a B |
| Integração | upload de fixture CSV → `GET /staged` → `commit` → transações criadas com conta/categoria corretas |

Fixtures em `backend/src/test/resources/imports/`. Nenhum teste toca Ollama (os extratores
determinísticos não usam IA; o `VisionExtractor` segue com `ChatClient` mockado).

## 9. Dataset de testes

- **V27 (seed `dev`):** batch CSV `EXTRACTED` + 3 staged `PENDING` da Família Costa, uma com
  `duplicate_candidate_of` — dá material real para a revisão multi-linha.
- **`docs/http/seed-dataset.http`:** requests de upload CSV e OFX, incluindo o caminho 409 e
  o `?force=true`.
- Fixtures de parser (§8) são artefato de teste, não dataset de produto — vivem em
  `src/test/resources`, com dados fictícios (nunca extrato real de alguém).

## 10. Critérios de saída

Mapeamento com os critérios do épico #175 — desta fatia saem os itens 1, 3 e 4; o item 2
("taxa de conclusão de revisão de batch") depende da metade B e permanece aberto no épico.

Desta fatia (verificáveis):
- [ ] Arquivo OFX real (1.x e 2.x) processado ponta a ponta: upload → staged → commit.
- [ ] Arquivo CSV real de pelo menos 2 bancos diferentes processado **sem erro silencioso**
      (falha explícita é aceitável; valor errado passando calado não é).
- [ ] Re-upload do mesmo arquivo não duplica (409) e `force=true` reimporta.
- [ ] Batch de 40+ linhas commitado numa transação só.
- [ ] Zero regressão no caminho de imagem da Fase 1.
- [ ] **Aprendizado registrado:** quais bancos/formatos apareceram e quais casaram no CSV
      genérico — é a entrada da decisão de templates da Fase 3.

Da Fase 2 completa, **fica pendente** (metade B): taxa de conclusão de revisão de batch
(usuário revisa 30+ transações sem abandonar) — não é mensurável sem a UX de lote.

## 11. Fora de escopo

- **UX de revisão em lote** (seleção múltipla, edição de categoria em massa, paginação de
  staged) — metade B da Fase 2 (mesmo épico #175), spec própria, alimentada pelo que esta
  fatia produzir.
- **Registry de templates bancários** e **IA como cobertura universal para arquivo** —
  Fase 3 (#176).
- **PDF** (texto e escaneado) — Fase 3 (#176).
- **Multi-transação por imagem** — #194 (milestone *Fase 3*); o guarda-corpo de recusa
  (#193, entregue) continua valendo e não é tocado aqui.
- **Dedup import × import** e categorização automática — Fase 4 (#177).
- **Harness de avaliação do extrator de visão** — #191, milestone *Fase 1* (ver §1).
- **`GET /api/imports`** (histórico de importações) — útil, mas é feature de tela, não da
  fatia backend.
- **Soma × total declarado** — Fase 3 (ver §6.3).

## 12. Riscos

| Risco | Mitigação |
|---|---|
| OFX varia mais entre bancos do que o subconjunto enxuto cobre | Fixtures reais logo na primeira task; se aparecer variação estrutural (não só campo extra), reavaliar `ofx4j` **antes** de empilhar remendos no parser próprio (decisão c) |
| CSV genérico casa pouco (headers muito diferentes do esperado) | É exatamente o dado que a fase existe para produzir — taxa baixa de casamento não é fracasso, é a fila de priorização de templates da Fase 3. O usuário nunca fica sem saída: falha explícita + formulário manual |
| Commit de 500 itens numa transação só fica lento | Medir durante a execução; otimizar dentro da transação (batch insert), nunca quebrando a atomicidade |
| Encoding (latin-1 vs UTF-8) corrompendo descrições | Detecção de BOM + fallback ISO-8859-1; descrição corrompida é campo de baixo impacto (não é valor nem data) e o usuário corrige na revisão |

## 13. Impacto SemVer

**MINOR** — `api-spec/openapi.yaml` ganha capacidade retrocompatível (tipos de arquivo novos,
`force` opcional, `409` novo num endpoint existente). Nenhum campo removido ou renomeado;
nenhum cliente existente quebra.

## 14. Ordem de execução sugerida

1. **V26** (migration) + entidade/repository (`source_hash`, `source_filename`).
2. **`ExtractionInput` + `ExtractionRouter`** com o `VisionExtractor` atual como única
   implementação — refatoração pura, suíte verde antes de qualquer parser novo.
3. **`OfxExtractor`** (o mais determinístico, menor superfície de heurística) + fixtures.
4. **`CsvExtractor`** genérico + fixtures.
5. **Dedup** (hash/409/`force` + `duplicate_candidate_of`).
6. **Validações de sanidade** (`import.file.max-transactions`, janela de data).
7. **openapi.yaml + `api-sync.sh`** + frontend mínimo.
8. **V27** (seed) + `seed-dataset.http`.

O passo 2 é o único que mexe em código existente e deve fechar com a suíte verde antes de o
passo 3 começar — se a refatoração da porta e um parser novo quebrarem juntos, não se sabe
qual foi.
