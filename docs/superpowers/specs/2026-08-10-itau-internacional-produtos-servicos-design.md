# Spec: extração de "Lançamentos internacionais" e "produtos e serviços" (Itaú)

**Data:** 2026-08-10
**Status:** proposto (aguardando aprovação)
**Origem:** dívida técnica registrada na spec `2026-08-09-itau-fatura-ancora-por-documento-design.md`
(§7): após corrigir o roteamento de fatura, restou uma diferença de R$120,66 entre o total
impresso na fatura de referência (R$15.860,53) e o importado (R$15.739,87) — vinda de duas
seções que o `ItauFaturaTemplate` nunca leu.
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto

### 1.1 O que falta

`ItauFaturaTemplate` só lê a seção "Lançamentos: compras e saques". Duas outras seções
existem no PDF e nunca são processadas:

- **Lançamentos internacionais** — compras em moeda estrangeira, com conversão e IOF.
- **Lançamentos: produtos e serviços** — taxas e serviços do próprio cartão (anuidade
  diferenciada, envio de mensagem automática, encargos de atraso).

### 1.2 Levantamento no corpus (21 faturas modernas, formato atual)

| Seção | Presente em |
|---|---|
| `produtos e serviços` (com transação real) | **21/21 (100%)** |
| `Lançamentos internacionais` | 9/21 (43%) |

`produtos e serviços` é a seção mais valiosa — aparece em toda fatura, não só ocasionalmente.

### 1.3 Formato real medido

**A infraestrutura de coluna já existente resolve o problema de ruído.** Rodei
`detectColumnSplit` (já em produção, corrige a separação de coluna desde a spec
`2026-08-07-itau-ancora-coluna-por-data`) contra a fatura de referência: dentro da coluna já
separada, as duas seções aparecem **limpas**, sem o vazamento de caixas laterais
("Novo teto de juros", "Simulação de Compras parc...", etc.) que aparece no texto bruto não
dividido. Os dois cabeçalhos ("Lançamentos internacionais", "Lançamentos: produtos e
serviços") **já estão** na lista `STOP_MARKERS` — hoje só usados pra fechar o bloco de
compras e saques, nunca processados como conteúdo.

**Produtos e serviços** — bloco de 1 ou 2 linhas por item, medido em 5 faturas de datas
diferentes:

```
04/07 ANUIDADE DIFER 03/12 62,00       ← linha 1: DD/MM CÓDIGO [NN/NN] VALOR
Anuidade Diferenciada                   ← linha 2 (opcional): descrição completa

03/01 ENVIOMENS.AUTOMATICA 7,49         ← linha única, sem continuação
03/01 ENCARGOS DE ATRASO 51,26          ← linha única, sem continuação

03/01 ESTORNO DE ANUIDADE DIF - 29,50   ← valor negativo = estorno
Adicional 6752
```

O marcador de parcela (`NN/NN`) aparece colado ou espaçado ao código, formato inconsistente
entre faturas (`ANUIDADE DIFER 03/12` vs `ANUIDADE DIFERENCI08/12`) mas sempre reconhecível
pelo mesmo padrão `\d{2}/\d{2}` já usado em compras e saques.

**Internacional** — formato mais frágil:

```
Lançamentos internacionais
SERGIO HENRIQUE COSTA
DATA ESTABELECIMENTO US$ R$
18/07 ANTHROPIC* CLAUDE SUBSA 116,58
110,00 BRL 21,51
Dólar de Conversão R$ 5,42
Total transações inter. em R$ 116,58
Repasse de IOF em R$ 4,08
Total lançamentos  inter. em R$ 120,66
```

O valor em US$ e a taxa de conversão vêm quebrados numa segunda linha sem correspondência
clara campo a campo — parsear por transação exigiria inferência sobre um formato validado em
só 9 faturas do corpus. A linha de subtotal (`Total lançamentos inter. em R$`) já soma tudo
certo, incluindo IOF (`116,58 + 4,08 = 120,66`, confere com o valor impresso).

## 2. Decisões

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Internacional | 1 transação **sintética** por fatura, parseada da linha `Total lançamentos inter. em R$` | Por transação — formato validado em amostra pequena (9 faturas), campo US$/conversão sem mapeamento confiável. Risco de repetir o erro de "adivinhar padrão com pouca amostra" já cometido antes nesta mesma investigação |
| b | Produtos/serviços | Por transação, bloco de 1–2 linhas | Consolidado como internacional — descartado porque o formato aqui é limpo e consistente nas 5 faturas medidas, vale o detalhe por item |
| c | Marcador `NN/NN` em produtos/serviços | **Nunca** popula `installment_number`/`installment_total` — todo item commita avulso | Mesmo tratamento de compras e saques (parcela 1 vira grupo completo) — descartado: no corpus, "Anuidade Diferenciada NN/12" é cobrada e estornada quase no mês seguinte; criar um `InstallmentGroup` de 12 parcelas projetaria 11 cobranças futuras que o histórico real mostra que não acontecem |
| d | Descrição (produtos/serviços) | Linha de continuação quando existe (`Anuidade Diferenciada`); código truncado da linha 1 quando não há (`ENVIOMENS.AUTOMATICA`) | — |
| e | Sinal | Mesma regra de compras e saques: negativo = estorno (`credit`/INCOME) | — |
| f | Onde a lógica entra | Generaliza `extrairTransacoesDoStream` (hoje fixo em 1 header) pra aceitar header + função de parse de linha, chamado 3x (compras e saques, produtos/serviços, internacional-consolidado) na mesma coluna já separada | Duplicar o loop de bloco pra cada seção — mais código, mesmo risco de dessincronia que a spec anterior já cortou ao extrair `extrairVencimento` |

**(a) Por que consolidado, não por transação.** A mesma disciplina que motivou a spec anterior
("pare de adivinhar, meça o corpus") se aplica aqui: 9 faturas é amostra pequena pra um formato
que já se mostra quebrado (valor em US$ numa linha sem alinhamento óbvio com a linha da data).
Consolidar pela linha de subtotal fecha a diferença de valor com risco baixo — perde
detalhe por estabelecimento, ganha certeza no total.

**(c) Por que nunca parcela.** `ImportService.commit()` já decide "parcela 1 com total>1 vira
grupo completo" olhando só os campos `installment_number`/`installment_total` — não importa de
qual seção do template eles vieram. Não popular esses campos pra produtos/serviços é a forma
mais simples de garantir que essa seção NUNCA aciona esse caminho, sem precisar de nenhuma
exceção nova no `ImportService`.

## 3. Modelo de dados / Contrato de API

Nenhuma mudança — reusa `NormalizedTransactionDTO`/`StagedTransaction` já existentes. Sem
migration, sem contrato REST novo.

## 4. Fluxo

### 4.1 Generalização do extrator de bloco

`extrairTransacoesDoStream(String stream, int mesVencimento, int anoVencimento)` vira
`extrairTransacoesDoStream(String stream, String header, int mesVencimento, int anoVencimento,
Function<String, TransacaoItau> parserDeLinha)` — mesma lógica de cursor/bloco/stop-marker,
parametrizada pelo header procurado e pela função que reconhece uma linha. `parse()` chama 3x:

```java
transacoes.addAll(extrairTransacoesDoStream(coluna, HEADER_LANCAMENTOS, mes, ano, this::parseLinhaCompraOuSaque));
transacoes.addAll(extrairTransacoesDoStream(coluna, HEADER_PRODUTOS_SERVICOS, mes, ano, this::parseLinhaProdutoServico));
transacoes.addAll(extrairTransacaoInternacionalConsolidada(coluna, mes, ano));
```

`STOP_MARKERS` não precisa de mudança — os 2 headers novos já estão na lista, então o bloco de
compras e saques já para corretamente antes deles; os blocos novos usam a MESMA lista pra saber
onde parar (menos o próprio header que os abre).

### 4.2 Produtos/serviços — reconhecimento de linha

`parseLinhaProdutoServico`: casa `^(\d{2})/(\d{2})\s+(.+?)\s+(-?\d[\d.,]*)\s*$` na linha 1
(mesmo padrão de valor final já usado). Marcador `NN/NN` reconhecido e **descartado** (não vira
campo — decisão c). Espia a PRÓXIMA linha do bloco: se ela não casar `^\d{2}/\d{2}` (não é
início de outra transação) e não for um marcador de fim de seção, é a descrição completa;
senão, usa o texto da linha 1 (menos data/valor/marcador) como descrição.

### 4.3 Internacional — consolidado

`extrairTransacaoInternacionalConsolidada`: dentro do bloco delimitado por "Lançamentos
internacionais" até o próximo `STOP_MARKERS`, busca a linha `Total lançamentos\s+inter\. em R\$
(\d[\d.,]*)` (tolerante a espaço duplo, já visto no corpus). Se não achar → nenhuma transação
sintética (seção ausente ou formato não reconhecido, sem erro — mesma filosofia de "ausência de
sinal não é erro" do resto do template). Data: primeiro `DD/MM` encontrado no bloco antes da
linha de subtotal. Descrição fixa: `"Lançamentos internacionais (consolidado)"`.

## 5. Testes

Fixtures sintéticas reproduzindo os formatos reais medidos (nunca fatura real no repositório):

| Caso | Cobertura |
|---|---|
| Produto/serviço linha única | `ENVIOMENS.AUTOMATICA` sem continuação → 1 transação, descrição do código |
| Produto/serviço 2 linhas | `ANUIDADE DIFER 03/12` + continuação → descrição da linha 2, sem `installment_number` |
| Estorno | valor negativo → `direction=credit` |
| Internacional consolidado | bloco com subtotal → 1 transação sintética, valor do subtotal |
| Internacional ausente | fatura sem a seção → nenhuma transação extra, comportamento inalterado |
| Regressão | fatura sem nenhuma das 2 seções novas → contagem/soma de compras e saques idêntica a antes desta entrega |

## 6. Impacto SemVer

**MINOR** — extração de dado antes ignorado (mais staged transactions por import), sem mudança
de contrato REST.

## 7. Dívida técnica registrada

- **Internacional por transação:** se o formato se mostrar estável em amostra maior no futuro,
  vale revisitar o parsing por linha (perderíamos hoje: estabelecimento, data exata,
  valor em moeda original). Fora de escopo enquanto a amostra for pequena.
- **Interação com duplicata concentrada (já registrada na spec anterior):** produtos/serviços
  sempre avulso reduz esse risco especificamente pra essa seção (nunca cria grupo), mas não
  resolve o caso já conhecido em compras e saques.
