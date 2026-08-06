# Spec: Correção — templates PDF Itaú/Nubank contra ordem real do PDFBox

**Data:** 2026-08-06
**Status:** proposto (aguardando aprovação)
**Fonte do bug:** teste em ambiente dev com fatura Itaú e extrato Nubank reais, após o
merge de `docs/superpowers/specs/2026-08-05-extracao-fase3-registry-templates-design.md`
**Issue:** nova (a abrir)
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
Stack: @tech.md · Domínio: @domain.md

## 1. Contexto — o que quebrou e como foi investigado

O registry de templates (Itaú fatura + Nubank extrato) foi validado só contra fixtures
sintéticas de uma linha — nenhuma delas conseguia expor um problema que só existe em
documento real, multi-página, com layout de coluna. Testado contra os dois arquivos reais
que motivaram a feature em ambiente dev (`/api/imports`), os dois templates produzem
**dado financeiro errado** (não falha explícita — dado errado silencioso, o pior caso
possível segundo o próprio roadmap: *"erro explícito > erro silencioso"*).

**Investigação (systematic-debugging, Fase 1 — causa raiz antes de qualquer fix):**
extraído o texto bruto dos dois PDFs reais com a MESMA versão do PDFBox usada em produção
(`3.0.7`, `setSortByPosition(true)`), fora do pipeline, para ver a ordem exata linha a
linha — sem chutar a partir da renderização visual.

### 1.1 Itaú — causa raiz: duas tabelas renderizadas lado a lado na MESMA linha de texto

A fatura Itaú tem duas colunas de lançamentos lado a lado por boa parte do documento. O
`PDFTextStripper` (mesmo com `setSortByPosition`) agrupa texto por proximidade de Y, não
por coluna — então uma linha física da coluna esquerda e a linha da coluna direita **na
mesma altura** viram UMA linha de texto:

```
28/11 Foco Aluguel de Ca04/06 112,67 07/02 BeneficiarioTeste 36,00
```

(duas transações reais distintas, fundidas). O `ItauFaturaTemplate` atual (regex de UMA
data + UM valor por linha, âncora `^...$`) pega a primeira data e o **último** valor da
linha → mistura a data de uma transação com o valor de outra. **Não é caso de borda: a
fatura inteira segue esse padrão**, então a maioria das 47 transações reconhecidas no teste
real veio com valor errado.

Mais grave ainda: a partir de certo ponto do documento, a coluna direita deixa de ser
"compras e saques" e passa a ser uma tabela **diferente** simultânea (`Lançamentos
internacionais` → `produtos e serviços` → `Compras parceladas - próximas faturas` →
`Limites de crédito`), enquanto a coluna esquerda **continua** sendo lançamentos reais de
outro titular de cartão adicional. Delimitar por posição de caractere no texto linear
fundido (a abordagem da spec anterior) não consegue separar isso — cortar no primeiro
marcador de "Compras parceladas" perde transações reais que vêm depois (coluna esquerda
continuando); não cortar mistura parcela futura como se fosse do ciclo atual.

**Confirmado por posição real (`TextPosition.getXDirAdj()`)**, página 2 do PDF real:

| texto | X | Y |
|---|---|---|
| `Foco` | 178.2–192.6 | 203.3 |
| `112,67` | 319.3–340.2 | 203.3 |
| `BeneficiarioTeste` | 394.2–435.7 | 203.3 |
| `36,00` | 539.2–556.2 | 203.3 |

Gap claro entre colunas em torno de x≈365 (MediaBox da página: 595.28 × 841.89pt, A4).

### 1.2 Nubank — causa raiz: ordem assumida estava invertida

A spec anterior assumiu "o valor fecha um bloco multilinha, aparecendo sozinho na última
linha". A ordem real do PDFBox é o oposto: o valor vem **grudado na própria linha do
rótulo** (label + início da contraparte + valor, tudo numa linha só); as linhas seguintes
(complemento de agência/conta) são só decoração, sem valor, e nunca fecham nada de útil.
Conferido em **23 de 23** transações reais do extrato — zero exceção:

```
Transferência recebida pelo Pix FULANO DE TAL - •••.000.000-•• - CAIXA 151,91   ← já fecha aqui
ECONOMICA FEDERAL (0104) Agência: 7904 Conta:                                            ← decoração, ignorável
0000000000000000000-0                                                                    ← decoração, ignorável
```

O `NubankExtratoTemplate` atual continua acumulando linhas de decoração esperando um valor
que já passou — quando não aparece outro valor por várias linhas (ex.: quebra de página com
rodapé legal + cabeçalho da página seguinte no meio), tudo isso vira parte da `description`
de uma transação, ou pior, se um rodapé tivesse por acaso um padrão de valor, viraria
transação fantasma (risco já documentado e estacionado na spec anterior — **este teste real
confirma que o vazamento de rodapé acontece de fato**, não é só teórico).

## 2. Decisões de correção

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Itaú — separar as colunas | `PDFTextStripperByArea` com 2 regiões retangulares (esquerda `x∈[0,365]`, direita `x∈[365,595.28]`), extraídas por página e concatenadas por coluna antes do parsing | Clustering manual de `TextPosition` por gap de X (mais código, mesmo resultado — `PDFTextStripperByArea` já resolve isso, é API padrão do PDFBox) |
| b | Itaú — parsing por coluna | Reusa a MESMA lógica já existente (delimitação por `HEADER_LANCAMENTOS`/`STOP_MARKERS` + regex de 1 data+valor por linha), agora rodando **separadamente sobre cada stream de coluna já limpo** | Reescrever o parsing do zero — o bug nunca foi a lógica de linha, foi a fusão de colunas antes dela rodar |
| c | Nubank — fechamento de transação | Fecha assim que a PRÓPRIA linha tiver um valor reconhecível (label + valor na mesma linha); linha sem valor é sempre descartada, nunca acumulada | Manter acumulador multilinha "até achar valor" — não corresponde a nenhum caso real observado (0/23) e é a causa direta do vazamento de rodapé |
| d | Split de coluna do Itaú fixo em x=365pt | Aceito como constante do template (mesmo espírito de "template = cache compilado" do roadmap — específico do gerador de layout do Itaú, não genérico) | Calcular o gap dinamicamente por página (mais robusto a variação futura, mas sem segundo exemplar de fatura Itaú pra validar se o gap muda; adiado até haver evidência de que x=365 quebra) |

**(a) `PDFTextStripperByArea`, não clustering manual.** É a API do próprio PDFBox feita
exatamente para este problema (extrair texto de regiões retangulares independentes de uma
página) — usar `TextPosition` cru pra reimplementar isso seria reinventar uma engine já
testada pela própria lib. Validado empiricamente contra o PDF real antes de virar código:
região esquerda devolve só "Foco Aluguel de Ca04/06 112,67", região direita só "07/02
BeneficiarioTeste 36,00" — nenhuma mistura.

**(b) Reusa a lógica de linha existente.** O bug nunca esteve no regex de reconhecimento
de transação (`DD/MM estabelecimento [NN/NN] valor`) — esse sempre funcionou certo quando
a linha tinha UMA transação. O bug era a fusão de colunas ACONTECENDO ANTES da regex rodar.
Corrigindo a entrada (texto já separado por coluna), a lógica de saída (regex, inferência de
ano, delimitação de seção) continua válida sem mudança de forma — só passa a rodar 2 vezes
(uma por coluna) em vez de 1, com os resultados unidos.

**(c) Nubank fecha na própria linha.** Evidência real (100% dos casos) mostra que o valor
NUNCA aparece isolado numa linha própria depois de várias linhas de contraparte — ele está
sempre grudado na primeira linha do rótulo. Simplifica o parser: não precisa mais de
acumulador multilinha esperando fechamento; qualquer linha sem valor é ruído descartável
por definição, o que elimina o vazamento de rodapé por construção (rodapé nunca fecha nada
porque nunca é acumulado).

## 3. Modelo de dados / Contrato de API

Nenhuma mudança. Mesmo schema (`NormalizedTransactionDTO`), mesmo `templateId()` por
template, nenhuma migration, nenhuma mudança de contrato.

## 4. Fluxo

### 4.1 `ItauFaturaTemplate.parse()` — novo

```
1. Localizar mesVencimento/anoVencimento (igual hoje)
2. Para cada página do documento:
     extrair região esquerda (x∈[0,365]) → texto da coluna esquerda desta página
     extrair região direita (x∈[365,595.28]) → texto da coluna direita desta página
3. Concatenar todas as páginas: textoEsquerda (todas as páginas), textoDireita (todas as páginas)
4. Para CADA um dos dois streams (esquerda, direita), independentemente:
     localizar TODOS os blocos "Lançamentos: compras e saques" (podem repetir)
     cada bloco delimitado até o STOP_MARKER mais próximo (ou fim do stream)
     dentro do bloco: 1 linha = 1 transação (regex já existente, sem mudança)
5. União das transações das duas colunas
```

Requer mudar a ENTRADA do `PdfBankTemplate.parse(String fullText)` — hoje o template só
recebe o texto já achatado pelo `PdfTextExtractor`. Duas opções:
- **(i)** `PdfTextExtractor` passa a extrair o `PDDocument` bruto pro template decidir como
  ler (quebra a assinatura atual `String → List<...>` pra `PDDocument → List<...>`, ou uma
  variante).
- **(ii)** `ItauFaturaTemplate` recebe os BYTES do PDF (não o texto já extraído) e faz sua
  própria extração posicional internamente, ignorando o `fullText` que o `PdfTextExtractor`
  já calculou para a heurística genérica.

Escolha: **(ii)** — menor mudança de interface. `PdfBankTemplate.parse` passa a receber
`(String fullText, byte[] content)` — o `fullText` genérico continua disponível pra
templates que não precisam de posição (Nubank não precisa), e `content` fica disponível
pros que precisam reabrir o PDF com `PDFTextStripperByArea` (Itaú). `matches()` continua
recebendo só `fullText` (detecção não precisa de posição).

### 4.2 `NubankExtratoTemplate.parse()` — simplificado

```
dataCorrente = null, direcaoCorrente = null
para cada linha do texto (a partir de "Movimentações"):
  se bate DD MES_PT YYYY → dataCorrente = data; processa o resto da linha recursivamente
  senão se começa com "Total de entradas" → direcaoCorrente = credit
  senão se começa com "Total de saídas" → direcaoCorrente = debit
  senão se a linha TEM um valor reconhecível → fecha transação (data corrente, direção
        corrente, description = prefixo antes do valor, valor) — SEM tocar acumulador
  senão → ignora a linha (não acumula mais nada)
```

Remove o `StringBuilder acumulador` inteiro — não é mais necessário.

## 5. Testes

| Camada | Cobertura nova |
|---|---|
| `ItauFaturaTemplateTest` | fixture com DUAS transações na mesma linha (Y igual, X diferente) simulando a fusão real — PDF sintético com 2 blocos de texto na mesma altura, X diferentes; confirma 2 transações corretas, não 1 misturada |
| `ItauFaturaTemplateTest` | página com coluna direita em seção diferente ("Compras parceladas") enquanto a esquerda continua "compras e saques" — confirma que a esquerda não é perdida |
| `NubankExtratoTemplateTest` | linha "ruído" (sem valor) entre duas transações reais não vaza pra descrição de nenhuma delas |
| `NubankExtratoTemplateTest` | linha de rodapé/legal (sem valor, texto longo) entre duas transações não interfere em nenhuma — substitui o teste anterior que validava (incorretamente) o comportamento de acumulador |
| Regressão | `ItauFaturaTemplateTest`/`NubankExtratoTemplateTest` existentes ajustados onde a fixture não reflete mais a ordem real (ex.: teste de multilinha do Nubank que assumia valor no fim) |

**Fixtures sintéticas com posição controlada** (Itaú): PDFBox permite posicionar texto via
`cs.newLineAtOffset(x, y)` — a fixture do teste de fusão de coluna usa 2 chamadas de
`showText` na MESMA linha Y, X diferentes, pra reproduzir exatamente o cenário real sem
precisar de um PDF real.

## 6. Fora de escopo

- Split de coluna dinâmico por página (decisão d) — fixo em x=365pt até haver evidência de
  quebra com outra fatura real.
- Qualquer coisa já listada como fora de escopo na spec anterior (fallback IA, soma×total,
  outros bancos, CEF).

## 7. Impacto SemVer

**PATCH** — correção de bug, sem mudar contrato.
