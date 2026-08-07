# Spec: split de coluna dinâmico na fatura Itaú

**Data:** 2026-08-07
**Status:** proposto (aguardando aprovação)
**Fonte:** validação manual contra fatura real em prod — total impresso R$15.860,53,
sistema importou só R$3.326,53 (43 de ~90+ transações esperadas), sem erro nem aviso.
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto

`ItauFaturaTemplate` separa a página em duas colunas de lançamentos via
`PDFTextStripperByArea` com um corte fixo em `COLUMN_SPLIT_X = 365f` — constante medida
contra UM documento real anterior (comentário no código já avisava: "não há segundo
exemplar de fatura pra validar se varia entre documentos").

Nesta fatura real (multi-titular, 2 portadores), o cabeçalho `Lançamentos: compras e
saques` da coluna direita começa em X≈351–358 (varia por página) — **antes** do corte em
365. O texto do cabeçalho é cortado ao meio pela extração por região: a coluna direita
recebe só o sufixo (`çamentos: compras e saques`), a busca por string exata nunca acha, e
o bloco inteiro da coluna direita — a maior parte do valor da fatura — é descartado em
silêncio. Confirmado por análise manual, independente do sistema (extração PDFBox própria,
sem passar pelo `ItauFaturaTemplate`): só 43 linhas reconhecidas somando R$3.326,53.

O guard de observabilidade existente (`log.warn` quando uma coluna tem linhas no formato
`DD/MM ... valor` mas zero transações reconhecidas) **não pegou o caso**: a corrupção do
split é severa o bastante pra que nem as linhas de transação da coluna direita sobrevivam
reconhecíveis como `DD/MM ...` — a condição do guard nunca fica verdadeira.

## 2. Decisões

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Onde fixar o corte de coluna | Detecção dinâmica por página: maior vão horizontal entre extents de texto vira o corte | Manter constante fixa e só recalibrar o valor — resolve esta fatura, quebra na próxima com layout diferente (mesma classe de bug) |
| b | Escopo desta entrega | Só o fix do split de coluna | Guard de soma × total declarado na fatura (ver §6) — investigação mostrou que o único rótulo textual candidato a âncora (`Lançamentos no cartão`) é ambíguo (aparece em contextos diferentes, não é 1-por-titular como parecia à primeira vista) e os números não fecharam numa tentativa de reconciliação manual. Construir um guard sobre uma âncora não verificada arrisca ficar "confiantemente errado" — pior que não ter guard nenhum |

**(a) Detecção dinâmica, não recalibração.** O problema não é o valor 365 estar errado —
é que QUALQUER constante fixa quebra assim que um documento tiver layout um pouco
diferente (múltiplos titulares, versão de template do banco, etc.). Detectar o vão real
por página generaliza sem precisar de um segundo exemplar pra calibrar.

**(b) Escopo mínimo, alta confiança.** O bug do split é confirmado e provado (extração
independente, fora do sistema, reproduz o problema e a causa exata). O guard de soma exigia
confiar num rótulo textual (`Lançamentos no cartão`) cuja semântica não ficou clara com um
único documento real disponível — mesma armadilha que o roadmap já documentou
("Correção pós-entrega, fatia 2": calibrar contra 1 documento não generaliza). Registrado
como dívida técnica (§6), não descartado — só adiado até haver mais de um documento real
pra calibrar o âncora certo.

## 3. Modelo de dados / Contrato de API

Nenhuma mudança — `ItauFaturaTemplate` é implementação interna, não altera contrato REST
nem schema. **SemVer PATCH** (correção de bug, sem mudança de contrato).

## 4. Fluxo

### 4.1 Detecção dinâmica de coluna (`ItauFaturaTemplate`)

Por página, em vez de duas regiões fixas:

1. Extrai todas as posições X de início/fim de cada trecho de texto da página (via hook de
   `PDFTextStripper.writeString`, não `PDFTextStripperByArea` — esta última já assume as
   regiões prontas, não serve pra descobrir onde elas deveriam estar).
2. Ordena os extents (`xStart`, `xEnd`) por `xStart`.
3. Acha o maior vão (`gap = próximoXStart − xEndAtual`) entre extents consecutivos, dentro
   da faixa `[margem esquerda, largura da página − margem direita]` — ignora vãos na borda
   (não é gutter entre colunas, é margem da página).
4. Corte = ponto médio do maior vão encontrado.
5. Com o corte calculado (por página), reusa `PDFTextStripperByArea` com duas regiões
   (mesma mecânica atual) pra extrair o texto de cada coluna — o valor do corte passa a
   variar por página, em vez de ser uma constante global fixa pro documento inteiro.

Página sem vão significativo (layout de coluna única, ex. a folha de resumo/capa) → sem
gap claro pra detectar, região direita fica vazia — comportamento já tolerado hoje pelo
loop de blocos (que simplesmente não acha `HEADER_LANCAMENTOS` numa coluna vazia, sem
lançar exceção).

**Falha ao detectar QUALQUER vão na página inteira** (todo o texto concentrado, sem gap
algum) é diferente de "página de coluna única" — nesse caso extremo, cai pra uma coluna só
(região direita vazia), mesmo comportamento do caso anterior. Não há necessidade de um
terceiro caminho: ambos os cenários (coluna única real, ou detecção falhando) produzem o
mesmo resultado seguro (não perde dado da região que TEM conteúdo, só não separa duas
colunas onde não havia duas colunas de verdade pra separar).

## 5. Testes

| Camada | Cobertura |
|---|---|
| `ItauFaturaTemplateTest` | fixture sintética com 2 colunas onde o cabeçalho da direita começa ANTES de onde um corte fixo ingênuo cairia (reproduz o bug exato encontrado) — extração dinâmica reconhece ambas as colunas corretamente |
| `ItauFaturaTemplateTest` | fixture de página com 1 coluna só (sem vão significativo) não quebra — só não processa a região vazia, resultado idêntico ao de hoje pra documentos de coluna única |
| `ItauFaturaTemplateTest` | fixture com vão em posição diferente da fatura de calibração original (prova que não há mais dependência de constante fixa) |

Fixtures sintéticas — mesmo padrão já usado (nomes/valores fictícios, nunca dado real
desta investigação).

## 6. Impacto SemVer

**PATCH** — correção de bug em implementação interna, nenhuma mudança de contrato REST,
nenhuma migration.

## 7. Dívida técnica registrada

- **Guard de soma × total declarado na fatura** (adiado desta entrega, §2b): validaria a
  soma das transações extraídas contra um total impresso no documento, pegando esta classe
  de bug automaticamente em vez de depender de auditoria manual. Precisa de mais de um
  documento real do Itaú pra identificar com segurança qual rótulo textual serve de âncora
  confiável (candidato investigado, `Lançamentos no cartão`, mostrou ambiguidade de
  contexto — não é seguro assumir 1 ocorrência por titular sem mais evidência).
- Página de layout de coluna única (resumo, capa) não tem vão detectável — comportamento
  correto por construção (não há lançamento pra perder ali), mas sem teste explícito
  cobrindo esse caso combinado com "detecção de vão falhou por outro motivo" (documento
  raro, praticamente impossível de diferenciar dos dois cenários com dado real disponível
  hoje).
