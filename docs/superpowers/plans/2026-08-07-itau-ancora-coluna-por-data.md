# Detecção de coluna por âncora de data (Itaú) — Plano de Execução

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir a detecção de coluna do `ItauFaturaTemplate` (hoje: maior vão de texto + fallback `365f`) por âncora nos tokens de data, corrigindo uma regressão em produção que infla o valor extraído de faturas reais em até 2×.

**Architecture:** Só `detectColumnSplit` muda. Colhe os tokens posicionados da página, identifica os tokens `DD/MM` que **iniciam bloco**, agrupa por proximidade em X, pega os 2 clusters de maior massa e corta 10pt à esquerda do início da coluna direita. Nenhuma constante de posição absoluta permanece no arquivo.

**Tech Stack:** Apache PDFBox 3.0.7 (`PDFTextStripper`, `TextPosition`, `PDFTextStripperByArea`), JUnit 5 + AssertJ.

## Global Constraints

- **Remover `FALLBACK_SPLIT_X = 365f` e `MIN_GAP_WIDTH` por completo.** A medição do corpus provou que 365 cai dentro da faixa em que a coluna direita começa (351,3–367,2) — não é default seguro, é corte que atravessa conteúdo real. Nenhuma coordenada absoluta pode sobreviver nesta classe.
- Nenhum dado real de fatura entra no repositório. Fixtures são sintéticas, com valores fictícios (padrão já existente no arquivo: `FULANO DE TAL`, `BeneficiarioTeste`), mas **posicionadas nas coordenadas X reais medidas** (151,2/367,2 e 133,0/351,3).
- `PDFTextStripperByArea` continua com instância nova por página (regressão histórica do PR #213).
- Sem migration, sem mudança de contrato REST. SemVer **PATCH**.
- Comentários no código explicam o **porquê** medido (ex.: "folga real medida ≥20,9pt em 121 páginas"), não o óbvio.

---

### Task 1: Substituir a detecção de coluna pela âncora de data

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java`
- Test: `backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java`

**Interfaces:**
- Consumes: nada de tarefas anteriores.
- Produces: `parse(String fullText, byte[] content)` inalterado na assinatura. `detectColumnSplit` continua privado; só o corpo muda. Nenhum outro arquivo do projeto referencia as constantes removidas (`grep` confirma: `MIN_GAP_WIDTH` e `FALLBACK_SPLIT_X` só aparecem neste arquivo).

- [ ] **Step 1: Escrever os testes da geometria real medida**

Adicionar ao final de `ItauFaturaTemplateTest.java`, antes do fecho da classe. Os três primeiros usam a sobrecarga de fixture com offsets X que já existe no arquivo (`pdfComDuasColunas(List, List, float, float)`).

```java
@Test
void parseReconheceColunasNaGeometriaRealMedidaMaisComum() {
    // Coordenadas medidas em fatura real: coluna esquerda X=151.2, direita X=367.2 —
    // o par mais frequente no levantamento de 45 faturas (spec §1.3).
    byte[] pdfBytes = pdfComDuasColunas(
            List.of("Lançamentos: compras e saques", "28/11 Foco Aluguel de Ca04/06 112,67"),
            List.of("Lançamentos: compras e saques", "07/02 BeneficiarioTeste 36,00"),
            151.2f, 367.2f);
    String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

    List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

    assertThat(resultado).hasSize(2);
    assertThat(resultado)
            .extracting(t -> t.fields().get("amount").value())
            .containsExactlyInAnyOrder(new BigDecimal("112.67"), new BigDecimal("36.00"));
    assertThat(resultado)
            .extracting(t -> t.fields().get("description").value())
            .containsExactlyInAnyOrder("Foco Aluguel de Ca", "BeneficiarioTeste");
}

@Test
void parseReconheceColunasNoOutroExtremoDaGeometriaMedida() {
    // Outro extremo medido no corpus: esquerda X=133.0, direita X=351.3. É o caso em que o
    // antigo corte fixo de 365pt cairia DENTRO da coluna direita (351.3 < 365) e a
    // corromperia — este teste falha contra qualquer implementação que use aquela constante.
    byte[] pdfBytes = pdfComDuasColunas(
            List.of("Lançamentos: compras e saques", "28/11 Foco Aluguel de Ca04/06 112,67"),
            List.of("Lançamentos: compras e saques", "07/02 BeneficiarioTeste 36,00"),
            133.0f, 351.3f);
    String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

    List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

    assertThat(resultado).hasSize(2);
    assertThat(resultado)
            .extracting(t -> t.fields().get("amount").value())
            .containsExactlyInAnyOrder(new BigDecimal("112.67"), new BigDecimal("36.00"));
}

@Test
void parseIgnoraMarcadorDeParcelaComoAncoraDeColuna() {
    // "04/06" no meio da descrição tem o MESMO formato de uma data de lançamento. Se contasse
    // como âncora, criaria um cluster espúrio no meio da página e deslocaria o corte. O filtro
    // de "início de bloco" (>15pt de espaço à esquerda) existe exatamente para isso: no
    // levantamento, 87% do ruído de cluster vinha de marcador de parcela.
    byte[] pdfBytes = pdfComDuasColunas(
            List.of("Lançamentos: compras e saques",
                    "28/11 Foco Aluguel de Ca 04/06 112,67",
                    "29/11 Outra Compra 02/03 50,00"),
            List.of("Lançamentos: compras e saques",
                    "07/02 BeneficiarioTeste 36,00",
                    "08/02 Segunda Direita 21,00"),
            151.2f, 367.2f);
    String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

    List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

    assertThat(resultado).hasSize(4);
    assertThat(resultado)
            .extracting(t -> t.fields().get("amount").value())
            .containsExactlyInAnyOrder(
                    new BigDecimal("112.67"), new BigDecimal("50.00"),
                    new BigDecimal("36.00"), new BigDecimal("21.00"));
}
```

- [ ] **Step 2: Rodar os testes novos e confirmar que o 2º falha**

Run: `./backend/mvnw -f backend/pom.xml test -Dtest=ItauFaturaTemplateTest`

Expected: `parseReconheceColunasNoOutroExtremoDaGeometriaMedida` FALHA contra o código atual (o fallback `365f` corta dentro da coluna direita que começa em 351,3). Os outros dois podem passar já hoje. Se o 2º passar, PARE e reporte — significa que a fixture não está reproduzindo a condição e o teste não prova nada.

- [ ] **Step 3: Implementar a detecção por âncora**

Em `ItauFaturaTemplate.java`:

**(3a)** Remover as duas constantes e seus comentários por completo:
```java
    private static final float MIN_GAP_WIDTH = 20f;
```
e
```java
    private static final float FALLBACK_SPLIT_X = 365f;
```

**(3b)** Adicionar as constantes novas, logo abaixo de `TRAILING_INSTALLMENT_MARKER`:

```java
    // Um token "DD/MM" só conta como início de linha de lançamento se tiver este tanto de
    // espaço vazio à esquerda. Sem isso, o marcador de parcela ("04/06", mesmo formato) viraria
    // âncora: no levantamento de 45 faturas reais, 87% do ruído de cluster vinha daí.
    private static final float MIN_BLOCK_GAP = 15f;

    // Tolerância para dois tokens de data pertencerem à mesma coluna. As colunas reais medidas
    // têm variância interna ~0,01pt e ficam a ~216pt uma da outra — 5pt separa com folga larga.
    private static final float CLUSTER_TOLERANCE = 5f;

    // Massa mínima do 2º cluster para a página ser considerada de duas colunas. Ruído legítimo
    // (datas na seção de limites de crédito) aparece com massa 1–3; coluna real medida tem 15–36.
    private static final int MIN_CLUSTER_MASS = 3;

    // Recuo do corte em relação ao início da coluna direita. A folga real entre o fim do
    // conteúdo da coluna esquerda e o início da direita foi de 20,9pt a 29,2pt em 121 páginas
    // medidas — 10pt fica dentro dessa folga com margem, em todos os casos observados.
    private static final float SPLIT_MARGIN = 10f;

    // Abaixo desta fração da largura da página não há coluna direita plausível (a direita real
    // medida fica em 351–367pt numa página de ~595pt, ou seja ~0,59 da largura).
    private static final float MIN_RIGHT_COLUMN_RATIO = 0.45f;

    // Menos âncoras que isto na página inteira: não há bloco de lançamentos em duas colunas
    // aqui (capa, resumo, página de encargos).
    private static final int MIN_ANCHORS = 4;
```

**(3c)** Trocar imports: remover `java.util.Comparator` se ficar sem uso, garantir
`java.util.Map`, `java.util.TreeMap`, `java.util.Comparator` disponíveis (o arquivo já importa
`List`, `ArrayList`, `Map`, `LinkedHashMap`). Adicionar o que faltar.

**(3d)** Substituir `detectColumnSplit` inteiro (javadoc + corpo) por:

```java
    /**
     * Acha o corte entre as duas colunas de lançamento da página pela posição X dos tokens de
     * DATA que iniciam uma linha de lançamento.
     *
     * <p>Por que a data e não o "maior vão de texto" (que era a estratégia anterior): o vão
     * some assim que QUALQUER texto cai perto da calha — rodapé, endereço, rótulo de subtotal —
     * e a página inteira degrada em silêncio. Já o token de data só existe onde há lançamento,
     * então é imune a esse ruído. Medição sobre 45 faturas reais (2022–2026): as datas formam
     * dois clusters com variância interna ~0,01pt, nas posições exatas das duas colunas, e
     * ZERO clusters espúrios em 141 páginas. Racional completo: spec
     * itau-ancora-coluna-por-data §1.3.
     *
     * <p>Sem duas colunas detectáveis, devolve a largura da página: tudo cai numa região só e é
     * processado normalmente (correto em página de coluna única — capa, resumo). Note que NÃO
     * há mais fallback para coordenada fixa: a medição mostrou que o antigo 365pt cai dentro da
     * faixa onde a coluna direita começa (351–367pt), ou seja, corta conteúdo real.
     */
    private float detectColumnSplit(PDDocument document, PDPage page, int pageNumberOneBased)
            throws IOException {
        float pageWidth = page.getMediaBox().getWidth();
        List<Float> anchors = collectDateAnchors(document, pageNumberOneBased);
        if (anchors.size() < MIN_ANCHORS) {
            return pageWidth;
        }

        List<float[]> clusters = clusterByProximity(anchors);
        // Por MASSA, não por posição: há ruído legítimo à DIREITA da coluna direita (datas na
        // seção de limites de crédito), que venceria um critério de "cluster mais à direita".
        clusters.sort((a, b) -> Float.compare(b[1], a[1]));
        if (clusters.size() < 2 || clusters.get(1)[1] < MIN_CLUSTER_MASS) {
            return pageWidth;
        }

        float rightColumnX = Math.max(clusters.get(0)[0], clusters.get(1)[0]);
        if (rightColumnX < pageWidth * MIN_RIGHT_COLUMN_RATIO) {
            return pageWidth;
        }
        return rightColumnX - SPLIT_MARGIN;
    }

    /**
     * Posições X dos tokens {@code DD/MM} que INICIAM uma linha de lançamento — primeiro token
     * da linha, ou precedido por um espaço vazio de pelo menos {@link #MIN_BLOCK_GAP}. O filtro
     * descarta o marcador de parcela, que tem o mesmo formato mas aparece colado ao meio da
     * descrição.
     */
    private List<Float> collectDateAnchors(PDDocument document, int pageNumberOneBased)
            throws IOException {
        // Chave: Y arredondado (a linha visual). Valor: tokens dessa linha, cada um como
        // {xInicio, xFim} mais o texto — precisamos do texto pra testar o formato de data.
        Map<Integer, List<Object[]>> tokensByRow = new TreeMap<>();
        PDFTextStripper collector = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                if (textPositions.isEmpty()) {
                    return;
                }
                float minX = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE;
                for (TextPosition tp : textPositions) {
                    minX = Math.min(minX, tp.getX());
                    maxX = Math.max(maxX, tp.getX() + tp.getWidth());
                }
                int row = Math.round(textPositions.get(0).getY());
                tokensByRow.computeIfAbsent(row, k -> new ArrayList<>())
                        .add(new Object[] {text.trim(), minX, maxX});
            }
        };
        collector.setSortByPosition(true);
        collector.setStartPage(pageNumberOneBased);
        collector.setEndPage(pageNumberOneBased);
        collector.getText(document);

        List<Float> anchors = new ArrayList<>();
        for (List<Object[]> row : tokensByRow.values()) {
            row.sort(Comparator.comparingDouble(t -> (Float) t[1]));
            for (int i = 0; i < row.size(); i++) {
                String text = (String) row.get(i)[0];
                if (!DATE_TOKEN.matcher(text).matches()) {
                    continue;
                }
                float x = (Float) row.get(i)[1];
                boolean startsBlock = i == 0 || x - (Float) row.get(i - 1)[2] > MIN_BLOCK_GAP;
                if (startsBlock) {
                    anchors.add(x);
                }
            }
        }
        Collections.sort(anchors);
        return anchors;
    }

    /** Agrupa posições X próximas. Cada item devolvido é {@code {centroide, massa}}. */
    private List<float[]> clusterByProximity(List<Float> sortedX) {
        List<float[]> clusters = new ArrayList<>();
        int i = 0;
        while (i < sortedX.size()) {
            float start = sortedX.get(i);
            float sum = 0f;
            int count = 0;
            while (i < sortedX.size() && sortedX.get(i) - start <= CLUSTER_TOLERANCE) {
                sum += sortedX.get(i);
                count++;
                i++;
            }
            clusters.add(new float[] {sum / count, count});
        }
        return clusters;
    }
```

**(3e)** Adicionar o padrão do token de data junto aos outros `Pattern` do topo:
```java
    private static final Pattern DATE_TOKEN = Pattern.compile("^\\d{2}/\\d{2}$");
```

**(3f)** Adicionar os imports que faltarem: `java.util.Collections`, `java.util.Comparator`,
`java.util.TreeMap`, `org.apache.pdfbox.text.PDFTextStripper`,
`org.apache.pdfbox.text.TextPosition`. Remover imports que ficaram sem uso.

- [ ] **Step 4: Rodar os testes do arquivo e confirmar tudo verde**

Run: `./backend/mvnw -f backend/pom.xml test -Dtest=ItauFaturaTemplateTest`

Expected: TODOS passam — os 3 novos e os pré-existentes. Atenção especial aos pré-existentes
que usam offsets 50/400 (`pdfComDuasColunas` de 2 argumentos): a distância entre 50 e 400
é bem maior que a real, mas a detecção por âncora não depende de distância — só de haver dois
clusters de datas, o que essas fixtures têm.

Se algum pré-existente falhar, NÃO ajuste o teste para passar: investigue se a detecção está
correta naquele layout e reporte o achado.

- [ ] **Step 5: Rodar a suíte do pacote de importação (regressão mais ampla)**

Run: `./backend/mvnw -f backend/pom.xml test -Dtest="com.fintech.api.service.imports.**"`

Expected: nenhuma falha.

- [ ] **Step 6: Confirmar que nenhuma coordenada absoluta sobreviveu**

Run: `grep -n "365\|MIN_GAP_WIDTH\|FALLBACK_SPLIT_X" backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java`

Expected: nenhuma ocorrência de `365`, `MIN_GAP_WIDTH` nem `FALLBACK_SPLIT_X`. Se aparecer
alguma, remova — é requisito explícito das Global Constraints (a constante é comprovadamente
nociva, não um default seguro).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java
git add backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java
git commit -m "fix(import): detecta coluna da fatura Itaú pela âncora de data

A detecção por maior vão de texto (em produção) é pior que o bug
que ela corrigia: medida sobre 45 faturas reais, acerta 24/45
contra 40/45 do corte fixo anterior, e erra INFLANDO — no pior
caso dobra o valor da fatura, que é a direção mais perigosa para
dado financeiro.

Passa a ancorar na posição X dos tokens de data que iniciam linha
de lançamento: sinal que só existe onde há transação, imune a
rodapé, endereço e rótulo de subtotal — exatamente o ruído que
derrubava o vão. Zero clusters espúrios em 141 páginas medidas.

Remove o fallback 365pt: a medição mostrou que esse valor cai
dentro da faixa onde a coluna direita começa (351-367pt), ou seja,
corta conteúdo real em parte do corpus."
```

---

## Fim do plano

Após review final aprovada: `superpowers:finishing-a-development-branch` (merge em `develop`,
PR para `main` — agora com PR normal, a divergência de histórico foi resolvida).

**Validação manual obrigatória antes do merge (controller, não subagente):** reimportar a
fatura real que motivou a investigação no backend local e conferir que a soma bate com o total
impresso (R$15.739,87). As faturas reais não entram no repositório nem em subagente.
