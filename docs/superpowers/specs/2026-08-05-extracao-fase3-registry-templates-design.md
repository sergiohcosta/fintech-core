# Spec: Extração — Fase 3: registry de templates bancários (Itaú fatura + Nubank extrato)

**Data:** 2026-08-05
**Status:** proposto (aguardando aprovação)
**Fonte do produto:** `docs/roadmap-extracao-e-conciliacao.md` — Fase 3 ("PDF, registry de
templates e a camada de cobertura universal")
**Spec anterior:** `docs/superpowers/specs/2026-07-31-extracao-fase3-pdf-texto-design.md`
(Fase 3, fatia 1 — heurística genérica de PDF texto)
**Issue:** nova (a abrir), sub-issue do épico **#176** — Fase 3
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto e escopo

A fatia 1 da Fase 3 (#205) entregou `PdfTextExtractor` com heurística genérica de linha
(data + valor na mesma linha). Testado contra documentos reais (fatura Itaú, extrato PDF
Nubank), a heurística genérica reconhece **zero transações** nos dois formatos:

- **Itaú fatura:** datas sem ano (`28/11`, não `28/11/2025`) — o padrão de data exige ano.
- **Nubank extrato PDF:** data (`05 JUL 2026`) e valor (`+ 4.708,35`) em linhas separadas —
  o padrão exige os dois na mesma linha.

Sem reconhecimento algum, hoje os dois formatos batem no guard-rail "zero transações
aproveitáveis" do `ImportService` → batch `FAILED` → usuário cai no formulário manual.
Nubank **CSV**, ao contrário, já funciona hoje sem mudança nenhuma: os headers reais
(`Data,Valor,Identificador,Descrição`) já batem os sinônimos do `CsvExtractor` — não é
alvo desta spec.

**Escopo desta spec:**
- Interface `PdfBankTemplate` + dois templates concretos: Itaú fatura, Nubank extrato PDF.
- `PdfTextExtractor` tenta os templates (por assinatura de conteúdo) antes da heurística
  genérica; nenhum bate → heurística genérica atual, sem mudança de comportamento.
- Proveniência: `extractor_used` grava o `templateId()` quando um template processa.

**Fora de escopo desta fatia** (§11 detalha): fallback para IA em PDF não reconhecido por
nenhum template nem heurística (depende de PDF→imagem, projeto futuro à parte); validação
soma × total declarado; telemetria por formato; "Lançamentos internacionais" e "produtos e
serviços" do Itaú (formato de linha distinto do corpo principal, baixo volume); CEF (só
existe como print de imagem no caso de uso relatado — pertence a #194, não a templates
de PDF/CSV).

## 2. Decisões arquiteturais

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Forma do registry | Interface Java (`PdfBankTemplate`) + lista de beans Spring ordenada por `@Order`, mesmo padrão do `VisionModelClient` já usado no `VisionExtractor` | JSON declarativo (`detection` + `field_mapping`, roadmap §1.4) — funciona bem para CSV/OFX (contrato de coluna), mas os dois layouts reais aqui exigem lógica (delimitação de seção no Itaú, state machine multilinha no Nubank) que um mapeamento declarativo de campo não expressa |
| b | Onde o template entra no funil | Dentro do `PdfTextExtractor`, tentado **antes** da heurística genérica; nenhum bate → heurística genérica atual, sem regressão | Extrator novo com `@Order` próprio abaixo de 30 — criaria um segundo lugar decidindo "isto é PDF", duplicando o guard-rail de PDF sem texto e o parsing de bytes→texto que já existe |
| c | Detecção (`matches`) | String fixa e praticamente única por banco (CNPJ da instituição + rótulo de seção conhecido), contra o texto completo já extraído | Nome do arquivo — mente com frequência (mesma razão do roteamento por conteúdo do `ExtractionRouter`, nunca por `Content-Type`/nome) |
| d | PDF não reconhecido por nenhum template nem heurística | Continua caindo no guard-rail existente do `ImportService` ("zero transações aproveitáveis" → `FAILED`) — comportamento inalterado desta fatia | Roteirar para `VisionExtractor` — inviável hoje: ele só processa bytes de imagem, não renderiza página de PDF (mesma decisão (c) da spec da fatia 1, ainda válida) |
| e | Ano da data no Itaú (`DD/MM` sem ano) | Inferido da data de vencimento da fatura (aparece uma vez no topo do texto, formato completo `DD/MM/YYYY`) — lançamentos em `DD/MM` cujo mês é maior que o mês de vencimento pertencem ao ano anterior (fatura fecha ~1 mês antes do vencimento, corpo cobre o mês anterior) | Assumir sempre o ano do vencimento — quebra em fatura de janeiro com lançamento de dezembro do ano anterior (caso real e comum) |
| f | Direção no Nubank extrato | Rastreada pela seção corrente (`"Total de entradas"` → credit, `"Total de saídas"` → debit) até a próxima ocorrência de qualquer um dos dois rótulos | Palavra-chave por linha (`débito`/`crédito`) — o extrato Nubank não usa essas palavras; rótulos como "Resgate RDB" aparecem tanto em entradas quanto em saídas, então só a seção corrente desambigua |

**(a) Interface Java, não JSON declarativo.**
O roadmap (§1.4) desenha o registry como config declarativa (`field_mapping` por coluna) —
isso resolve bem CSV/OFX porque a estrutura de origem já é colunar. Texto de PDF não tem
coluna: o Itaú precisa de uma regra de "onde a seção de lançamentos começa e termina"
(senão duplica parcelas futuras como transação do mês), e o Nubank precisa de um estado
(seção corrente = entradas/saídas) carregado entre linhas. Nenhuma das duas cabe num mapa
campo→coluna. Uma interface Java com `matches()`/`parse()` é o menor código que expressa
isso — mesmo trade-off já aceito no projeto para o `VisionModelClient` (porta Java, não
config, porque a lógica por trás de cada provider não é so mapeamento de campo).

**(b) Dentro do `PdfTextExtractor`, não extrator novo.**
Ambos os alvos desta fatia (Itaú, Nubank) SÃO PDF — a decisão "isto é PDF, tem camada de
texto, vamos tentar reconhecer" já existe e é boa. Duplicar isso num extrator separado só
para hospedar os templates criaria dois lugares fazendo a mesma pergunta de baixo nível
(magic number, `PDFTextStripper`, guard-rail de PDF escaneado) — reuso, não abstração nova.

**(c) Detecção por CNPJ + rótulo de seção.**
CNPJ da instituição financeira é um identificador legal único, estável (não muda com
rebranding cosmético do documento) e aparece literalmente no texto de qualquer fatura/
extrato desses bancos por exigência regulatória. Combinado com um rótulo de seção
conhecido (`"Lançamentos: compras e saques"`, `"Movimentações"`) reduz a chance de colisão
a praticamente zero sem precisar de heurística de layout.

**(d) Sem fallback para IA nesta fatia.**
Mantém a decisão já tomada na fatia 1 (spec anterior, decisão c): `VisionExtractor` não
sabe processar bytes de PDF. Construir isso agora inflaria o escopo para o tamanho de um
projeto à parte (PDF→imagem + prompt multi-transação, que é literalmente a próxima fatia
da Fase 3 e o corpo de #194). Documentos que não batem template nem heurística seguem no
caminho já existente: falha explícita, formulário manual.

**(e) Ano inferido da data de vencimento.**
A fatura Itaú declara `Data de Vencimento` uma vez, no cabeçalho, sempre com ano completo.
O corpo de lançamentos cobre o período do ciclo anterior (fecha ~1 mês antes do
vencimento) — por isso um lançamento com mês **maior** que o mês de vencimento pertence ao
ano anterior (ex.: fatura vence em `10/03/2025`, lançamento `28/11` é `28/11/2024`).
Regra: `ano = anoVencimento - (mesLancamento > mesVencimento ? 1 : 0)`.

**(f) Direção pela seção corrente no Nubank.**
Único sinal confiável no extrato: cada dia agrupa suas movimentações sob
`"Total de entradas"` (subtotal, não é uma transação em si) seguido das linhas de entrada,
depois `"Total de saídas"` seguido das linhas de saída. Rótulos de transação (`"Resgate
RDB"`, `"Transferência recebida/enviada pelo Pix"`) se repetem nos dois lados — não dá pra
inferir direção pelo rótulo isolado.

## 3. Invariante inviolável — isolamento de tenant

Nenhuma mudança no modelo estabelecido. `PdfBankTemplate` recebe só o texto já extraído do
PDF (`String`) e devolve `List<NormalizedTransactionDTO>` — não conhece tenant, não toca
banco. Mesma fronteira do `PdfTextExtractor` (spec anterior, §3).

## 4. Modelo de dados

**Nenhuma migration nova.** `extractor_used` já é `VARCHAR` livre (grava hoje valores como
`pdf_text_v1`); passa a gravar `itau_fatura_v1`/`nubank_extrato_v1` quando um template
processa, e `pdf_text_v1` quando cai na heurística genérica (comportamento atual mantido).
`extractor_provider`/`extractor_model` (V28) não se aplicam — são específicos da cadeia de
visão; ficam `NULL` para PDF, igual hoje.

## 5. Contrato de API

**Nenhuma mudança.** `POST /api/imports` já aceita PDF desde a fatia 1; o registry é
transparente para o cliente — mesmo endpoint, mesma resposta, só varia `extractor_used` no
detalhe do batch (já exposto).

## 6. Fluxo

### 6.1 Interface

```java
interface PdfBankTemplate {
    boolean matches(String fullText);
    List<NormalizedTransactionDTO> parse(String fullText);
    String templateId(); // "itau_fatura_v1", "nubank_extrato_v1"
}
```

`PdfTextExtractor` recebe `List<PdfBankTemplate> templates` no construtor (mesmo padrão de
injeção do `VisionExtractor`/`List<VisionModelClient>`), ordenados por `@Order` nos beans
concretos.

### 6.2 Roteamento dentro de `extract()`

```
texto extraído (já existe, §6.2 da spec anterior)
  ↓
para cada template em ordem:
    template.matches(texto)?
      sim → transactions = template.parse(texto); extractorUsed = template.templateId(); para
      não → próximo template
  ↓ nenhum bateu
heurística genérica de linha (comportamento atual, inalterado)
  ↓
guard-rail "zero transações aproveitáveis" do ImportService (inalterado)
```

`matches()` roda sobre o texto já extraído (nenhuma leitura adicional do PDF) — custo
desprezível mesmo com N templates.

### 6.3 `ItauFaturaTemplate`

1. `matches`: texto contém `"60.872.504/0001-23"` (CNPJ Itaú Unibanco Holding) **e**
   `"Lançamentos: compras e saques"`.
2. Extrai `anoVencimento`/`mesVencimento` da primeira ocorrência de
   `"Data de Vencimento"`/campo equivalente com data `DD/MM/YYYY` completa no cabeçalho.
3. Localiza os blocos delimitados por `"Lançamentos: compras e saques"` (pode se repetir —
   um por titular de cartão adicional) até o próximo cabeçalho de seção conhecido
   (`"Compras parceladas - próximas faturas"`, `"Limites de crédito"`,
   `"Encargos cobrados"`, `"Lançamentos internacionais"`, `"Lançamentos: produtos e
   serviços"`) — só o texto **dentro** desses blocos vira transação.
4. Por linha dentro do bloco: regex `DD/MM <estabelecimento> [NN/NN] <valor>` — o grupo
   opcional `NN/NN` (marcador de parcela, ex. `04/06`) é descartado, não tratado como data.
   `amount`/`transaction_date` confiança `1.0` (padrão do template bateu exatamente);
   `description` = estabelecimento (confiança `0.9` — nome literal do template, não
   inferência posicional como na heurística genérica); `direction` = pelo sinal do valor
   capturado (positivo = `debit`, negativo = `credit`) — cobre estorno pontual (ex.:
   `"ESTORNO DE ANUIDADE DIF -29,50"`), que é real e aparece no corpo de "compras e
   saques" mesmo sendo um abatimento, não uma compra.
5. Data final = `LocalDate.of(mesLancamento > mesVencimento ? anoVencimento - 1 :
   anoVencimento, mesLancamento, diaLancamento)`.

### 6.4 `NubankExtratoTemplate`

1. `matches`: texto contém `"18.236.120/0001-58"` (CNPJ Nu Pagamentos) **e**
   `"Movimentações"`.
2. State machine linha a linha, dentro da seção `"Movimentações"`:
   - linha bate `DD MES_PT YYYY` (`MES_PT` = JAN..DEZ) → data corrente = essa data; zera
     acumulador de descrição.
   - linha começa com `"Total de entradas"` → direção corrente = `credit`; não gera
     transação (é subtotal do dia).
   - linha começa com `"Total de saídas"` → direção corrente = `debit`; não gera
     transação.
   - linha termina em valor monetário reconhecível (mesmo padrão da heurística genérica) →
     fecha uma transação: `description` = acumulado + trecho da linha antes do valor
     (confiança `0.8` — rótulo + contraparte, concatenação determinística, não inferência
     de posição solta); `amount`/`transaction_date` confiança `1.0`; `direction` = a
     corrente (confiança `1.0` — vem da seção, não de sinal ambíguo).
   - qualquer outra linha não vazia → acumula como continuação da descrição (contraparte
     multilinha).
3. Datas completas desde o início (`DD MES_PT YYYY`) — sem ambiguidade de ano como no
   Itaú.

### 6.5 Guard-rails e dedup — sem mudança

Nenhum guard-rail novo. Guard-rail de `max-transactions`, data/valor implausível e dedup
por trio `(data, valor, descrição)` do `ImportService` seguem valendo — um template com bug
que gera lixo cai nos mesmos guard-rails que a heurística genérica sempre teve.

## 7. Frontend

**Nenhuma mudança.** Upload, revisão e commit já processam qualquer batch PDF desde a
fatia 1; o registry só muda `extractor_used` no detalhe, já exibido sem tratamento
especial por template.

## 8. Testes

| Camada | Cobertura |
|---|---|
| `ItauFaturaTemplate` (puro) | `matches()` positivo/negativo (CNPJ presente/ausente); parse de fixture sintética multi-linha reconhece transações dentro da seção e ignora "Compras parceladas - próximas faturas"; virada de ano (lançamento em mês > mês de vencimento) |
| `NubankExtratoTemplate` (puro) | `matches()` positivo/negativo; parse de fixture sintética com entrada de linha única (`"Resgate RDB 4.708,35"`) e entrada multilinha (contraparte quebrada em 2+ linhas antes do valor); direção correta em `"Total de entradas"` vs `"Total de saídas"`; `"Total de X"` não vira transação |
| `PdfTextExtractor` (integração dos templates) | template bate → `extractorUsed` = `templateId()`; nenhum template bate → heurística genérica atual (regressão zero, reusa fixtures da fatia 1) |
| `ImportService` | batch via template segue o mesmo caminho de staging/commit/dedup que a heurística genérica |

Fixtures em `backend/src/test/resources/imports/` (`itau_fatura_sintetica.pdf`,
`nubank_extrato_sintetico.pdf`) — **texto sintético representativo do layout, nunca extrato
real de usuário**: nomes/valores/CPF fictícios, gerados via PDFBox no próprio teste ou
fixture pré-gerada. Os PDFs reais usados para validar esta spec ficam fora do controle de
versão, só na máquina local de quem validou.

## 9. Dataset de testes

Feature de backend sem tabela nova — sem obrigação de seed. Mesmo raciocínio da spec
anterior (§9): avaliar durante a execução se vale um batch de exemplo no seed `dev`, sem
bloquear o design. Se entrar, usa texto sintético (nunca dado real).

## 10. Critérios de saída

- [ ] Fatura Itaú real (formato validado nesta spec) processada ponta a ponta via
      `ItauFaturaTemplate`: upload → staged com transações corretas → commit.
- [ ] Extrato PDF Nubank real processado ponta a ponta via `NubankExtratoTemplate`.
- [ ] Parcelas futuras da fatura Itaú (seção "Compras parceladas - próximas faturas") **não**
      viram transação do batch.
- [ ] Direção correta em 100% das transações do extrato Nubank testado (entradas =
      credit, saídas = debit).
- [ ] Zero regressão: PDFs que hoje caem na heurística genérica (fatia 1) continuam
      caindo nela, sem mudança de resultado.
- [ ] Zero regressão nos demais extratores (OFX, CSV, imagem).

## 11. Fora de escopo

- **Fallback para IA em PDF não reconhecido por template nem heurística** — depende de
  PDF→imagem (fatia futura da Fase 3, mesmo requisito da "PDF escaneado via visão").
- **Validação soma × total declarado** — ainda não implementada para nenhum extrator;
  candidata natural para quando o volume de templates justificar (roadmap §1.5).
- **Telemetria por formato/template** (taxa de casamento, drift) — fatia própria da Fase 3.
- **"Lançamentos internacionais" e "produtos e serviços" do Itaú** — formato de linha
  distinto do corpo principal (colunas US$/R$ ou linha sem estabelecimento explícito),
  baixo volume observado; podem ser adicionados numa fatia posterior do mesmo template sem
  mudar a interface.
- **CEF** — só existe como print de extrato (imagem), não PDF/CSV; pertence a #194
  (multi-transação por imagem única), fora do escopo de registry de templates PDF/CSV.
- **Outros bancos** — registry cresce por adição de novo `PdfBankTemplate`, sem tocar nos
  existentes; novos bancos entram quando houver amostra real, não especulativamente.

## 12. Riscos

| Risco | Mitigação |
|---|---|
| Layout do Itaú/Nubank muda (banco redesenha fatura/extrato) | `matches()` para de bater (CNPJ + rótulo de seção são estáveis, mas se quebrar) → cai automaticamente na heurística genérica, sem exceção nem batch `FAILED` a mais — degrada para o comportamento de hoje, não quebra |
| Regra de virada de ano do Itaú (decisão e) errada num caso de borda (fatura de fechamento atípico) | Coberto por teste dedicado da virada de ano; falha vira data errada revisável pelo usuário na tela de revisão (staged, não commitada automaticamente) |
| `NubankExtratoTemplate` perde uma transação por bug na state machine (ex. linha de continuação mal reconhecida) | Guard-rail de `max-transactions`/valor implausível do `ImportService` não pega "transação faltando" — mitigação real é teste de fixture cobrindo o caso multilinha explicitamente, não guard-rail em runtime |
| Falso positivo de `matches()` (outro documento com o mesmo CNPJ por coincidência, ex. um boleto do próprio banco não relacionado a fatura/extrato) | Baixo risco — `matches()` exige CNPJ **e** rótulo de seção específico (`"Lançamentos: compras e saques"`/`"Movimentações"`), não só o CNPJ isolado |

## 13. Impacto SemVer

**PATCH** — nenhuma mudança de contrato (`api-spec/openapi.yaml` inalterado). Melhora
interna de taxa de reconhecimento para dois bancos; `extractor_used` já era campo livre,
novo valor não quebra nenhum cliente.

## 14. Ordem de execução sugerida

1. **Interface `PdfBankTemplate`** + `PdfTextExtractor` passa a receber `List<PdfBankTemplate>`
   vazia (nenhum bean ainda) — prova que o roteamento novo não regride a heurística
   genérica existente (fixtures da fatia 1 continuam verdes).
2. **`ItauFaturaTemplate`** + fixture sintética + testes (matches, parse, virada de ano,
   exclusão de "Compras parceladas").
3. **`NubankExtratoTemplate`** + fixture sintética + testes (matches, parse, multilinha,
   direção por seção).
4. **Teste de integração** dos dois templates dentro do `PdfTextExtractor` (extractorUsed
   correto) + regressão da heurística genérica.
5. **Documentação**: `summary.md` (seção de Importação — menção ao registry),
   `docs/roadmap-extracao-e-conciliacao.md` (marcar entrega parcial da Fase 3 — "registry
   de templates para os 2-3 bancos principais").

Passo 1 isola o roteamento (lista vazia de templates = comportamento idêntico a hoje)
**antes** de qualquer lógica de template entrar — mesmo raciocínio da spec anterior (passo
2 lá): se o roteamento e o parsing quebrarem juntos, não dá pra saber qual foi.
