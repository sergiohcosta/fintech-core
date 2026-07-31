package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unitário PURO (sem Spring) do {@link PdfTextExtractor} — Onda 1 (esqueleto): roteamento por
 * magic number e falha explícita de PDF sem texto (escaneado) ou corrompido. A heurística de
 * reconhecimento de transação por linha entra na Onda 2 (spec §6.3).
 *
 * <p>Fixtures são geradas EM MEMÓRIA via PDFBox (não arquivos binários commitados em
 * {@code src/test/resources}) — evita manter blob binário não-diffável para um caso tão simples
 * de reproduzir programaticamente.
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
    void extractDevolveBatchComSourceTypeEExtractorUsedCorretosQuandoHaTexto() {
        NormalizedBatchDTO batch = extractor.extract(input(pdfComTexto("Extrato de teste com texto suficiente para passar do limiar")));

        assertThat(batch.sourceType()).isEqualTo(ImportSourceType.PDF_TEXT);
        assertThat(batch.extractorUsed()).isEqualTo("pdf_text_v1");
        // Onda 1 (esqueleto): heurística de reconhecimento de linha ainda não existe (Onda 2) —
        // o batch vem vazio, e é o guard-rail já existente no ImportService ("zero transações
        // aproveitáveis") que converte isso em FAILED, sem duplicar a checagem aqui.
        assertThat(batch.transactions()).isEmpty();
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
