# Fix: split de coluna dinâmico na fatura Itaú — Plano de Execução

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir o corte fixo de coluna (`COLUMN_SPLIT_X = 365f`) do `ItauFaturaTemplate`
por detecção dinâmica por página, corrigindo a perda silenciosa de ~78% do valor de uma
fatura real (coluna direita inteira descartada quando o cabeçalho da coluna cai antes do
corte fixo).

**Architecture:** Por página, um `PDFTextStripper` leve coleta a posição X (início/fim) de
cada trecho de texto renderizado; o maior vão horizontal encontrado (acima de um limiar
mínimo) vira o corte da página. Com o corte calculado, a extração por região
(`PDFTextStripperByArea`) segue igual a hoje — só o valor do corte passa a variar por
página em vez de ser uma constante global.

**Tech Stack:** Apache PDFBox 3.0.7 (`PDFTextStripper`, `PDFTextStripperByArea`,
`TextPosition`), JUnit 5 + AssertJ (testes já existentes no arquivo).

## Global Constraints

- Migrations imutáveis: esta mudança NÃO adiciona nem edita migration (fix interno, sem
  schema).
- SemVer: PATCH (correção de bug em implementação interna, sem mudança de contrato REST).
- Nenhum dado real (PDF de fatura, nome, CPF) entra em fixture de teste — só valores
  fictícios, mesmo padrão já usado no arquivo (`FULANO DE TAL`, `BeneficiarioTeste`).
- `PDFTextStripperByArea` precisa de uma instância NOVA por página neste fix (não reusar
  uma instância com `addRegion` chamado fora do loop) — o histórico deste mesmo template
  (PR #213) já teve um bug de estado vazando entre páginas quando a stripper era
  reaproveitada; criar uma instância por página elimina a dúvida por construção.

---

### Task 1: Detecção dinâmica de coluna por página

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java`
- Test: `backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java`

**Interfaces:**
- Consumes: nada de tarefas anteriores (fix isolado, 1 arquivo de produção).
- Produces: comportamento de `parse(String fullText, byte[] content)` inalterado na
  assinatura — só a lógica interna de separação de coluna muda. Nenhum outro arquivo do
  projeto referencia `COLUMN_SPLIT_X` (`grep` confirma: só usado dentro deste arquivo).

- [ ] **Step 1: Escrever o teste que reproduz o bug exato encontrado**

Adicionar ao final de `ItauFaturaTemplateTest.java` (antes do fechamento da classe):

```java
@Test
void parseReconheceColunasQuandoDireitaComecaAntesDoCorteFixoAntigo() {
    // Reproduz o bug real: a fatura que motivou este fix tinha o cabeçalho da coluna
    // direita começando em X≈351-358 — ANTES do corte fixo antigo (365f). Aqui a coluna
    // direita começa em X=340: com o corte fixo antigo, cairia inteira na região
    // "esquerda" e se fundiria com a coluna esquerda na mesma altura Y (mesmo bug de
    // fusão de coluna do PR #213, agora causado pelo corte errado em vez de ausência de
    // corte). Com detecção dinâmica, o vão real entre as colunas é achado e as duas
    // transações saem distintas e corretas.
    byte[] pdfBytes = pdfComDuasColunas(
            List.of("Lançamentos: compras e saques", "28/11 Foco Aluguel de Ca04/06 112,67"),
            List.of("Lançamentos: compras e saques", "07/02 BeneficiarioTeste 36,00"),
            50f, 340f);
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
void parseNaoQuebraQuandoPaginaTemColunaUnica() {
    // Página sem vão significativo (todo o texto numa faixa X contínua) — layout de
    // coluna única (ex.: folha de resumo/capa). Não deve lançar exceção; a coluna única
    // ainda é reconhecida normalmente (o parser sempre trata "esquerda"/"direita" como
    // dois streams independentes — aqui a "direita" simplesmente fica vazia).
    byte[] pdfBytes = pdfComDuasColunas(
            List.of("Lançamentos: compras e saques", "28/11 Foco Aluguel de Ca04/06 112,67"),
            List.of(),
            50f, 50f);
    String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

    List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

    assertThat(resultado).hasSize(1);
    assertThat(resultado.get(0).fields().get("amount").value()).isEqualTo(new BigDecimal("112.67"));
}

@Test
void parseFuncionaComVaoEmPosicaoBemDiferenteDaCalibracaoOriginal() {
    // Prova que não há mais dependência de nenhuma constante fixa: vão bem mais à
    // esquerda do que qualquer valor já usado neste arquivo (a calibração original era
    // 365f; o bug real caiu em ~351-358; aqui o vão fica em ~150 — posição arbitrária,
    // só pra provar generalização).
    byte[] pdfBytes = pdfComDuasColunas(
            List.of("Lançamentos: compras e saques", "28/11 Foco Aluguel de Ca04/06 112,67"),
            List.of("Lançamentos: compras e saques", "07/02 BeneficiarioTeste 36,00"),
            50f, 150f);
    String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

    List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

    assertThat(resultado).hasSize(2);
    assertThat(resultado)
            .extracting(t -> t.fields().get("amount").value())
            .containsExactlyInAnyOrder(new BigDecimal("112.67"), new BigDecimal("36.00"));
}
```

Essas três referenciam uma nova sobrecarga de `pdfComDuasColunas` com offsets X
configuráveis (Step 2) — sem ela o teste não compila ainda, o que é esperado neste ponto
(TDD: escreve o teste, roda, vê falhar por motivo esperado).

- [ ] **Step 2: Adicionar a sobrecarga de fixture com offsets X configuráveis**

No mesmo arquivo de teste, adicionar ao lado das outras sobrecargas de
`pdfComDuasColunas` (não substitui as existentes — as duas já usadas por outros testes
continuam com os offsets fixos 50/400):

```java
/**
 * Variante com offsets X configuráveis — usada pelos testes de detecção dinâmica de
 * coluna, que precisam controlar exatamente onde cada coluna começa (diferente das
 * demais fixtures deste arquivo, que sempre usam 50/400, posições seguras em relação ao
 * corte fixo antigo).
 */
private static byte[] pdfComDuasColunas(
        List<String> linhasEsquerda, List<String> linhasDireita, float xEsquerda, float xDireita) {
    try (PDDocument document = new PDDocument()) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
            cs.newLineAtOffset(xEsquerda, 700);
            for (String linha : linhasEsquerda) {
                cs.showText(linha);
                cs.newLineAtOffset(0, -15);
            }
            cs.endText();
            if (!linhasDireita.isEmpty()) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(xDireita, 700);
                for (String linha : linhasDireita) {
                    cs.showText(linha);
                    cs.newLineAtOffset(0, -15);
                }
                cs.endText();
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
}
```

- [ ] **Step 3: Rodar os testes novos e confirmar que falham pelo motivo certo**

Run: `./mvnw -f backend/pom.xml test -Dtest=ItauFaturaTemplateTest -q`

Expected: `parseReconheceColunasQuandoDireitaComecaAntesDoCorteFixoAntigo` e
`parseFuncionaComVaoEmPosicaoBemDiferenteDaCalibracaoOriginal` FALHAM (colunas fundidas
numa só, ou transação errada) — prova que o código ATUAL (corte fixo) tem o bug.
`parseNaoQuebraQuandoPaginaTemColunaUnica` deve PASSAR já hoje (não depende do fix — é
guarda de regressão pro comportamento que já funciona).

- [ ] **Step 4: Implementar a detecção dinâmica de coluna**

Em `ItauFaturaTemplate.java`, substituir o bloco de constantes e o corpo do `try` em
`parse()`.

Remove a constante antiga:
```java
    // Gap real entre as duas colunas de lançamentos da fatura, medido por coordenada X
    // (TextPosition) contra o PDF real que motivou este fix — página A4 (595.28×841.89pt).
    // Fixo por ora (spec: decisão d) — não há segundo exemplar de fatura pra validar se
    // varia entre documentos.
    private static final float COLUMN_SPLIT_X = 365f;
```

Substitui por:
```java
    // Vão mínimo (pt) pra considerar uma quebra de coluna real, não espaço normal entre
    // palavras/blocos de texto — folga generosa: o vão real medido no documento que
    // motivou este fix era ~77pt; espaçamento intra-linha em fonte 10pt não passa de
    // poucos pontos. Abaixo disso, a página é tratada como coluna única (spec §4.1).
    private static final float MIN_GAP_WIDTH = 20f;
```

Adiciona imports:
```java
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.util.Comparator;
```

Substitui o bloco de extração dentro de `parse()` (o `try (PDDocument document =
Loader.loadPDF(content)) { ... }` que hoje cria UMA `PDFTextStripperByArea` fora do loop
de páginas) por:

```java
        try (PDDocument document = Loader.loadPDF(content)) {
            int pageNumber = 0;
            for (PDPage page : document.getPages()) {
                pageNumber++;
                float split = detectColumnSplit(document, page, pageNumber);
                PDRectangle box = page.getMediaBox();
                // Instância nova por página — não reusar uma stripper com addRegion fora
                // do loop (histórico deste template, PR #213: estado vazando entre
                // páginas já causou bug de duplicação aqui antes).
                PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                stripper.setSortByPosition(true);
                stripper.addRegion("esquerda", new Rectangle2D.Float(0, 0, split, box.getHeight()));
                stripper.addRegion("direita",
                        new Rectangle2D.Float(split, 0, box.getWidth() - split, box.getHeight()));
                stripper.extractRegions(page);
                colunaEsquerda.append(stripper.getTextForRegion("esquerda")).append('\n');
                colunaDireita.append(stripper.getTextForRegion("direita")).append('\n');
            }
        } catch (IOException e) {
            throw new ExtractionException(
                    "Não foi possível reabrir o PDF da fatura Itaú para separar as colunas.", e);
        }
```

Adiciona o método novo (privado, logo abaixo de `parse()`):

```java
    /**
     * Acha o corte de coluna da página pelo maior vão horizontal entre trechos de texto
     * renderizados — substitui a constante fixa antiga (spec: fix-itau-split-coluna-
     * dinamico). Sem vão significativo (≥ {@link #MIN_GAP_WIDTH}) na página inteira,
     * devolve a largura da página inteira: a região "direita" fica vazia e a página é
     * tratada como coluna única (mesmo efeito de uma página de resumo/capa sem
     * lançamentos, que já não quebra o loop de blocos hoje).
     */
    private float detectColumnSplit(PDDocument document, PDPage page, int pageNumberOneBased)
            throws IOException {
        List<float[]> extents = new ArrayList<>();
        PDFTextStripper detector = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                float minX = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE;
                for (TextPosition tp : textPositions) {
                    minX = Math.min(minX, tp.getX());
                    maxX = Math.max(maxX, tp.getX() + tp.getWidth());
                }
                if (minX <= maxX) {
                    extents.add(new float[] {minX, maxX});
                }
            }
        };
        detector.setSortByPosition(true);
        detector.setStartPage(pageNumberOneBased);
        detector.setEndPage(pageNumberOneBased);
        detector.getText(document);

        float pageWidth = page.getMediaBox().getWidth();
        if (extents.isEmpty()) {
            return pageWidth;
        }
        extents.sort(Comparator.comparingDouble(e -> e[0]));

        float cursor = 0f;
        float bestGapStart = -1f;
        float bestGapSize = 0f;
        for (float[] extent : extents) {
            if (extent[0] > cursor) {
                float gap = extent[0] - cursor;
                if (gap > bestGapSize) {
                    bestGapSize = gap;
                    bestGapStart = cursor;
                }
            }
            cursor = Math.max(cursor, extent[1]);
        }

        if (bestGapSize < MIN_GAP_WIDTH) {
            return pageWidth;
        }
        return bestGapStart + bestGapSize / 2f;
    }
```

Atualiza também o javadoc da classe (topo do arquivo) que hoje não menciona a mecânica de
coluna — adiciona uma linha citando a detecção dinâmica, pro próximo leitor não achar que
ainda existe uma constante fixa em algum lugar:

```java
/**
 * Template Itaú fatura de cartão — reconhece transações da seção "Lançamentos: compras e
 * saques" (spec: registry de templates, decisões c/e). Datas de lançamento vêm sem ano
 * (DD/MM); o ano é inferido da data de vencimento, que aparece uma vez no documento com ano
 * completo. Colunas de lançamento são separadas por detecção dinâmica de vão horizontal,
 * por página (spec: fix-itau-split-coluna-dinamico) — não há constante fixa de corte.
 */
```

- [ ] **Step 5: Rodar a suíte completa do arquivo e confirmar tudo verde**

Run: `./mvnw -f backend/pom.xml test -Dtest=ItauFaturaTemplateTest -q`

Expected: todos os testes do arquivo passam, incluindo os 3 novos E os pré-existentes
(a mudança não pode quebrar `parseSeparaColunasQuandoDuasTransacoesEstaoNaMesmaAlturaY` e
os demais testes com offsets fixos 50/400 — esses continuam passando porque o vão entre
50 e 400 é bem maior que `MIN_GAP_WIDTH`, a detecção dinâmica acha o mesmo corte que a
constante antiga aproximava).

- [ ] **Step 6: Rodar a suíte completa do módulo `imports` (regressão mais ampla)**

Run: `./mvnw -f backend/pom.xml test -Dtest="com.fintech.api.service.imports.**" -q`

Expected: nenhuma falha — `PdfTextExtractorTest`, `ImportServiceTest` e demais não usam
`COLUMN_SPLIT_X` diretamente (só via `ItauFaturaTemplate.parse()`, cuja assinatura não
mudou), mas rodam por completude já que tocam o mesmo pacote.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java
git add backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java
git commit -m "fix(import): detecta corte de coluna dinamicamente na fatura Itaú

Corte fixo (COLUMN_SPLIT_X=365f) cortava o cabeçalho da coluna
direita ao meio em fatura real cujo layout diverge do documento de
calibração original — coluna inteira descartada em silêncio (~78%
do valor da fatura perdido). Detecção por maior vão horizontal,
por página, substitui a constante — sem depender de calibração
contra um único documento."
```

---

## Fim do plano

Após este task, se a review final aprovar: seguir `superpowers:finishing-a-development-branch`
(merge em `develop`, PR pra `main`, mesmo padrão das entregas anteriores desta sessão).
