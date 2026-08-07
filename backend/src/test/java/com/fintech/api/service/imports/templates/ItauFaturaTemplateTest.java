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
     * (spec: fix-templates-pdf-ordem-real, §1.1). Cada linha em {@code linhasEsquerda}/
     * {@code linhasDireita} vira uma chamada {@code showText} própria, separada por
     * {@code newLineAtOffset} — PDFBox não quebra linha sozinho dentro de uma única
     * {@code showText}. Linhas na mesma posição de índice das duas listas caem na MESMA
     * altura Y — é isso que reproduz a fusão de coluna do PDFBox no teste central deste
     * arquivo.
     */
    private static byte[] pdfComDuasColunas(List<String> linhasEsquerda, List<String> linhasDireita) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(50, 700);
                for (String linha : linhasEsquerda) {
                    cs.showText(linha);
                    cs.newLineAtOffset(0, -15);
                }
                cs.endText();
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(400, 700);
                for (String linha : linhasDireita) {
                    cs.showText(linha);
                    cs.newLineAtOffset(0, -15);
                }
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
     * Duas colunas na mesma posição da fixture padrão deste arquivo (esquerda X=50, direita
     * X=400 — a mesma calibração do corte histórico {@code FALLBACK_SPLIT_X}) mais UMA linha
     * de rodapé, fora da área de lançamentos (Y bem abaixo das linhas de transação), cujo
     * texto começa dentro do vão real entre as colunas e se estende além dele — reproduz a
     * prova empírica do C1 do review final: uma palavra qualquer cruzando a calha anula a
     * detecção dinâmica do vão (nenhuma faixa X fica 100% vazia na página inteira). O corte
     * fixo de fallback (365f) continua separando as colunas corretamente aqui — X=400 > 365 —
     * é exatamente o cenário em que o corte histórico é um default seguro.
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
}
