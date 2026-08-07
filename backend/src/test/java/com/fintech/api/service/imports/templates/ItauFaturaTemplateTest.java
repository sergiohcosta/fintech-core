package com.fintech.api.service.imports.templates;

import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItauFaturaTemplateTest {

    private final ItauFaturaTemplate template = new ItauFaturaTemplate();

    /**
     * PDF sintético com DUAS "colunas" de texto — reproduz a fusão real de coluna do Itaú
     * (spec: fix-templates-pdf-ordem-real, §1.1). Linhas na mesma posição de índice das duas
     * listas caem na MESMA altura Y, que é o que reproduz a fusão do PDFBox.
     *
     * <p>Escreve palavra a palavra, como todas as fixtures deste arquivo — ver
     * {@link #escreveLinhasPalavraAPalavra}.
     */
    private static byte[] pdfComDuasColunas(List<String> linhasEsquerda, List<String> linhasDireita) {
        return pdfComDuasColunas(linhasEsquerda, linhasDireita, 50f, 400f);
    }

    /**
     * Variante com varargs: converte Strings em listas para simplicidade em testes que
     * usam poucas linhas. Strings vazias viram listas vazias. O header "Lançamentos: compras e saques"
     * é adicionado automaticamente antes de cada linha para permitir ao parser localizar o bloco.
     */
    private static byte[] pdfComDuasColunas(String esquerda, String direita) {
        List<String> linhasEsquerda = esquerda.isEmpty() ? List.of() : List.of("Lançamentos: compras e saques", esquerda);
        List<String> linhasDireita = direita.isEmpty() ? List.of() : List.of("Lançamentos: compras e saques", direita);
        return pdfComDuasColunas(linhasEsquerda, linhasDireita);
    }

    /**
     * Variante multi-página: uma entrada por página em {@code paginasEsquerda}/
     * {@code paginasDireita} (mesmo índice = mesma página). Usada para o teste de
     * regressão do bug de duplicação — {@code addRegion} chamado dentro do loop de
     * páginas acumulava regiões repetidas e {@code extractRegions} duplicava/triplicava
     * o texto a partir da 2ª página.
     */
    private static byte[] pdfComDuasColunasMultiPagina(
            List<List<String>> paginasEsquerda, List<List<String>> paginasDireita) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < paginasEsquerda.size(); i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.newLineAtOffset(50, 700);
                    for (String linha : paginasEsquerda.get(i)) {
                        cs.showText(linha);
                        cs.newLineAtOffset(0, -15);
                    }
                    cs.endText();
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.newLineAtOffset(400, 700);
                    for (String linha : paginasDireita.get(i)) {
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

    /**
     * Variante com offsets X configuráveis — usada pelos testes de detecção dinâmica de
     * coluna, que precisam controlar exatamente onde cada coluna começa.
     *
     * <p><b>Escreve PALAVRA A PALAVRA, cada uma posicionada por métrica de fonte</b>, e não a
     * linha inteira numa operação só. Isso importa: o PDFBox entrega a uma
     * {@code PDFTextStripper} exatamente os pedaços que foram escritos — uma fatura real (que
     * posiciona cada palavra) chega token a token, enquanto uma fixture que escreve a linha
     * inteira chega num token só. Fixture com granularidade errada testa um caminho de código
     * que não existe em produção; foi assim que defeitos reais passaram pelos testes antes.
     */
    private static byte[] pdfComDuasColunas(
            List<String> linhasEsquerda, List<String> linhasDireita, float xEsquerda, float xDireita) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                escreveLinhasPalavraAPalavra(cs, linhasEsquerda, xEsquerda, 700);
                escreveLinhasPalavraAPalavra(cs, linhasDireita, xDireita, 700);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final PDType1Font FONTE_FIXTURE =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final float TAMANHO_FONTE_FIXTURE = 10f;

    /** Largura do texto na fonte da fixture, em pontos. */
    private static float larguraDe(String texto) throws IOException {
        return FONTE_FIXTURE.getStringWidth(texto) / 1000f * TAMANHO_FONTE_FIXTURE;
    }

    /**
     * Escreve cada linha com uma operação de escrita POR PALAVRA, avançando X pela largura
     * real da palavra mais um espaço — reproduz a granularidade de token de um PDF de verdade.
     */
    private static void escreveLinhasPalavraAPalavra(
            PDPageContentStream cs, List<String> linhas, float x0, float y0) throws IOException {
        float espaco = larguraDe(" ");
        float y = y0;
        for (String linha : linhas) {
            float x = x0;
            for (String palavra : linha.split(" ")) {
                if (palavra.isEmpty()) {
                    x += espaco;
                    continue;
                }
                cs.beginText();
                cs.setFont(FONTE_FIXTURE, TAMANHO_FONTE_FIXTURE);
                cs.newLineAtOffset(x, y);
                cs.showText(palavra);
                cs.endText();
                x += larguraDe(palavra) + espaco;
            }
            y -= 15f;
        }
    }

    @Test
    void matchesReconheceCnpjItauEHeaderDeLancamentos() {
        String texto = "algum texto\n60.872.504/0001-23\nLançamentos: compras e saques\nfim";
        assertThat(template.matches(texto)).isTrue();
    }

    @Test
    void matchesRejeitaTextoSemCnpjOuSemHeader() {
        assertThat(template.matches("Lançamentos: compras e saques sem cnpj nenhum")).isFalse();
        assertThat(template.matches("60.872.504/0001-23 sem o header de lancamentos")).isFalse();
    }

    @Test
    void templateIdEItauFaturaV1() {
        assertThat(template.templateId()).isEqualTo("itau_fatura_v1");
    }

    private static final String CABECALHO_VENCIMENTO =
            "FULANO DE TAL\nVencimento 10/03/2025\n"
            + "60.872.504/0001-23\n";

    @Test
    void parseSeparaColunasQuandoDuasTransacoesEstaoNaMesmaAlturaY() {
        // O header precisa estar DENTRO do PDF (não só no fullTextFake) — extrairTransacoesDoStream
        // roda sobre o stream extraído por coluna, não sobre fullText; sem o header lá, o bloco
        // de lançamentos nunca é encontrado nas colunas. Ele entra como uma linha acima da
        // transação em cada coluna (mesma sequência de newLineAtOffset), o que não interfere no
        // ponto central do teste: a linha de transação continua na MESMA altura Y nas duas colunas.
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "28/11 Foco Aluguel de Ca04/06 112,67"),
                List.of("Lançamentos: compras e saques", "07/02 BeneficiarioTeste 36,00"));
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

    @Test
    void parseNaoDuplicaTransacoesEmFaturaComMultiplasPaginas() {
        // Regressão: addRegion() define o FORMATO da região (nome → retângulo), não é por
        // página — chamá-lo dentro do loop de páginas acumulava um par "esquerda"/"direita"
        // extra a cada página, e extractRegions() duplicava (2ª página), triplicava (3ª)
        // etc. o texto extraído. 1 transação real por página (2 no total) prova que não há
        // duplicação: o resultado tem que ser exatamente 2, não 4.
        byte[] pdfBytes = pdfComDuasColunasMultiPagina(
                List.of(
                        List.of("Lançamentos: compras e saques", "03/02 SUBWAY FAZENDINHA 49,00"),
                        List.of("05/02 FARMACIA SAO JOAO 30,00")),
                List.of(List.of(), List.of()));
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/02 SUBWAY FAZENDINHA 49,00\n"
                + "05/02 FARMACIA SAO JOAO 30,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(2);
        assertThat(transacoes)
                .extracting(tx -> tx.fields().get("description").value())
                .containsExactlyInAnyOrder("SUBWAY FAZENDINHA", "FARMACIA SAO JOAO");
    }

    @Test
    void parseReconheceTransacaoSimplesDentroDaSecaoDeLancamentos() {
        // O template agora extrai o bloco de lançamentos do STREAM DO PDF (separado por
        // coluna), não mais de fullText — a fixture precisa conter o header/marcador dentro
        // do PDF, não só na String fullTextFake usada para matches()/DUE_DATE.
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "03/02 SUBWAY FAZENDINHA 49,00", "Limites de crédito"),
                List.of());
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/02 SUBWAY FAZENDINHA 49,00\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("transaction_date").value()).isEqualTo("2025-02-03");
        assertThat((BigDecimal) tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("49.00"));
        assertThat(tx.fields().get("description").value()).isEqualTo("SUBWAY FAZENDINHA");
        assertThat(tx.fields().get("direction").value()).isEqualTo("debit");
    }

    @Test
    void parseRemoveMarcadorDeParcelaColadoAoEstabelecimento() {
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "28/11 Foco Aluguel de Ca04/06 112,67", "Limites de crédito"),
                List.of());
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "28/11 Foco Aluguel de Ca04/06 112,67\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(1);
        assertThat(transacoes.get(0).fields().get("description").value()).isEqualTo("Foco Aluguel de Ca");
        // Mês do lançamento (11) > mês de vencimento (03) → ano anterior ao de vencimento.
        assertThat(transacoes.get(0).fields().get("transaction_date").value()).isEqualTo("2024-11-28");
    }

    @Test
    void parseIgnoraComprasParceladasDeProximasFaturas() {
        byte[] pdfBytes = pdfComDuasColunas(
                List.of(
                        "Lançamentos: compras e saques",
                        "28/11 Foco Aluguel de Ca04/06 112,67",
                        "Compras parceladas - próximas faturas",
                        "28/11 Foco Aluguel de Ca05/06 112,67"),
                List.of());
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "28/11 Foco Aluguel de Ca04/06 112,67\n"
                + "Compras parceladas - próximas faturas\n"
                + "28/11 Foco Aluguel de Ca05/06 112,67\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        // Só a parcela do ciclo corrente (04/06) — a próxima parcela (05/06), que aparece na
        // seção de preview de faturas futuras, não vira transação deste batch.
        assertThat(transacoes).hasSize(1);
    }

    @Test
    void parseTrataValorNegativoComoCreditEEstornoDeAnuidade() {
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "03/03 ESTORNO DE ANUIDADE DIF - 29,50", "Limites de crédito"),
                List.of());
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/03 ESTORNO DE ANUIDADE DIF - 29,50\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat((BigDecimal) tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("29.50"));
        assertThat(tx.fields().get("direction").value()).isEqualTo("credit");
        assertThat(tx.fields().get("description").value()).isEqualTo("ESTORNO DE ANUIDADE DIF");
    }

    @Test
    void parseReconheceTransacoesDeTodosOsBlocosDeLancamentosComTitularAdicional() {
        // O header "Lançamentos: compras e saques" repete uma vez por titular adicional no
        // mesmo cartão — cada ocorrência é seu próprio bloco, delimitado até o marcador de
        // parada mais próximo (ou fim do texto, na última).
        byte[] pdfBytes = pdfComDuasColunas(
                List.of(
                        "Lançamentos: compras e saques",
                        "03/02 SUBWAY FAZENDINHA 49,00",
                        "Lançamentos: compras e saques",
                        "05/02 FARMACIA SAO JOAO 30,00",
                        "Limites de crédito"),
                List.of());
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/02 SUBWAY FAZENDINHA 49,00\n"
                + "Lançamentos: compras e saques\n"
                + "05/02 FARMACIA SAO JOAO 30,00\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(2);
        assertThat(transacoes)
                .extracting(tx2 -> tx2.fields().get("description").value())
                .containsExactlyInAnyOrder("SUBWAY FAZENDINHA", "FARMACIA SAO JOAO");
    }

    @Test
    void parseNaoDuplicaTransacoesQuandoHeaderRepeteEmContinuacaoDePagina() {
        // Regressão do bug real (validação manual vs. fatura Itaú real, "Foco Aluguel de Ca
        // 112,67" duplicado): forma FIEL ao bug — header repetido na PÁGINA 2 (não na mesma
        // página, forma do teste anterior que a revisão de Task 4 apontou como não-reprodutora),
        // com uma transação DISTINTA de cada lado do 2º header e SEM stop marker entre elas (só
        // no fim, depois da 2ª).
        //
        // NOTA HONESTA (achado da verificação red→green desta task): apesar de modelar a forma
        // exata do cenário real, este teste passa TANTO contra o código antigo (nextHeaderIdx
        // capando o bloco) QUANTO contra o fix atual — verificado via git stash + execução real
        // (2 e 3 páginas) e confirmado por enumeração exaustiva do algoritmo em Java standalone
        // (todas as combinações de header/marker/conteúdo até 8-9 símbolos, 2 alfabetos de
        // marcador distintos): headerIdx_antigo e cursor_novo sempre reencontram o MESMO próximo
        // header (busca determinística a partir de `start`, que nunca contém outro header entre
        // si e `stop`), então os blocos antigo e novo particionam o MESMO conteúdo sem overlap
        // nem perda — o diff de f33b494 é, matematicamente, um no-op de saída para esta função.
        // Mantido mesmo assim como guarda de regressão fiel ao formato real de continuação de
        // página (mais forte que o teste anterior de mesma página, mesmo não sendo red→green) —
        // a causa real da duplicação relatada na fatura de produção provavelmente está em outra
        // camada (extração PDFBox por região/página, já corrigida em 7c74479), não neste loop.
        // Ver pdfComDuasColunasMultiPagina para o helper multi-página.
        byte[] pdfBytes = pdfComDuasColunasMultiPagina(
                List.of(
                        List.of("Lançamentos: compras e saques", "28/11 Foco Aluguel de Ca 112,67"),
                        List.of(
                                "Lançamentos: compras e saques",
                                "07/02 BeneficiarioTeste 36,00",
                                "Limites de crédito")),
                List.of(List.of(), List.of()));
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "28/11 Foco Aluguel de Ca 112,67\n"
                + "Lançamentos: compras e saques\n"
                + "07/02 BeneficiarioTeste 36,00\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(2);
        NormalizedTransactionDTO primeira = transacoes.stream()
                .filter(t -> "Foco Aluguel de Ca".equals(t.fields().get("description").value()))
                .findFirst().orElseThrow();
        assertThat((BigDecimal) primeira.fields().get("amount").value())
                .isEqualByComparingTo(new BigDecimal("112.67"));
        NormalizedTransactionDTO segunda = transacoes.stream()
                .filter(t -> "BeneficiarioTeste".equals(t.fields().get("description").value()))
                .findFirst().orElseThrow();
        assertThat((BigDecimal) segunda.fields().get("amount").value())
                .isEqualByComparingTo(new BigDecimal("36.00"));
    }

    @Test
    void parseCapturaNumeroETotalDeParcelaQuandoLinhaTemMarcador() {
        byte[] pdfBytes = pdfComDuasColunas("28/11 Foco Aluguel de Ca04/06 112,67", "");
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "28/11 Foco Aluguel de Ca04/06 112,67\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("installment_number").value()).isEqualTo(4);
        assertThat(tx.fields().get("installment_total").value()).isEqualTo(6);
        assertThat(tx.fields().get("installment_number").confidence()).isEqualByComparingTo("1.0");
        // Descrição continua limpa — o marcador não sobra nela (comportamento já existente).
        assertThat(tx.fields().get("description").value()).isEqualTo("Foco Aluguel de Ca");
    }

    @Test
    void parseNaoGravaCamposDeParcelaQuandoLinhaNaoTemMarcador() {
        byte[] pdfBytes = pdfComDuasColunas("03/02 SUBWAY FAZENDINHA 49,00", "");
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/02 SUBWAY FAZENDINHA 49,00\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(1);
        assertThat(transacoes.get(0).fields()).doesNotContainKey("installment_number");
        assertThat(transacoes.get(0).fields()).doesNotContainKey("installment_total");
    }

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
        // 365f; o bug real caiu em ~351-358; aqui o vão fica em ~200 — posição arbitrária,
        // só pra provar generalização).
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "28/11 Foco Aluguel de Ca04/06 112,67"),
                List.of("Lançamentos: compras e saques", "07/02 BeneficiarioTeste 36,00"),
                50f, 250f);
        String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

        List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

        assertThat(resultado).hasSize(2);
        assertThat(resultado)
                .extracting(t -> t.fields().get("amount").value())
                .containsExactlyInAnyOrder(new BigDecimal("112.67"), new BigDecimal("36.00"));
    }

    @Test
    void parseNaoFundeColunasQuandoLinhaAvulsaCruzaOVao() throws IOException {
        // Regressão do C1 do review final (fix-itau-split-coluna-dinamico): uma única linha
        // fora das duas colunas de lançamento (rodapé de central de atendimento, endereço,
        // numeração de página) cujo texto atravessa a faixa X da calha real é o suficiente
        // pra "fechar" o vão inteiro na página — detectColumnSplit rodava um PDFTextStripper
        // sobre a página TODA, sem filtrar por seção. Antes deste fix, o fallback devolvia
        // pageWidth, a região "direita" ficava vazia e as duas colunas eram fundidas no
        // MESMO stream (data de uma transação com o valor da outra) — exatamente o modo de
        // falha do PR #213 reaberto por outro caminho. O fix devolve o corte histórico
        // (365f) como último recurso: aqui isso continua separando as colunas 50/340
        // corretamente, então o resultado tem que ser 2 transações DISTINTAS e corretas.
        byte[] pdfBytes = pdfComDuasColunasComLinhaAvulsaNaCalha();
        String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

        List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

        assertThat(resultado).hasSize(2);
        NormalizedTransactionDTO primeira = resultado.stream()
                .filter(t -> "112.67".equals(t.fields().get("amount").value().toString()))
                .findFirst().orElseThrow();
        assertThat(primeira.fields().get("description").value()).isEqualTo("Foco Aluguel de Ca");
        assertThat(primeira.fields().get("transaction_date").value()).isEqualTo("2024-11-28");
        NormalizedTransactionDTO segunda = resultado.stream()
                .filter(t -> "36.00".equals(t.fields().get("amount").value().toString()))
                .findFirst().orElseThrow();
        assertThat(segunda.fields().get("description").value()).isEqualTo("BeneficiarioTeste");
        assertThat(segunda.fields().get("transaction_date").value()).isEqualTo("2025-02-07");
    }

    /**
     * Duas colunas na posição padrão deste arquivo (esquerda X=50, direita X=400) mais UMA
     * linha de rodapé, fora da área de lançamentos (Y bem abaixo das linhas de transação),
     * cujo texto começa dentro do vão real entre as colunas e se estende além dele — uma
     * palavra qualquer cruzando a calha anula qualquer detecção baseada em "faixa X vazia".
     * A âncora de data ignora esse ruído por construção: o rodapé não tem token {@code DD/MM}
     * iniciando bloco, então não entra em nenhum cluster.
     */
    private static byte[] pdfComDuasColunasComLinhaAvulsaNaCalha() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(50, 700);
                cs.showText("Lançamentos: compras e saques");
                cs.newLineAtOffset(0, -15);
                cs.showText("28/11 Foco Aluguel de Ca04/06 112,67");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(400, 700);
                cs.showText("Lançamentos: compras e saques");
                cs.newLineAtOffset(0, -15);
                cs.showText("07/02 BeneficiarioTeste 36,00");
                cs.endText();

                // Rodapé isolado, em outra altura Y, cruzando a calha entre as duas colunas
                // (começa antes de 400 e se estende além, fechando o vão real).
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(200, 100);
                cs.showText("Central de atendimento SAC 0800 728 0728");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void parseNaoCortaDentroDeTextoQuandoLinhaLargaVemAntesDeLinhaEstreita() {
        // Regressão da mudança do algoritmo para running-max: uma linha larga (descrição
        // longa) seguida por uma linha estreita (descrição curta) na mesma coluna, antes da
        // transição para a coluna direita. Sem running-max, o cursor usaria o maxX da linha
        // estreita (não a larga), calcularia um vão falso e retornaria split dentro do
        // texto real da linha larga (bug de corte reintroduzido). Running-max garante que o
        // cursor rastreia o MÁXIMO alcance visto até ali — a split fica DEPOIS de qualquer
        // texto já processado.
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques",
                        "28/11 Uma Descricao Bem Longa De Compra Parcelada 999,99",
                        "29/11 Curta 1,00"),
                List.of("Lançamentos: compras e saques", "07/02 BeneficiarioTeste 36,00"),
                50f, 400f);
        String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

        List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

        // Com o running-max correto, todas as três transações são reconhecidas
        assertThat(resultado).hasSize(3);
        assertThat(resultado)
                .extracting(t -> t.fields().get("amount").value())
                .containsExactlyInAnyOrder(
                        new BigDecimal("999.99"),  // Linha larga da esquerda
                        new BigDecimal("1.00"),    // Linha estreita da esquerda
                        new BigDecimal("36.00"));  // Coluna direita
    }

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

    /**
     * Reproduz o modo de falha REAL medido em fatura de produção, que exige DOIS fatores
     * simultâneos — nenhum deles sozinho quebra, e é por isso que as fixtures anteriores
     * deste arquivo não pegaram o defeito:
     *
     * <ol>
     *   <li>uma linha avulsa cruzando a calha (rodapé/endereço), que anula a detecção por
     *       maior vão — nenhuma faixa X da página fica 100% vazia;
     *   <li>coluna direita começando ANTES de 365pt (aqui 351,3 — extremo real medido no
     *       levantamento de 45 faturas), de modo que o corte histórico de fallback cai
     *       DENTRO da coluna direita e a corrompe.
     * </ol>
     */
    private static byte[] pdfCalhaCruzadaComColunaDireitaAntesDe365() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(133.0f, 700);
                cs.showText("Lançamentos: compras e saques");
                cs.newLineAtOffset(0, -15);
                cs.showText("28/11 Foco Aluguel de Ca04/06 112,67");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(351.3f, 700);
                cs.showText("Lançamentos: compras e saques");
                cs.newLineAtOffset(0, -15);
                cs.showText("07/02 BeneficiarioTeste 36,00");
                cs.endText();

                // Rodapé isolado cruzando a calha entre as colunas (fator 1).
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(280, 100);
                cs.showText("Central de atendimento SAC 0800 728 0728");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void parseReconheceColunasNoOutroExtremoDaGeometriaMedida() throws IOException {
        // Extremo real medido no corpus: esquerda X=133.0, direita X=351.3, com linha avulsa
        // na calha. Falha contra qualquer implementação que caia num corte fixo de 365pt —
        // esse valor cai dentro da coluna direita aqui e a funde com a esquerda.
        byte[] pdfBytes = pdfCalhaCruzadaComColunaDireitaAntesDe365();
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

    /**
     * Página de coluna ÚNICA mais um token de data solto bem à direita (ex.: uma data isolada
     * numa caixa de resumo). Sem proteção, esse token vira "coluna direita" e o corte cai DENTRO
     * das linhas de lançamento, partindo cada uma em metade-data e metade-valor — a página
     * inteira some da extração, em silêncio. É o cenário que o review final provou com PDFBox.
     */
    private static byte[] pdfColunaUnicaComDataSoltaADireita() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                escreveLinhasPalavraAPalavra(cs, List.of(
                        "Lançamentos: compras e saques",
                        "28/11 Foco Aluguel de Cavalos Premium Ltda 112,67",
                        "29/11 Outra Compra Qualquer Bem Comprida 50,00",
                        "30/11 Terceira Compra Aqui Tambem Longa 25,00"), 50f, 700f);
                // Data solta em outra altura, longe o bastante pra passar no guard de separação
                // (>100pt) mas perto o bastante pra que o corte resultante caia DENTRO das
                // linhas de lançamento acima — é essa combinação que produz o defeito.
                escreveLinhasPalavraAPalavra(cs, List.of("15/12"), 180f, 300f);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void parseNaoPerdeColunaUnicaQuandoHaDataSoltaADireita() throws IOException {
        // Guard de "o corte não pode atravessar linha de lançamento": mesmo que a data solta
        // forme um cluster e passe pelos limiares de massa e separação, o corte resultante
        // partiria as três linhas ao meio — então a página é tratada como coluna única e as
        // três transações continuam sendo extraídas.
        byte[] pdfBytes = pdfColunaUnicaComDataSoltaADireita();
        String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

        List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

        assertThat(resultado).hasSize(3);
        assertThat(resultado)
                .extracting(t -> t.fields().get("amount").value())
                .containsExactlyInAnyOrder(
                        new BigDecimal("112.67"), new BigDecimal("50.00"), new BigDecimal("25.00"));
    }

    /**
     * Última página típica: coluna esquerda cheia, coluna direita com POUCOS lançamentos, e
     * datas de ruído ainda mais à direita (seção de limites de crédito). Se a segunda coluna
     * fosse escolhida por MASSA, o ruído (massa maior que a coluna esparsa) venceria e o corte
     * cairia depois da coluna direita real, partindo-a ao meio.
     */
    private static byte[] pdfColunaDireitaEsparsaComRuidoMaisADireita() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                escreveLinhasPalavraAPalavra(cs, List.of(
                        "Lançamentos: compras e saques",
                        "28/11 Primeira Esquerda 10,00",
                        "29/11 Segunda Esquerda 20,00",
                        "30/11 Terceira Esquerda 30,00",
                        "01/12 Quarta Esquerda 40,00"), 143f, 700f);
                // Coluna direita real, esparsa: 2 lançamentos.
                escreveLinhasPalavraAPalavra(cs, List.of(
                        "Lançamentos: compras e saques",
                        "02/12 Primeira Direita 50,00",
                        "03/12 Segunda Direita 60,00"), 358f, 700f);
                // Ruído mais à direita, com massa MAIOR que a coluna direita real (3 > 2),
                // em outra altura — não são lançamentos.
                escreveLinhasPalavraAPalavra(cs,
                        List.of("10/12", "11/12", "12/12"), 500f, 300f);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Coluna única cujos marcadores de parcela estão ALINHADOS em X — formam um cluster com
     * massa igual à da própria coluna. É o único arranjo que exercita {@link
     * ItauFaturaTemplate} no ponto do filtro de "início de bloco": sem ele, esse cluster vira
     * "coluna direita" e o corte cai no meio das linhas. Marcadores em X variável (o que
     * acontece quando as descrições têm larguras diferentes) NÃO testam nada — viram clusters
     * de massa 1 e perdem para a coluna real por qualquer critério.
     */
    private static byte[] pdfMarcadoresDeParcelaAlinhados() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                escreveLinhasPalavraAPalavra(cs, List.of("Lançamentos: compras e saques"), 143f, 700f);
                // Descrições de MESMA largura → os marcadores caem todos no mesmo X.
                String[] datas = {"28/11", "29/11", "30/11", "01/12"};
                String[] valores = {"10,00", "20,00", "30,00", "40,00"};
                float y = 685f;
                for (int i = 0; i < datas.length; i++) {
                    escreveLinhasPalavraAPalavra(cs, List.of(datas[i] + " LojaParceladaAqui"), 143f, y);
                    // Marcador de parcela colado à descrição, sempre no MESMO X.
                    escreveLinhasPalavraAPalavra(cs, List.of("0" + (i + 1) + "/06"), 255f, y);
                    escreveLinhasPalavraAPalavra(cs, List.of(valores[i]), 300f, y);
                    y -= 15f;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void parseNaoTomaMarcadoresDeParcelaAlinhadosComoColuna() throws IOException {
        // Os 4 marcadores alinhados em X=255 formam um cluster de massa 4 — mesma massa da
        // coluna real e a 112pt dela, ou seja, passariam por todos os limiares. Só o filtro de
        // "início de bloco" (MIN_BLOCK_GAP) os descarta, porque estão colados à descrição.
        byte[] pdfBytes = pdfMarcadoresDeParcelaAlinhados();
        String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

        List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

        assertThat(resultado).hasSize(4);
        assertThat(resultado)
                .extracting(t -> t.fields().get("amount").value())
                .containsExactlyInAnyOrder(
                        new BigDecimal("10.00"), new BigDecimal("20.00"),
                        new BigDecimal("30.00"), new BigDecimal("40.00"));
    }

    @Test
    void parseEscolheColunaDireitaRealEmVezDoRuidoMaisMassivoADireita() throws IOException {
        byte[] pdfBytes = pdfColunaDireitaEsparsaComRuidoMaisADireita();
        String fullTextFake = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques";

        List<NormalizedTransactionDTO> resultado = template.parse(fullTextFake, pdfBytes);

        assertThat(resultado).hasSize(6);
        assertThat(resultado)
                .extracting(t -> t.fields().get("amount").value())
                .containsExactlyInAnyOrder(
                        new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"),
                        new BigDecimal("40.00"), new BigDecimal("50.00"), new BigDecimal("60.00"));
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
}
