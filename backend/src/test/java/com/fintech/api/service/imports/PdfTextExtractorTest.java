package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
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
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unitário PURO (sem Spring) do {@link PdfTextExtractor}: roteamento por magic number, falha
 * explícita de PDF sem texto (escaneado) ou corrompido (Onda 1), e a heurística de reconhecimento
 * de transação por linha — data + valor na mesma linha (Onda 2, spec §6.3).
 *
 * <p>Fixtures são geradas EM MEMÓRIA via PDFBox (não arquivos binários commitados em
 * {@code src/test/resources}) — evita manter blob binário não-diffável para um caso tão simples
 * de reproduzir programaticamente. Só ASCII sem acento no texto das fixtures: a fonte Helvetica
 * padrão (WinAnsiEncoding) do PDFBox não garante todo acento, e a heurística já reconhece
 * "debito"/"credito" com ou sem acento (regex {@code d[ée]bito}/{@code cr[ée]dito}).
 */
class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor("v1-test");

    private static ExtractionInput input(byte[] content) {
        return new ExtractionInput(content, "extrato.pdf", "application/pdf", ImportMode.NEW_TRANSACTIONS);
    }

    /** PDF de verdade, com uma página contendo texto extraível. */
    private static byte[] pdfComTexto(String... lines) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                for (String line : lines) {
                    cs.showText(line);
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

    /** PDF de verdade, mas com página em branco (sem operador de texto) — simula PDF escaneado. */
    private static byte[] pdfSemTexto() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Bytes com o magic number correto, mas sem NENHUMA estrutura válida de PDF por trás. */
    private static byte[] pdfCorrompido() {
        return "%PDF-1.4\nisto nao e um pdf de verdade, so o cabecalho".getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void supportsAceitaQualquerPdfPeloMagicNumberIndependenteDeTerTexto() {
        assertThat(extractor.supports(input(pdfComTexto("Extrato de teste")))).isTrue();
        assertThat(extractor.supports(input(pdfSemTexto()))).isTrue();
        // supports() só sniffa os bytes iniciais — não abre o documento — por isso aceita até o
        // corrompido (a falha desse caso só aparece em extract(), não no roteamento).
        assertThat(extractor.supports(input(pdfCorrompido()))).isTrue();
    }

    @Test
    void supportsRejeitaArquivoQueNaoComecaComMagicNumberDePdf() {
        assertThat(extractor.supports(input("nao e um pdf".getBytes(StandardCharsets.UTF_8)))).isFalse();
        assertThat(extractor.supports(input(new byte[0]))).isFalse();
    }

    @Test
    void extractDevolveBatchComSourceTypeEExtractorUsedCorretos() {
        NormalizedBatchDTO batch = extractor.extract(input(pdfComTexto("01/07/2026 PADARIA TESTE 55,90")));

        assertThat(batch.sourceType()).isEqualTo(ImportSourceType.PDF_TEXT);
        assertThat(batch.extractorUsed()).isEqualTo("pdf_text_v1");
    }

    @Test
    void extractDevolveBatchVazioQuandoNenhumaLinhaTemDataEValor() {
        // Texto presente (passa do limiar de "tem camada de texto"), mas sem o padrão data+valor
        // em nenhuma linha — o batch fica vazio, e é o guard-rail já existente no ImportService
        // ("zero transações aproveitáveis") que converte isso em FAILED, sem duplicar a checagem aqui.
        NormalizedBatchDTO batch = extractor.extract(
                input(pdfComTexto("Extrato de teste com texto suficiente para passar do limiar")));

        assertThat(batch.transactions()).isEmpty();
    }

    @Test
    void reconheceTransacoesComDataEValorEmFormatosDistintos() {
        NormalizedBatchDTO batch = extractor.extract(input(pdfComTexto(
                "01/07/2026 PADARIA TESTE 55,90",
                "2026-07-02 ALUGUEL -1.200,00")));

        assertThat(batch.transactions()).hasSize(2);

        NormalizedTransactionDTO primeira = batch.transactions().get(0);
        assertThat(primeira.fields().get("transaction_date").value()).isEqualTo("2026-07-01");
        assertThat(primeira.fields().get("amount").value()).isEqualTo(new BigDecimal("55.90"));
        assertThat(primeira.fields().get("amount").confidence()).isEqualByComparingTo("1.0");
        assertThat(primeira.fields().get("description").value()).isEqualTo("PADARIA TESTE");
        // Valor positivo, sem palavra-chave → credit (inferência pelo sinal, confiança 0.7).
        assertThat(primeira.fields().get("direction").value()).isEqualTo("credit");
        assertThat(primeira.fields().get("direction").confidence()).isEqualByComparingTo("0.7");

        NormalizedTransactionDTO segunda = batch.transactions().get(1);
        assertThat(segunda.fields().get("transaction_date").value()).isEqualTo("2026-07-02");
        // Amount é sempre gravado em MÓDULO (mesma convenção do OFX/CSV) — o sinal só decide direction.
        assertThat(segunda.fields().get("amount").value()).isEqualTo(new BigDecimal("1200.00"));
        assertThat(segunda.fields().get("direction").value()).isEqualTo("debit");
        assertThat(segunda.fields().get("description").value()).isEqualTo("ALUGUEL");
    }

    @Test
    void linhaSemDataOuSemValorNaoViraTransacao() {
        NormalizedBatchDTO batch = extractor.extract(input(pdfComTexto(
                "EXTRATO DE CONTA CORRENTE",
                "Saldo anterior",
                "01/07/2026 PADARIA TESTE 55,90",
                "Total de lancamentos: 1")));

        // Só a linha com data E valor reconhecidos vira transação — as demais (cabeçalho, saldo
        // sem data, rodapé com número solto sem data) não têm o par completo do padrão.
        assertThat(batch.transactions()).hasSize(1);
        assertThat(batch.transactions().get(0).fields().get("description").value()).isEqualTo("PADARIA TESTE");
    }

    @Test
    void palavraChaveDebitoOuCreditoTemPrioridadeSobreOSinalDoValor() {
        NormalizedBatchDTO batch = extractor.extract(input(pdfComTexto(
                "03/07/2026 COMPRA DEBITO 45,00")));

        // Valor POSITIVO (sem sinal de menos), mas a palavra-chave "DEBITO" na linha vence a
        // inferência pelo sinal — mesma prioridade da direção no CsvExtractor (coluna de tipo
        // explícita vale mais que o sinal do valor).
        assertThat(batch.transactions().get(0).fields().get("direction").value()).isEqualTo("debit");
    }

    @Test
    void aceitaDataComAnoReduzidoEValorComSimboloDeMoeda() {
        NormalizedBatchDTO batch = extractor.extract(input(pdfComTexto(
                "04/07/26 PAGAMENTO R$ 120,00")));

        NormalizedTransactionDTO tx = batch.transactions().get(0);
        assertThat(tx.fields().get("transaction_date").value()).isEqualTo("2026-07-04");
        assertThat(tx.fields().get("amount").value()).isEqualTo(new BigDecimal("120.00"));
    }

    @Test
    void extractLancaExcecaoExplicitaQuandoPdfNaoTemTextoExtraivel() {
        assertThatThrownBy(() -> extractor.extract(input(pdfSemTexto())))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("imagem digitalizada");
    }

    @Test
    void extractLancaExcecaoExplicitaQuandoPdfEstaCorrompidoSemVazarMensagemDeInfra() {
        assertThatThrownBy(() -> extractor.extract(input(pdfCorrompido())))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("Não foi possível abrir este PDF")
                // Mensagem é a NOSSA, redigida em PT-BR — nunca o texto cru de uma exceção do
                // PDFBox (que poderia vazar detalhe de parsing interno da lib).
                .hasMessageNotContaining("org.apache.pdfbox");
    }

    @Test
    void sourceTypeEExtractorVersionSaoExpostosParaOCaminhoDeFalha() {
        assertThat(extractor.sourceType()).isEqualTo(ImportSourceType.PDF_TEXT);
        assertThat(extractor.extractorVersion()).isEqualTo("v1-test");
    }
}
