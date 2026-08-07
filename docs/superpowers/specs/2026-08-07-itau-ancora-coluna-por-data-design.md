# Spec: detecção de coluna por âncora de data na fatura Itaú

**Data:** 2026-08-07
**Status:** proposto (aguardando aprovação)
**Fonte:** regressão em produção + levantamento empírico sobre **45 faturas Itaú reais**
(2022-01 a 2026-08), medido fora do sistema com PDFBox direto.
**Substitui:** `docs/superpowers/specs/2026-08-07-fix-itau-split-coluna-dinamico-design.md`
(mesma classe de problema; aquele desenho está comprovadamente errado — ver §1.2)
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto

### 1.1 Histórico: três tentativas, três defeitos

O `ItauFaturaTemplate` separa as duas colunas de lançamentos da fatura por uma coordenada X.
A forma de escolher essa coordenada já falhou três vezes:

| # | Estratégia | Defeito |
|---|---|---|
| 1 | `COLUMN_SPLIT_X = 365f` fixo, calibrado contra 1 documento | Fatura com layout ligeiramente diferente teve a coluna direita inteira descartada em silêncio (~78% do valor perdido) |
| 2 | Maior vão horizontal entre extents de TODO o texto da página | Qualquer texto perto da calha (rodapé, endereço, rótulo) zera o vão; colunas fundem e produzem **dado errado** (data de uma transação com valor de outra) |
| 3 | Idem + fallback pro `365f` quando não acha vão | O fallback dispara com frequência e reintroduz o defeito nº1 — **e `365f` cai DENTRO da faixa real da coluna direita** (§2.1), então corta no meio do conteúdo |

### 1.2 Medição: o algoritmo em produção é pior que o bug original

Rodei as três estratégias sobre 45 faturas reais, comparando com os totais impressos nas
próprias faturas:

| Estratégia | Dentro de 90–101% do total impresso | Pior caso |
|---|---|---|
| `365f` fixo (pré-fix) | 40/45 | 1.13× |
| **Vão dinâmico (em produção hoje)** | **24/45** | **2.04× — dobra o valor** |
| Âncora de data (esta spec) | **41/45** | 1.00× |

Na fatura que motivou a investigação, com total verificável de R$15.739,87:
`365f` → R$3.326,53 · **produção → R$32.304,67** · âncora → **R$15.739,87 (exato)**.

O algoritmo em produção não só erra mais: erra **inflando**, que é a pior direção possível
para dado financeiro (dado ausente é percebido; dado inventado passa por real).

### 1.3 O que o corpus revelou sobre o layout

Medido em 121 páginas de 45 faturas, cobrindo 4 anos e 4 números de cartão distintos:

| Fato medido | Valor |
|---|---|
| Largura da página | 595,0–595,3pt (A4, sempre) |
| X de início da coluna **esquerda** | 133,0 · 143,1 · 149,3 · 151,2 |
| X de início da coluna **direita** | 351,3 · 358,7 · 365,3 · 367,2 |
| Distância entre colunas | **215,6–218,3pt** (média 216,0) |
| Folga entre o fim do conteúdo da esquerda e o início da direita | **20,9–29,2pt** — nunca negativa |

Duas consequências diretas:

1. **`365f` é um valor comprovadamente ruim**: cai dentro da faixa `[351,3 ; 367,2]` em que a
   coluna direita começa. Não é "um chute que envelheceu" — é um corte que atravessa conteúdo
   real em parte do corpus. Some da base de código nesta entrega.
2. **Existe folga estrutural de ≥20,9pt** antes da coluna direita. Um corte alguns pontos à
   esquerda do início da coluna direita é seguro por construção, com margem medida.

## 2. Decisões

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Sinal de detecção | Posição X dos **tokens de data (`DD/MM`) que iniciam um bloco** | Maior vão de texto (estratégia 2/3) — refutada pela medição: sensível a qualquer texto perto da calha |
| b | Filtro de ruído | Só conta token de data que é o primeiro da linha OU tem >15pt de espaço vazio à esquerda | Contar todo `DD/MM` — o marcador de parcela (`01/06`) tem o mesmo formato e polui os clusters (87% do ruído medido vem daí) |
| c | Escolha dos clusters | Os **2 clusters de maior massa**, não os mais à esquerda/direita | "Cluster mais à direita" — há ruído legítimo em X 430–463 (seção de limites), que venceria a coluna real |
| d | Ponto de corte | `X_direita − 10pt` | Ponto médio entre colunas — desnecessário: a folga medida (≥20,9pt) já garante segurança com margem bem menor, e ficar perto da coluna direita é mais robusto a variação do lado esquerdo |
| e | Quando não há 2 colunas | Trata a página como **coluna única** (corte = largura da página) | Cair num valor fixo (`365f`) — é exatamente o defeito nº3; nenhuma constante de posição sobrevive a esta entrega |
| f | Arquitetura de extração | Mantém `PDFTextStripperByArea` (região retangular) | Montar as linhas token a token: medido, empata dentro do ruído do ground truth (19 vs 18 exatos) mas é reescrita maior — não se justifica sem ganho demonstrado |

**(a)/(b) Por que token de data.** Cada linha de transação começa com `DD/MM`. Os tokens que
iniciam bloco formam dois clusters apertadíssimos (variância ~0,01pt dentro do cluster) na
posição exata das duas colunas. É um sinal que só existe onde há transação — imune a
cabeçalho, rodapé, endereço e rótulo de subtotal, que foram justamente o que derrubou a
estratégia anterior. Com o filtro de bloco + massa mínima, **zero clusters espúrios em 141
páginas medidas**.

**(c) Por massa, não por posição.** O corpus tem ruído real à direita da coluna direita
(datas na seção de limites de crédito, X 430–463, massa 1–3). A coluna real sempre tem massa
alta (15–36 na medição). Ordenar por massa e pegar os dois primeiros separa sinal de ruído
sem depender de nenhuma coordenada assada no código.

**(e) Coluna única é seguro, valor fixo não é.** Se a detecção não encontra duas colunas, o
corte vira a largura da página: tudo cai numa região só e é processado normalmente. Numa
página que de fato tem uma coluna só (capa, resumo), é o comportamento correto. Numa página
de duas colunas onde a detecção falhou, degrada para "linhas fundidas" — mas isso exige que
uma coluna real tenha menos de 3 transações, cenário que não ocorre em nenhuma das 121
páginas medidas. Já um valor fixo erra **ativamente**, cortando dentro de conteúdo real.

## 3. Modelo de dados / Contrato de API

Nenhuma mudança — implementação interna do template. Sem migration, sem contrato REST.
**SemVer PATCH** (correção de bug).

## 4. Fluxo

### 4.1 `detectColumnSplit` (substitui a implementação atual por completo)

Por página:

1. Colhe todos os tokens de texto com posição (`PDFTextStripper.writeString`), guardando
   `texto`, `xInício`, `xFim` e `y` arredondado.
2. Agrupa os tokens por linha (mesmo `y`).
3. Em cada linha, ordenada por X: um token que casa `^\d{2}/\d{2}$` é **âncora** se for o
   primeiro da linha **ou** se houver mais de `MIN_BLOCK_GAP` (15pt) entre ele e o fim do
   token anterior.
4. Menos de 4 âncoras na página → devolve a largura da página (coluna única).
5. Agrupa as âncoras por proximidade em X (tolerância `CLUSTER_TOLERANCE` = 5pt), guardando
   centroide e massa.
6. Ordena por massa decrescente. Se houver menos de 2 clusters, ou o segundo tiver massa
   < `MIN_CLUSTER_MASS` (3) → largura da página (coluna única).
7. `xDireita` = o maior X entre os dois clusters escolhidos. Se `xDireita < largura × 0,45`
   → largura da página (não há coluna direita plausível).
8. Devolve `xDireita − SPLIT_MARGIN` (10pt).

Nenhuma constante de posição absoluta sobrevive — só tolerâncias relativas, todas
justificadas por medição (§1.3).

### 4.2 Resto do template

Inalterado: com o corte calculado, `PDFTextStripperByArea` extrai as duas regiões (uma
instância nova por página, como já é hoje) e o parsing de linha segue idêntico.

## 5. Testes

As fixtures passam a codificar a **geometria real medida**, não posições inventadas — essa
foi a causa de os três defeitos anteriores passarem pelos testes.

| Caso | Cobertura |
|---|---|
| Geometria real | Duas colunas em X=151,2 e X=367,2 (posições medidas no corpus) → duas transações distintas e corretas |
| Geometria real, variante | Colunas em X=133,0 e X=351,3 (o outro extremo medido) → idem. Prova independência de constante: `365f` cortaria dentro da coluna direita aqui |
| Ruído de marcador de parcela | Linha com `01/06` no meio da descrição não cria cluster nem desloca o corte |
| Texto avulso na calha | Linha solta cruzando o vão entre as colunas (o caso que derrubou a estratégia 3) não funde as colunas |
| Ruído à direita | Tokens de data isolados à direita da coluna direita (massa baixa) não vencem a coluna real |
| Coluna única | Página com uma coluna só é processada normalmente, sem exceção e sem perder a transação |

Fixtures sintéticas com valores fictícios — **nenhuma fatura real entra no repositório**.
As faturas usadas na medição são documentos pessoais do desenvolvedor e permanecem fora
do controle de versão; desta spec constam apenas as estatísticas agregadas.

## 6. Impacto SemVer

**PATCH** — correção de bug em implementação interna, sem contrato REST, sem migration.

## 7. Dívida técnica registrada

- **Guard de soma × total declarado** (já registrado na spec anterior, segue pendente): agora
  há evidência de qual âncora textual usar — `Lançamentos no cartão` aparece em 45/46 faturas,
  mas a extração do valor associado falhou em 2 casos na medição, então ainda não é confiável
  o bastante para virar validação bloqueante. Vale revisitar com extração posicional.
- **Arquitetura token a token** (decisão f): medida e empatada dentro do ruído. Se surgir um
  terceiro defeito nesta área, é o próximo passo natural — a região retangular obriga o
  PDFBox a reachatar o texto, e é dessa reachatação que vieram os três defeitos.
- **Faturas sem CNPJ do Itaú no texto** (8 das 46 medidas, todas anteriores a 2024-04): não
  casam o `matches()` do template e caem na heurística genérica. Fora do escopo desta spec,
  mas é lacuna real de cobertura do registry.
