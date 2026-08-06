# Fix Templates PDF (ordem real do PDFBox) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corrigir os dois templates PDF (Itaú fatura, Nubank extrato) para produzirem dado financeiro correto contra documentos reais — hoje produzem dado errado silencioso (data/valor de transações diferentes misturados), confirmado por teste em dev com os arquivos reais.

**Architecture:** Itaú passa a separar as duas colunas da fatura via `PDFTextStripperByArea` (região retangular por coordenada X) antes de aplicar a lógica de linha já existente — corrige a fusão de colunas na mesma linha de texto. Nubank remove o acumulador multilinha (nunca necessário — 23/23 transações reais têm valor grudado na própria linha do rótulo) e passa a descartar qualquer linha sem valor reconhecível, eliminando o vazamento de rodapé/decoração por construção.

**Tech Stack:** Java 21, Apache PDFBox 3.0.7 (`PDFTextStripperByArea`, já dependência do projeto), JUnit 5 + AssertJ.

## Global Constraints

- Spec de origem: `docs/superpowers/specs/2026-08-06-fix-templates-pdf-ordem-real-design.md` — decisões já tomadas, não reabrir.
- `PdfBankTemplate.parse` muda de assinatura: `parse(String fullText)` → `parse(String fullText, byte[] content)`. Único breaking change interno (ambos templates + `PdfTextExtractor` + testes precisam atualizar juntos).
- Nenhuma migration, nenhuma mudança de contrato de API. Impacto SemVer: **PATCH**.
- Fixtures de teste continuam sintéticas — nunca PDF real de usuário commitado.
- Commits em português, imperativo, sem `Co-Authored-By`.

---

### Task 1: `PdfBankTemplate.parse` recebe `byte[] content` — wiring

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/PdfBankTemplate.java`
- Modify: `backend/src/main/java/com/fintech/api/service/imports/PdfTextExtractor.java`
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java` (só assinatura, lógica na Task 2)
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/NubankExtratoTemplate.java` (só assinatura, lógica na Task 3)
- Modify: `backend/src/test/java/com/fintech/api/service/imports/PdfTextExtractorTest.java`
- Modify: `backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java`
- Modify: `backend/src/test/java/com/fintech/api/service/imports/templates/NubankExtratoTemplateTest.java`

**Interfaces:**
- Produces: `PdfBankTemplate.parse(String fullText, byte[] content)` — novo contrato, usado pelas Tasks 2-4.

- [ ] **Step 1: Mudar a interface**

Em `PdfBankTemplate.java`, trocar:
```java
    /** Reconhece as transações do documento. Só chamado quando {@link #matches} devolveu {@code true}. */
    List<NormalizedTransactionDTO> parse(String fullText);
```
por:
```java
    /**
     * Reconhece as transações do documento. Só chamado quando {@link #matches} devolveu
     * {@code true}. {@code content} (bytes originais do PDF) é oferecido para templates que
     * precisam reabrir o documento com extração posicional (ex.: separar colunas por
     * coordenada) — templates que só precisam do texto achatado (ex.: Nubank) ignoram esse
     * parâmetro.
     */
    List<NormalizedTransactionDTO> parse(String fullText, byte[] content);
```

- [ ] **Step 2: Atualizar o call site em `PdfTextExtractor`**

Em `PdfTextExtractor.java`, trocar (dentro de `extract()`):
```java
        for (PdfBankTemplate template : templates) {
            if (template.matches(text)) {
                return new NormalizedBatchDTO(
                        input.mode(), ImportSourceType.PDF_TEXT, template.templateId(), extractorVersion,
                        template.parse(text));
            }
        }
```
por:
```java
        for (PdfBankTemplate template : templates) {
            if (template.matches(text)) {
                return new NormalizedBatchDTO(
                        input.mode(), ImportSourceType.PDF_TEXT, template.templateId(), extractorVersion,
                        template.parse(text, input.content()));
            }
        }
```

- [ ] **Step 3: Atualizar as assinaturas dos dois templates (sem mudar lógica ainda)**

Em `ItauFaturaTemplate.java`, trocar a assinatura do método (mantendo o corpo atual intacto
por enquanto — a Task 2 reescreve o corpo):
```java
    @Override
    public List<NormalizedTransactionDTO> parse(String fullText) {
```
por:
```java
    @Override
    public List<NormalizedTransactionDTO> parse(String fullText, byte[] content) {
```

Em `NubankExtratoTemplate.java`, mesma troca de assinatura (corpo intacto, Task 3 reescreve):
```java
    @Override
    public List<NormalizedTransactionDTO> parse(String fullText) {
```
por:
```java
    @Override
    public List<NormalizedTransactionDTO> parse(String fullText, byte[] content) {
```

- [ ] **Step 4: Atualizar todos os call sites de teste**

Em `PdfTextExtractorTest.java`, `ItauFaturaTemplateTest.java` e `NubankExtratoTemplateTest.java`,
buscar todas as chamadas `template.parse(texto)` ou `.parse(algumaVariavel)` de um
`ItauFaturaTemplate`/`NubankExtratoTemplate` e adicionar o segundo argumento. Para os testes
que já têm bytes de PDF disponíveis (via `pdfComTexto(...)`), passar esses bytes. Para os
testes de `ItauFaturaTemplateTest`/`NubankExtratoTemplateTest` que hoje só constroem uma
`String texto` solta (sem gerar PDF real), passar `new byte[0]` por enquanto — a Task 2
reescreve os testes do Itaú para gerar PDF real via PDFBox (necessário para testar
separação de coluna de verdade); a Task 3 mantém os testes do Nubank com `String` pura
(não precisa de bytes, `content` é ignorado por esse template) mas o compilador exige o
argumento mesmo assim.

- [ ] **Step 5: Compilar e rodar a suíte inteira dos 3 arquivos afetados**

Run: `cd backend && ./mvnw test -Dtest=PdfTextExtractorTest,ItauFaturaTemplateTest,NubankExtratoTemplateTest`
Expected: compila e todos os testes existentes continuam passando (mudança é só de
assinatura, nenhum comportamento mudou ainda nesta task).

- [ ] **Step 6: Commit**

```bash
cd backend
git add src/main/java/com/fintech/api/service/imports/templates/PdfBankTemplate.java \
        src/main/java/com/fintech/api/service/imports/PdfTextExtractor.java \
        src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java \
        src/main/java/com/fintech/api/service/imports/templates/NubankExtratoTemplate.java \
        src/test/java/com/fintech/api/service/imports/PdfTextExtractorTest.java \
        src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java \
        src/test/java/com/fintech/api/service/imports/templates/NubankExtratoTemplateTest.java
git commit -m "$(cat <<'EOF'
refactor(import): PdfBankTemplate.parse passa a receber os bytes do PDF

Pré-requisito pro fix do ItauFaturaTemplate (Task 2), que precisa
reabrir o documento com extração posicional por coordenada pra
separar as colunas da fatura. Nenhuma mudança de comportamento nesta
task — só assinatura.
EOF
)"
```

---

### Task 2: `ItauFaturaTemplate` — separa colunas por posição antes de parsear

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java`
- Modify: `backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java`

**Interfaces:**
- Consumes: `PdfBankTemplate.parse(String fullText, byte[] content)` (Task 1).
- Produces: mesmo `templateId()`/`matches()` (inalterados).

**Contexto (evidência real, não repetir na leitura de código):** a fatura Itaú renderiza
duas colunas de lançamentos lado a lado; o PDFBox funde texto da mesma altura Y em uma
única linha, mesmo com `setSortByPosition(true)`. Confirmado por coordenada real
(`TextPosition.getXDirAdj()`): gap claro entre colunas em torno de x≈365pt (página A4,
595.28×841.89pt). `PDFTextStripperByArea` com duas regiões retangulares resolve isso na
origem, sem tocar a lógica de linha já validada.

- [ ] **Step 1: Escrever o teste de fusão de coluna (fixture PDF real, não string solta)**

Este teste PRECISA gerar um PDF de verdade com posicionamento controlado (X, Y) — o bug só
existe em coordenada real, não é reproduzível com uma `String` de teste. Adicionar ao topo
de `ItauFaturaTemplateTest.java` os imports necessários e um helper de fixture:

```java
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
```

```java
    /**
     * PDF sintético com DUAS "colunas" de texto na MESMA altura Y — reproduz a fusão real
     * de coluna do Itaú (spec: fix-templates-pdf-ordem-real, §1.1). {@code leftX}/{@code
     * rightX} simulam a posição real observada (esquerda ~178-340pt, direita ~394-556pt).
     */
    private static byte[] pdfComDuasColunas(String textoEsquerda, String textoDireita) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(50, 700);
                cs.showText(textoEsquerda);
                cs.endText();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(400, 700);
                cs.showText(textoDireita);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```

Adicionar o teste:

```java
    @Test
    void parseSeparaColunasQuandoDuasTransacoesEstaoNaMesmaAlturaY() {
        byte[] pdfBytes = pdfComDuasColunas(
                "28/11 Foco Aluguel de Ca04/06 112,67",
                "07/02 BeneficiarioTeste 36,00");
        // O texto achatado (fullText) simula o que o PdfTextExtractor já extraiu antes de
        // chamar o template — precisa conter CNPJ/header/vencimento pro matches()/DUE_DATE,
        // mas a EXTRAÇÃO REAL de coluna vem de content (bytes), não deste texto.
        String fullTextFake = "60.872.504/0001-23\nVencimento 10/03/2025\n"
                + "Lançamentos: compras e saques\n"
                + "28/11 Foco Aluguel de Ca04/06 112,67 07/02 BeneficiarioTeste 36,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(fullTextFake, pdfBytes);

        assertThat(transacoes).hasSize(2);
        NormalizedTransactionDTO primeira = transacoes.stream()
                .filter(t -> "112.67".equals(t.fields().get("amount").value().toString()))
                .findFirst().orElseThrow();
        assertThat(primeira.fields().get("description").value()).isEqualTo("Foco Aluguel de Ca");
        NormalizedTransactionDTO segunda = transacoes.stream()
                .filter(t -> "36.00".equals(t.fields().get("amount").value().toString()))
                .findFirst().orElseThrow();
        assertThat(segunda.fields().get("description").value()).isEqualTo("BeneficiarioTeste");
    }
```

- [ ] **Step 2: Rodar o teste, confirmar que falha do jeito certo**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest#parseSeparaColunasQuandoDuasTransacoesEstaoNaMesmaAlturaY`
Expected: FAIL — com a implementação atual (ainda lendo só `fullTextFake`, que tem as duas
transações fundidas na mesma linha), o resultado tem 1 transação com valor errado (36.00,
não 112.67), não 2.

- [ ] **Step 3: Reescrever `parse()` para separar colunas via `PDFTextStripperByArea`**

Adicionar imports:
```java
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
```

Adicionar constante (junto das outras `private static final`):
```java
    // Gap real entre as duas colunas de lançamentos da fatura, medido por coordenada X
    // (TextPosition) contra o PDF real que motivou este fix — página A4 (595.28×841.89pt).
    // Fixo por ora (spec: decisão d) — não há segundo exemplar de fatura pra validar se
    // varia entre documentos.
    private static final float COLUMN_SPLIT_X = 365f;
```

Trocar o corpo de `parse()` (mantendo a assinatura `(String fullText, byte[] content)` já
trocada na Task 1):
```java
    @Override
    public List<NormalizedTransactionDTO> parse(String fullText, byte[] content) {
        Matcher dueDateMatcher = DUE_DATE.matcher(fullText);
        if (!dueDateMatcher.find()) {
            throw new ExtractionException(
                    "Não foi possível localizar a data de vencimento na fatura Itaú.");
        }
        int mesVencimento = Integer.parseInt(dueDateMatcher.group(2));
        int anoVencimento = Integer.parseInt(dueDateMatcher.group(3));

        StringBuilder colunaEsquerda = new StringBuilder();
        StringBuilder colunaDireita = new StringBuilder();
        // A fatura renderiza duas colunas de lançamentos lado a lado — o PDFBox funde texto
        // da mesma altura Y numa única linha (mesmo com sortByPosition), misturando data e
        // valor de transações DIFERENTES. Reabrir o documento e extrair por REGIÃO
        // RETANGULAR (posição X) separa as colunas antes de qualquer parsing de linha.
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);
            for (PDPage page : document.getPages()) {
                PDRectangle box = page.getMediaBox();
                stripper.addRegion("esquerda", new Rectangle2D.Float(0, 0, COLUMN_SPLIT_X, box.getHeight()));
                stripper.addRegion("direita",
                        new Rectangle2D.Float(COLUMN_SPLIT_X, 0, box.getWidth() - COLUMN_SPLIT_X, box.getHeight()));
                stripper.extractRegions(page);
                colunaEsquerda.append(stripper.getTextForRegion("esquerda")).append('\n');
                colunaDireita.append(stripper.getTextForRegion("direita")).append('\n');
            }
        } catch (IOException e) {
            throw new ExtractionException(
                    "Não foi possível reabrir o PDF da fatura Itaú para separar as colunas.", e);
        }

        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        transacoes.addAll(extrairTransacoesDoStream(colunaEsquerda.toString(), mesVencimento, anoVencimento));
        transacoes.addAll(extrairTransacoesDoStream(colunaDireita.toString(), mesVencimento, anoVencimento));
        return transacoes;
    }

    /**
     * Localiza TODOS os blocos "Lançamentos: compras e saques" dentro de UM stream já
     * separado por coluna (esquerda ou direita) e reconhece as transações de cada um — a
     * mesma lógica de delimitação de seção de antes, agora rodando sobre texto limpo (sem
     * fusão de coluna), o que a torna correta: cada coluna tem seus próprios cabeçalhos e
     * marcadores de parada na ordem certa.
     */
    private List<NormalizedTransactionDTO> extrairTransacoesDoStream(
            String stream, int mesVencimento, int anoVencimento) {
        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        int headerIdx = stream.indexOf(HEADER_LANCAMENTOS);
        while (headerIdx >= 0) {
            int start = headerIdx + HEADER_LANCAMENTOS.length();
            int stop = stream.length();
            for (String marker : STOP_MARKERS) {
                int idx = stream.indexOf(marker, start);
                if (idx >= 0 && idx < stop) {
                    stop = idx;
                }
            }
            int nextHeaderIdx = stream.indexOf(HEADER_LANCAMENTOS, start);
            if (nextHeaderIdx >= 0 && nextHeaderIdx < stop) {
                stop = nextHeaderIdx;
            }
            String bloco = stream.substring(start, stop);
            for (String linha : bloco.lines().toList()) {
                TransacaoItau transacao = parseLinha(linha.trim(), mesVencimento, anoVencimento);
                if (transacao != null) {
                    transacoes.add(toDto(transacao));
                }
            }
            headerIdx = stream.indexOf(HEADER_LANCAMENTOS, start);
        }
        return transacoes;
    }
```

`parseLinha`, `parseValorBr`, `toDto` e o record `TransacaoItau` continuam exatamente como
estão — a correção é só na entrada (colunas separadas), a lógica de linha já era correta.

- [ ] **Step 4: Rodar o novo teste, confirmar que passa**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest#parseSeparaColunasQuandoDuasTransacoesEstaoNaMesmaAlturaY`
Expected: PASS

- [ ] **Step 5: Ajustar os testes existentes de `parse()` pra gerar PDF real (não string solta)**

Os 4 testes de `parse()` já existentes (`parseReconheceTransacaoSimplesDentroDaSecaoDeLancamentos`,
`parseRemoveMarcadorDeParcelaColadoAoEstabelecimento`, `parseIgnoraComprasParceladasDeProximasFaturas`,
`parseTrataValorNegativoComoCreditEEstornoDeAnuidade`) hoje chamam `template.parse(texto, ...)`
passando só uma `String` solta como `fullText` e (pós Task 1) `new byte[0]` como `content` —
isso agora QUEBRA de verdade, porque `parse()` sempre reabre `content` via `Loader.loadPDF`,
e `byte[0]` não é um PDF válido.

Trocar cada um desses 4 testes para gerar um PDF sintético de UMA coluna só (usando o
mesmo padrão de `pdfComTexto` já usado em `PdfTextExtractorTest`, ou reaproveitando
`pdfComDuasColunas` com `textoDireita=""`) e passar `fullTextFake` (com CNPJ/header/
vencimento, igual ao texto usado hoje) + os bytes reais do PDF gerado. Exemplo de ajuste
para `parseReconheceTransacaoSimplesDentroDaSecaoDeLancamentos`:

```java
    @Test
    void parseReconheceTransacaoSimplesDentroDaSecaoDeLancamentos() {
        byte[] pdfBytes = pdfComDuasColunas("03/02 SUBWAY FAZENDINHA 49,00", "");
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/02 SUBWAY FAZENDINHA 49,00\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("transaction_date").value()).isEqualTo("2025-02-03");
        assertThat(tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("49.00"));
        assertThat(tx.fields().get("description").value()).isEqualTo("SUBWAY FAZENDINHA");
        assertThat(tx.fields().get("direction").value()).isEqualTo("debit");
    }
```

Aplicar o mesmo padrão (gerar PDF real de 1 coluna com o texto da transação, manter o
`fullTextFake` só com CNPJ/header/vencimento pro `matches()`/`DUE_DATE`) aos outros 3
testes. Nos testes que têm MÚLTIPLAS linhas de transação (`parseIgnoraComprasParceladasDeProximasFaturas`),
colocar todas as linhas no MESMO bloco de texto esquerdo (`textoEsquerda`), separadas por
`\n` dentro da mesma `String` passada a `showText` não funciona (PDFBox não quebra linha
sozinho) — usar múltiplas chamadas `cs.showText(linha); cs.newLineAtOffset(0, -15);` como
já faz o helper `pdfComTexto` de `PdfTextExtractorTest`. Ajustar `pdfComDuasColunas` para
aceitar `String... linhasEsquerda` em vez de uma `String` única, se necessário — critério:
o teste deve continuar legível, não force uma única linha gigante.

- [ ] **Step 6: Rodar a suíte completa do arquivo, confirmar tudo passa**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest`
Expected: PASS (todos os testes, incluindo os 3 de `matches()`/`templateId()` inalterados,
os 4 de `parse()` ajustados, e o novo de separação de coluna)

- [ ] **Step 7: Commit**

```bash
cd backend
git add src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java \
        src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java
git commit -m "$(cat <<'EOF'
fix(import): ItauFaturaTemplate separa colunas por posição antes de parsear

A fatura Itaú renderiza duas colunas de lançamentos lado a lado; o
PDFBox funde texto da mesma altura Y numa única linha, misturando
data de uma transação com valor de outra. Confirmado com dado real
em ambiente dev (a maioria das transações reconhecidas vinha com
valor errado). PDFTextStripperByArea com duas regiões retangulares
(split em x=365pt, medido por coordenada real) separa as colunas
antes da lógica de linha já existente rodar — corrige na origem, sem
mudar o parsing de linha em si.
EOF
)"
```

---

### Task 3: `NubankExtratoTemplate` — remove acumulador, fecha na própria linha

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/NubankExtratoTemplate.java`
- Modify: `backend/src/test/java/com/fintech/api/service/imports/templates/NubankExtratoTemplateTest.java`

**Interfaces:**
- Consumes: `PdfBankTemplate.parse(String fullText, byte[] content)` (Task 1, `content` ignorado por este template).

**Contexto (evidência real):** conferido contra as 23 transações reais do extrato Nubank
que motivou o registry — 100% têm o valor grudado na PRÓPRIA linha do rótulo (nunca numa
linha separada depois de várias linhas de contraparte, como a versão anterior assumia). O
acumulador multilinha nunca fecha corretamente em nenhum caso real observado — é a causa
tanto de descrições erradas quanto do vazamento de rodapé de página já documentado como
risco.

- [ ] **Step 1: Escrever os testes do comportamento novo (linha sem valor é descartada)**

Adicionar a `NubankExtratoTemplateTest.java`:

```java
    @Test
    void parseDescartaLinhaDeDecoracaoSemAcumularNaProximaTransacao() {
        String texto = "Movimentações\n"
                + "10 JUL 2026 Total de entradas + 151,91\n"
                + "Transferência recebida pelo Pix FULANO DE TAL - CAIXA 151,91\n"
                + "ECONOMICA FEDERAL (0104) Agência: 7904 Conta:\n"
                + "0000000000000000000-0\n"
                + "Total de saídas - 0,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, new byte[0]);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("151.91"));
        // Descrição NÃO carrega as linhas de decoração seguintes (agência/conta) — elas
        // vêm DEPOIS da linha que já fechou a transação, e são descartadas.
        assertThat(tx.fields().get("description").value())
                .isEqualTo("Transferência recebida pelo Pix FULANO DE TAL - CAIXA");
    }

    @Test
    void parseNaoDeixaRodapeDePaginaVazarParaTransacaoSeguinte() {
        String texto = "Movimentações\n"
                + "17 JUL 2026 Total de entradas + 450,00\n"
                + "Transferência recebida pelo Pix CICLANO DA SILVA - ITAÚ 450,00\n"
                + "Tem alguma dúvida? Mande uma mensagem para nosso time de atendimento pelo chat do app.\n"
                + "Extrato gerado dia 29 de julho de 2026 às 17:11 3 de 4\n"
                + "Fulano de Tal\n"
                + "UNIBANCO S.A. (0341) Agência: 6868 Conta: 4832-0\n"
                + "Total de saídas - 450,00\n"
                + "Resgate RDB 1.400,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, new byte[0]);

        assertThat(transacoes).hasSize(2);
        // A primeira transação fecha na própria linha, sem absorver o rodapé de página que
        // vem depois. A segunda ("Resgate RDB") tem descrição limpa, sem lixo de rodapé.
        assertThat(transacoes.get(0).fields().get("description").value())
                .isEqualTo("Transferência recebida pelo Pix CICLANO DA SILVA - ITAÚ");
        assertThat(transacoes.get(1).fields().get("description").value()).isEqualTo("Resgate RDB");
        assertThat(transacoes.get(1).fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("1400.00"));
    }
```

- [ ] **Step 2: Rodar os testes novos, confirmar que falham do jeito certo**

Run: `cd backend && ./mvnw test -Dtest=NubankExtratoTemplateTest#parseDescartaLinhaDeDecoracaoSemAcumularNaProximaTransacao+parseNaoDeixaRodapeDePaginaVazarParaTransacaoSeguinte`
Expected: FAIL — com o acumulador atual, a primeira transação do segundo teste absorve o
rodapé inteiro até achar "Total de saídas" (que reseta o acumulador SEM emitir a
transação pendente!) — na verdade, o comportamento atual pode até perder a transação inteira
nesse caso (acumulador reseta em "Total de X" sem checar se havia algo pendente). Documentar
a saída real do FAIL no relatório da task.

- [ ] **Step 3: Reescrever `parse()` sem acumulador**

Trocar o corpo de `parse()` inteiro:
```java
    @Override
    public List<NormalizedTransactionDTO> parse(String fullText, byte[] content) {
        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        LocalDate dataCorrente = null;
        String direcaoCorrente = null;

        // Escopa a leitura à seção "Movimentações" — linha antes dela (resumo do topo) não
        // deve virar transação por engano.
        String textoASerLido = fullText.substring(fullText.indexOf(HEADER_MOVIMENTACOES));
        for (String linhaBruta : textoASerLido.lines().toList()) {
            String linha = linhaBruta.trim();
            if (linha.isEmpty()) {
                continue;
            }

            Matcher dateMatcher = DATE_HEADER.matcher(linha);
            if (dateMatcher.matches()) {
                dataCorrente = LocalDate.of(
                        Integer.parseInt(dateMatcher.group(3)),
                        MESES.get(dateMatcher.group(2)),
                        Integer.parseInt(dateMatcher.group(1)));
                linha = dateMatcher.group(4).trim();
                if (linha.isEmpty()) {
                    continue;
                }
            }

            if (linha.startsWith("Total de entradas")) {
                direcaoCorrente = "credit";
                continue;
            }
            if (linha.startsWith("Total de saídas")) {
                direcaoCorrente = "debit";
                continue;
            }

            // Evidência real (23/23 transações do extrato que motivou este template): o
            // valor SEMPRE vem grudado na própria linha do rótulo — nunca isolado depois de
            // várias linhas de contraparte. Linha sem valor reconhecível é decoração
            // (complemento de agência/conta, rodapé de página) e é sempre descartada, nunca
            // acumulada — isso elimina por construção o vazamento de rodapé pra descrição
            // da transação seguinte.
            Matcher amountMatcher = TRAILING_AMOUNT.matcher(linha);
            if (amountMatcher.find() && amountMatcher.end() == linha.length()) {
                String descricao = linha.substring(0, amountMatcher.start()).trim();
                if (dataCorrente != null && direcaoCorrente != null && !descricao.isEmpty()) {
                    BigDecimal valor = parseValorBr(amountMatcher.group(1));
                    transacoes.add(toDto(dataCorrente, descricao, direcaoCorrente, valor));
                }
            }
        }
        return transacoes;
    }
```

Remover o campo `StringBuilder acumulador` (não existe mais em lugar nenhum do método).

- [ ] **Step 4: Rodar os testes novos, confirmar que passam**

Run: `cd backend && ./mvnw test -Dtest=NubankExtratoTemplateTest`
Expected: PASS nos 2 testes novos.

- [ ] **Step 5: Ajustar/remover os testes antigos que validavam o comportamento de acumulador**

`parseReconheceSaidaComContraparteMultilinha` (spec anterior) testava explicitamente o
acumulador juntando 3 linhas antes do valor — esse CENÁRIO não corresponde a nenhum caso
real (evidência: 0/23). Duas opções, decidir durante a execução conforme o que sobrar de
sentido:
- Remover o teste (o cenário que ele cobria nunca acontece de verdade).
- Reescrever o teste para reflitir a ordem REAL (valor já na primeira linha, linhas
  seguintes são só decoração descartada) — equivalente ao novo
  `parseDescartaLinhaDeDecoracaoSemAcumularNaProximaTransacao` do Step 1, então pode ser
  redundante.

Preferir REMOVER (evita duplicar cobertura do Step 1) — mas rodar a suíte depois de
remover pra confirmar que nenhuma outra asserção dependia dele.

Os demais testes de `parse()` (`parseReconheceEntradaDeLinhaUnica`,
`parseDistingueDirecaoPelaSecaoCorrenteNaoPeloRotulo`, `parseNaoGeraTransacaoParaAsLinhasDeSubtotal`)
continuam válidos sem mudança — todos usam valor na própria linha, que é exatamente o
caso que o novo código trata.

Todos os testes de `parse()` no arquivo precisam do segundo argumento `new byte[0]` em
`template.parse(texto, new byte[0])` (`content` é ignorado por este template — só o Itaú
usa).

- [ ] **Step 6: Rodar a suíte completa do arquivo**

Run: `cd backend && ./mvnw test -Dtest=NubankExtratoTemplateTest`
Expected: PASS em todos os testes do arquivo.

- [ ] **Step 7: Commit**

```bash
cd backend
git add src/main/java/com/fintech/api/service/imports/templates/NubankExtratoTemplate.java \
        src/test/java/com/fintech/api/service/imports/templates/NubankExtratoTemplateTest.java
git commit -m "$(cat <<'EOF'
fix(import): NubankExtratoTemplate fecha transação na própria linha do valor

Evidência real (23/23 transações do extrato que motivou o template):
o valor sempre vem grudado na linha do rótulo, nunca isolado depois
de várias linhas de contraparte. O acumulador multilinha assumia o
oposto e nunca fechava corretamente contra dado real — causa tanto
de descrição errada quanto do vazamento de rodapé de página já
documentado como risco (e confirmado em teste real). Remove o
acumulador: linha sem valor reconhecível é sempre descartada.
EOF
)"
```

---

### Task 4: Integração — reconfirmar contra os PDFs reais em ambiente dev

**Files:** nenhum arquivo novo — esta task é validação manual, não código.

**Interfaces:** nenhuma.

- [ ] **Step 1: Rodar a suíte completa do backend**

Run: `cd backend && ./mvnw test`
Expected: PASS (suíte inteira verde, incluindo `PdfTextExtractorTest` — comando demora,
rodar em background ou `./scripts/test-summary.sh backend`)

- [ ] **Step 2: Validação manual contra os PDFs reais (fora do controle de versão)**

Repetir o teste manual feito em dev antes deste fix (upload via `POST /api/imports` com
`force=true`, conferir `GET /api/imports/{id}/staged`) com os MESMOS dois arquivos reais
(fatura Itaú, extrato Nubank), agora contra o backend rodando localmente com o fix
(`./mvnw spring-boot:run`, perfil `dev`, ou contra o ambiente dev depois do deploy).
Conferir:
- Itaú: cada transação reconhecida bate com uma linha real da fatura (mesma data, mesmo
  estabelecimento, mesmo valor) — comparar manualmente contra pelo menos 10 linhas
  aleatórias do PDF original.
- Nubank: nenhuma descrição contém texto de rodapé/página seguinte; soma das transações de
  um dia bate com o "Total de entradas"/"Total de saídas" impresso no próprio documento
  para aquele dia (conferir pelo menos 3 dias).

Esta validação NÃO entra em teste automatizado (dado real, não commitável) — é o
critério de aceite humano antes de fechar o fix.

- [ ] **Step 3: Reportar o resultado da validação manual**

Sem commit nesta task — se a validação manual (Step 2) achar mais divergência, voltar para
a Task 2 ou 3 conforme o caso (novo ciclo de systematic-debugging, não continuar sem
entender a causa).

---

### Task 5: Documentação

**Files:**
- Modify: `docs/roadmap-extracao-e-conciliacao.md`

**Interfaces:** nenhuma (só documentação).

- [ ] **Step 1: Registrar a correção na Fase 3 do roadmap**

Logo após o parágrafo "**Fatia 2 entregue**" (já existente, sobre o registry de templates),
adicionar:

```markdown
**Correção pós-entrega (fatia 2):** validação contra os PDFs reais que motivaram a fatia
revelou dado financeiro errado nos dois templates — Itaú fundia duas colunas de
lançamentos na mesma linha de texto (PDFBox agrupa por proximidade Y, não por coluna),
misturando data de uma transação com valor de outra; Nubank assumia ordem de valor
invertida da real (acumulador multilinha nunca fechava certo contra dado real). Corrigido
via extração posicional por coluna (`PDFTextStripperByArea`) no Itaú e remoção do
acumulador no Nubank (valor sempre está na própria linha do rótulo, evidência real 23/23).
Spec: `docs/superpowers/specs/2026-08-06-fix-templates-pdf-ordem-real-design.md`. Lição:
fixture sintética de uma linha não expõe bug de ordem real de múltiplas linhas/colunas —
próximos templates de PDF devem validar contra pelo menos um documento real antes de
considerar a fatia fechada, não só depois.
```

- [ ] **Step 2: Commit**

```bash
git add docs/roadmap-extracao-e-conciliacao.md
git commit -m "$(cat <<'EOF'
docs: registra a correção pós-entrega dos templates PDF no roadmap

Documenta a causa raiz (fusão de coluna no Itaú, ordem invertida no
Nubank) e a lição pro processo (validar templates de PDF contra
documento real antes de fechar a fatia).
EOF
)"
```

---

## Nota de execução — worktree

Por `git-operator.md`: nova branch a partir da `develop` atualizada (spec e este plano já
commitados nela).

```bash
cd ~/fintech-core
git pull origin develop
git worktree add -b fix/templates-pdf-ordem-real ~/fintech-core/.worktrees/fix-templates-pdf-ordem-real develop
cd ~/fintech-core/.worktrees/fix-templates-pdf-ordem-real
```
