# Extração Fase 2 (metade A) — CSV/OFX e batch multi-transação: Plano de Execução

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recomendado) ou superpowers:executing-plans. Ondas com checkbox (`- [ ]`) para tracking.
> **A Onda 1 é bloqueante para todas as demais** — ela refatora código existente e precisa
> fechar com a suíte verde antes de qualquer parser novo entrar.

**Goal:** Levar o pipeline de importação de "1 imagem = 1 transação" para "1 arquivo = N
transações", com dois extratores determinísticos (OFX e CSV genérico), roteamento por
conteúdo, dedup intra-batch e guarda-corpo de sanidade — sem tocar na tela de revisão, que
continua item a item.

**Architecture:** `ExtractionRouter` percorre uma lista ordenada de `TransactionExtractor`
(`supports()` por sniff de conteúdo) — a ordem **é** o funil do roadmap §1.2 (padrão
universal → genérico → IA). Todo extrator converge para `NormalizedBatchDTO` (contrato já
existente, já uma lista). `ImportService` e o `commit` não mudam de forma: recebem N onde
antes recebiam 1.

**Tech Stack:** Java 21 · Spring Boot 4.0.1 · Spring Data JPA · Flyway · `commons-csv` (nova
dependência) · Angular 21 Zoneless · Orval · Testcontainers · Vitest.

**Spec de referência:** `docs/superpowers/specs/2026-07-28-extracao-fase2-csv-ofx-design.md`
**Issue:** #196 (sub-issue do épico #175, milestone *Fase 2*)
**Roadmap estratégico:** `docs/roadmap-extracao-e-conciliacao.md` — Fase 2

## Global Constraints

- **Multi-tenant:** toda query filtra pelo `Tenant` do usuário autenticado. `source_hash` é
  consultado **por tenant** (`findByTenantAndSourceHash`) — o mesmo extrato pode
  legitimamente existir em dois tenants, e um hash nunca pode revelar isso.
- **Extratores não conhecem tenant:** recebem bytes, devolvem `NormalizedBatchDTO`. Todo
  acesso a banco continua no `ImportService`.
- **Schema só via Flyway:** migrations imutáveis. Próximas versões livres: **V26** (schema),
  **V27** (seed dev). Não editar V24/V25.
- **Entidade JPA nunca exposta** em controller — sempre DTO, com Bean Validation.
- **Spec-first:** editar `api-spec/openapi.yaml` primeiro → `./scripts/api-sync.sh` (nunca
  os passos manuais).
- **Exceções:** usar `com.fintech.api.exception.{BusinessException,EntityNotFoundException}`
  — nunca a `EntityNotFoundException` do `jakarta.persistence`.
- **Auth:** `/api/imports` já está sob `.anyRequest().authenticated()` — **nenhuma**
  alteração em `SecurityConfigurations.java`.
- **Dataset vivo:** V26 mexe em tabela existente → seed V27 + `docs/http/seed-dataset.http`
  atualizados na mesma entrega (regra inviolável de `dataset.md`).
- **PT-BR** em comentários/commits; identificadores em inglês; imperativo; **sem**
  `Co-Authored-By`.
- **Baseline verde antes de iniciar:** `./scripts/test-summary.sh`. Falha pré-existente vira
  issue imediata — não se tolera "idêntico ao baseline".
- **Suíte backend demora >7 min:** rodar em background ou via `test-summary.sh`; para
  feedback rápido, `-Dtest=ClasseEspecifica`.
- **Worktree só depois** de spec + este plano commitados na `develop` (spec: `bfbc34d`).

---

## Decisões-chave (revisar antes de aprovar cada onda)

| Decisão | Escolha | Porquê / alternativa |
|---|---|---|
| Seleção de extrator | `ExtractionRouter` + `supports()`, sniff de conteúdo | Cada fase futura adiciona uma classe, não edita o `ImportService`. `Content-Type` do browser mente (`.ofx` → `application/octet-stream`). |
| Assinatura da porta | `extract(ExtractionInput)` | Absorve `filename` (pista de detecção + proveniência) e o que vier depois, sem quebrar assinatura a cada fase. |
| CSV | `commons-csv` | Aspas/escape/delimitador-dentro-de-campo é onde parser caseiro erra silenciosamente. |
| OFX | Parser próprio enxuto (`<STMTTRN>`) | 1.x é SGML com tags não fechadas — parser XML engasga. `ofx4j` traz um cliente de banking inteiro para usar 5%. **Se aparecer variação estrutural entre bancos, reavaliar a lib — não remendar.** |
| Confiança determinística | `1.0` lido · `0.7` inferido · `0.0` ausente; `overall` = menor dos críticos | Mantém CSV/OFX no mesmo `deriveRequiresReview` da Fase 1, sem exceção no núcleo. Média esconderia o campo fraco. |
| Linha ruim | Vira staged marcada (confiança `0.0`), não derruba o batch | Extrato de 200 linhas não pode morrer por 1 rodapé. Nada passa calado: marca + guarda-corpo do commit. |
| Formato desconhecido | Falha explícita com `failureReason` | Cair na IA é critério de saída da **Fase 3**; hoje seria mandar texto para prompt de visão. |
| Re-upload | 409 apontando o batch anterior; `?force=true` | Duplicar calado corrompe saldo — o pior desfecho num app financeiro. Bloquear de vez seria errado (reimportar é caso legítimo). |
| Duplicata no arquivo | Marca `duplicate_candidate_of` | Duas compras iguais no mesmo dia são caso real. `FITID` (OFX) é autoridade; CSV é só candidato. |

---

## Onda 1 — Refatoração da porta (BLOQUEANTE, sem comportamento novo)

Objetivo: preparar o terreno **sem** adicionar formato nenhum. Se esta onda e um parser novo
quebrarem juntos, não se sabe qual foi.

- [ ] `ExtractionInput` (record em `service/imports/`): `content` (byte[]), `filename`,
      `mimeType`, `mode`.
- [ ] `TransactionExtractor`: assinatura passa a `NormalizedBatchDTO extract(ExtractionInput)`
      + método `boolean supports(ExtractionInput)`. Javadoc atualizado (a porta deixa de ser
      "de imagem").
- [ ] `VisionExtractor`: adapta à nova assinatura; `supports()` = magic number de imagem
      (JPEG `FF D8 FF`, PNG `89 50 4E 47`, GIF, WEBP) — **não** confia no mimeType.
- [ ] `ExtractionRouter` (`@Service`): recebe `List<TransactionExtractor>` (Spring injeta
      todas, ordem por `@Order`), devolve o primeiro que `supports()`; nenhum → lança
      `ExtractionException("Não reconhecemos o formato deste arquivo. Envie uma imagem de
      comprovante, um extrato CSV ou um arquivo OFX.")`.
- [ ] `ImportService.createFromImage` → `createFromFile(ExtractionInput, User)`; delega ao
      router. `sourceType` do batch passa a vir do extrator escolhido (hoje é `IMAGE` fixo).
      Caminho de falha (`FAILED` + `failureReason`) inalterado.
- [ ] `ImportController`: passa `file.getOriginalFilename()` adiante; mensagem de erro de
      MIME sai do controller (a validação vira responsabilidade do router).
- [ ] Testes: `ExtractionRouter` roteia por conteúdo com mimeType mentindo
      (`application/octet-stream`); `VisionExtractor.supports()` aceita/rejeita por magic
      number; regressão do caminho de imagem da Fase 1 verde.

**Gate:** `./scripts/test-summary.sh backend` verde + upload de imagem funcionando ponta a
ponta como antes. Só então a Onda 2 começa.

---

## Onda 2 — `OfxExtractor`

Começa pelo OFX (não pelo CSV) porque é o mais determinístico: identificador único por
transação (`FITID`), campos nomeados, zero heurística de coluna. Ele valida o desenho do
router com o mínimo de variável solta.

- [ ] `OfxExtractor implements TransactionExtractor` (`@Order(10)` — padrão universal
      primeiro): `supports()` = `OFXHEADER:` (1.x) ou `<OFX` (2.x) nos primeiros bytes.
- [ ] Leitor tolerante de SGML/XML: envelope (`CURDEF`, `ACCTID`) + N `<STMTTRN>`
      (`DTPOSTED`, `TRNAMT`, `MEMO`/`NAME`, `FITID`, `TRNTYPE`).
- [ ] Mapeamento para o contrato normalizado:
      - `amount` = |TRNAMT| (conf. `1.0`); `direction` = sinal de TRNAMT (`-` → `debit`)
        (conf. `1.0` — o sinal é dado, não inferência)
      - `transaction_date` = DTPOSTED (aceitar `YYYYMMDD` e `YYYYMMDDHHMMSS[.SSS][TZ]`)
      - `description` = `MEMO` ou, na ausência, `NAME` (conf. `1.0`)
      - `external_id` = `FITID` (conf. `1.0`) · `currency` = `CURDEF` (conf. `1.0`)
      - `overall_confidence` = menor entre `amount` e `transaction_date`
- [ ] `extractorUsed = "ofx_parser_v1"`, `extractorVersion` por constante/property;
      `sourceType = OFX`.
- [ ] Fixtures em `backend/src/test/resources/imports/`: `ofx_1x_sample.ofx` (SGML),
      `ofx_2x_sample.ofx` (XML), `ofx_fitid_duplicado.ofx`, `ofx_sem_stmttrn.ofx`. Dados
      fictícios — nunca extrato real de alguém.
- [ ] Testes do parser **puros** (sem Spring): cada fixture; `TRNAMT` negativo → `debit`;
      arquivo sem `STMTTRN` → `ExtractionException`.

**Gate:** OFX 1.x e 2.x → `NormalizedBatchDTO` com N transações corretas, em teste unitário.

---

## Onda 3 — `CsvExtractor` genérico

- [ ] Dependência `org.apache.commons:commons-csv` no `backend/pom.xml` (versão pinada).
- [ ] `CsvExtractor implements TransactionExtractor` (`@Order(20)`): `supports()` = conteúdo
      textual + delimitador consistente + header plausível (≥1 coluna de data e ≥1 de valor
      reconhecidas). Header irreconhecível → **não** `supports()` → cai no erro explícito do
      router (Fase 3 é quem manda isso para a IA).
- [ ] Heurísticas determinísticas, cada uma documentada em comentário pedagógico:
      - **Charset:** BOM UTF-8 → UTF-8; senão tenta UTF-8 estrito e cai para ISO-8859-1.
      - **Delimitador:** `,` vs `;` por contagem consistente entre linhas.
      - **Header:** casamento por sinônimos normalizados (sem acento, minúsculo) —
        data/`date`/`data da compra`; valor/`amount`/`quantia`; descrição/`description`/
        `histórico`/`estabelecimento`.
      - **Decimal:** `,` vs `.` inferido do padrão da coluna (conf. `1.0` se inequívoco).
      - **Data:** `DD/MM/YYYY` e `YYYY-MM-DD` (conf. `1.0`); outros formatos → `0.0`.
      - **Direção:** sinal do valor, ou coluna de tipo se houver (conf. `0.7` — inferência).
- [ ] Colunas casadas por header → conf. `1.0`; colunas escolhidas por posição/heurística →
      conf. `0.7`. `extractorUsed = "csv_generic_v1"`; `sourceType = CSV`.
- [ ] Fixtures: `csv_virgula_iso.csv`, `csv_pontovirgula_ptbr.csv` (decimal `,`, data
      `DD/MM/YYYY`, BOM), `csv_aspas_com_delimitador.csv`, `csv_header_irreconhecivel.csv`,
      `csv_linha_invalida.csv`.
- [ ] Testes do parser puros, um por fixture.

**Gate:** CSV de 2 dialetos distintos extraído corretamente; header irreconhecível não é
aceito pelo `supports()`.

---

## Onda 4 — Persistência, dedup e guarda-corpo

- [ ] **Migration `V26__import_source_hash.sql`** (última hoje é V25):
      `ALTER TABLE import_batches ADD COLUMN source_hash VARCHAR(64) NULL`,
      `ADD COLUMN source_filename VARCHAR(255) NULL`, índice `(tenant_id, source_hash)`.
- [ ] `ImportBatch` + `ImportBatchResponseDTO` ganham os campos;
      `ImportBatchRepository.findByTenantAndSourceHash`.
- [ ] **Dedup por arquivo:** `sha256(bytes)` calculado **antes** de extrair (não gasta
      compute para descobrir que era repetido). Achou + `force != true` → **409** com
      `{ batchId, createdAt, filename }`. `?force=true` reimporta.
- [ ] **Dedup dentro do batch:** `FITID` repetido (OFX) ou trio (data, valor, descrição)
      repetido (CSV) → segunda staged recebe `duplicateCandidateOf` = id da primeira.
      Nenhuma linha descartada.
- [ ] **Sanidade** (`ImportService`, comum a todos os extratores):
      `import.file.max-transactions=500` (excedeu → `FAILED` com motivo); data fora de
      `[hoje−10 anos, hoje+1 ano]` → campo com conf. `0.0`; valor não parseável ou `0` →
      campo com conf. `0.0`; **zero linhas aproveitáveis** → `FAILED` com motivo.
- [ ] Testes de service: N staged criadas; linha inválida marca sem derrubar; zero linhas →
      `FAILED`; 409 e `force=true`; **tenant** — mesmo arquivo em dois tenants é aceito nos
      dois (teste obrigatório), staged de A invisível a B.

**Gate:** dedup e sanidade cobertos por teste; nenhuma regressão na suíte.

---

## Onda 5 — Contrato, frontend mínimo e dataset

- [ ] `api-spec/openapi.yaml`: `POST /api/imports` — descrição atualizada (imagem, CSV,
      OFX), query param `force` (boolean, default false), resposta `409` com schema do
      conflito, `400` regenerado ("formato não reconhecido"). Demais paths **inalterados**.
- [ ] `./scripts/api-sync.sh` (nunca os passos manuais; lembra de conferir que o
      `auth.service.ts` regenerado foi removido).
- [ ] Frontend (`features/import/`): `accept=".csv,.ofx,image/*"`; rótulos que não digam
      "imagem"; tratamento do 409 (mensagem com data do batch anterior + botão "importar
      mesmo assim" → `force=true`); badge para `duplicateCandidateOf`. Lógica pura em
      `import-utils.ts`, testada sem `TestBed`. Rodar specs via `npm test` — **nunca**
      `npx vitest` cru.
- [ ] Seed **`V27__seed_dev_import_csv.sql`**: 1 batch CSV `EXTRACTED` + 3 staged `PENDING`
      da Família Costa, uma com `duplicate_candidate_of` preenchido, `source_hash`/
      `source_filename` populados.
- [ ] `docs/http/seed-dataset.http`: upload CSV, upload OFX, caminho 409 e `?force=true`.
- [ ] Teste de integração (`@SpringBootTest`): upload de fixture CSV → `GET /staged` →
      `commit` → transações criadas com conta/categoria corretas.
- [ ] Validar na tela um batch de 40+ linhas — se travar ou ficar impraticável, isso é
      **dado de entrada para a metade B** (registrar na issue), não motivo para inchar esta
      fatia.

**Gate:** `./scripts/test-summary.sh` verde (back + front) e fluxo manual ponta a ponta com
CSV e OFX reais.

---

## Onda 6 — Documentação e fechamento

- [ ] `summary.md`: seção de Importação/Extração atualizada — tipos aceitos, roteamento,
      dedup, 409/`force`, escala de confiança determinística.
- [ ] `domain.md`: `ImportBatch` ganha `sourceHash`/`sourceFilename`.
- [ ] `database-schema.md`: linha da V26 e da V27 (com o *porquê*, no padrão das demais).
- [ ] `docs/roadmap-extracao-e-conciliacao.md`: marcar na Fase 2 o que a metade A entregou e
      o que resta (metade B).
- [ ] **Aprendizado registrado na issue #196:** quais bancos/formatos apareceram, taxa de
      casamento do CSV genérico, tempo do commit de 40+ linhas. É a entrada da decisão de
      templates da Fase 3 — sem isso, a fase não fecha (roadmap §3).
- [ ] Merge em `develop` (após aprovação) + limpeza da worktree
      (`./scripts/clean-worktrees.sh`, a partir da raiz estável).

---

## Impacto SemVer

**MINOR** — `api-spec/openapi.yaml` ganha capacidade retrocompatível (tipos de arquivo novos,
`force` opcional, `409` novo em endpoint existente). Nada removido ou renomeado; nenhum
cliente existente quebra.

## Riscos e sinais de alerta

| Sinal durante a execução | O que significa | Ação |
|---|---|---|
| Onda 1 exige cirurgia grande no `ImportService` | Algum acoplamento a "1 transação" passou despercebido na Fase 1 | Parar e mapear antes de seguir — é justamente o que a onda existe para revelar |
| Parser OFX ganhando `if` por banco | O subconjunto enxuto não cobre a realidade | Reavaliar `ofx4j` (decisão c da spec), não empilhar remendos |
| CSV genérico casa pouco | Esperado — é o dado que a fase existe para produzir | Registrar na #196 como fila de priorização de templates da Fase 3; usuário nunca fica sem saída (falha explícita + form manual) |
| Commit de 500 itens lento | Volume real batendo no caminho de criação | Otimizar dentro da transação (batch insert); **nunca** quebrar a atomicidade |
