package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unitário PURO (sem Spring) do {@link OfxExtractor} — prova o leitor tolerante de SGML (1.x)
 * e XML (2.x) com fixtures fictícias em {@code src/test/resources/imports/}. OFX é o primeiro
 * extrator determinístico do funil (roadmap §1.2): sem heurística de coluna, {@code FITID} é
 * identidade única por transação.
 */
class OfxExtractorTest {

    private final OfxExtractor extractor = new OfxExtractor("v1-test");

    private static byte[] fixture(String filename) {
        try (InputStream in = OfxExtractorTest.class.getResourceAsStream("/imports/" + filename)) {
            if (in == null) {
                throw new IllegalStateException("Fixture não encontrada: " + filename);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ExtractionInput input(String filename) {
        return new ExtractionInput(fixture(filename), filename, "application/octet-stream", ImportMode.NEW_TRANSACTIONS);
    }

    @Test
    void supportsAceitaOfx1xPorOfxheaderENaoConfiaNoMimeType() {
        // mimeType "application/octet-stream" é exatamente o que o browser manda pra um .ofx —
        // supports() decide pelo conteúdo (OFXHEADER:), nunca pelo Content-Type.
        assertThat(extractor.supports(input("ofx_1x_sample.ofx"))).isTrue();
    }

    @Test
    void supportsAceitaOfx2xPorTagOfxNoInicioDoArquivo() {
        assertThat(extractor.supports(input("ofx_2x_sample.ofx"))).isTrue();
    }

    @Test
    void supportsRejeitaConteudoQueNaoEOfx() {
        ExtractionInput csvLike = new ExtractionInput(
                "data,valor\n2026-01-01,10.00\n".getBytes(StandardCharsets.UTF_8),
                "extrato.csv", "text/csv", ImportMode.NEW_TRANSACTIONS);
        assertThat(extractor.supports(csvLike)).isFalse();
    }

    @Test
    void extraiOfx1xSgmlComDuasTransacoesEFallbackDeDescricaoParaName() {
        NormalizedBatchDTO batch = extractor.extract(input("ofx_1x_sample.ofx"));

        assertThat(batch.sourceType()).isEqualTo(ImportSourceType.OFX);
        assertThat(batch.extractorUsed()).isEqualTo("ofx_parser_v1");
        assertThat(batch.transactions()).hasSize(2);

        NormalizedTransactionDTO compra = batch.transactions().get(0);
        assertThat(compra.fields().get("amount").value()).isEqualTo(new java.math.BigDecimal("55.90"));
        assertThat(compra.fields().get("amount").confidence()).isEqualByComparingTo("1.0");
        // TRNAMT negativo → debit (o sinal é dado pelo banco, não inferência).
        assertThat(compra.fields().get("direction").value()).isEqualTo("debit");
        assertThat(compra.fields().get("transaction_date").value()).isEqualTo("2026-07-05");
        // MEMO presente → description usa MEMO, não NAME.
        assertThat(compra.fields().get("description").value()).isEqualTo("Compra debito");
        assertThat(compra.fields().get("external_id").value()).isEqualTo("OFX1X-0001");
        assertThat(compra.fields().get("currency").value()).isEqualTo("BRL");
        assertThat(compra.overallConfidence()).isEqualByComparingTo("1.0");

        NormalizedTransactionDTO salario = batch.transactions().get(1);
        assertThat(salario.fields().get("amount").value()).isEqualTo(new java.math.BigDecimal("1500.00"));
        // TRNAMT positivo → credit.
        assertThat(salario.fields().get("direction").value()).isEqualTo("credit");
        // Sem MEMO → cai para NAME.
        assertThat(salario.fields().get("description").value()).isEqualTo("SALARIO TESTE");
        // DTPOSTED sem componente de hora (YYYYMMDD puro) — mesmo parser cobre os dois formatos.
        assertThat(salario.fields().get("transaction_date").value()).isEqualTo("2026-07-10");
    }

    @Test
    void extraiOfx2xXmlComDtpostedContendoHoraETimezone() {
        NormalizedBatchDTO batch = extractor.extract(input("ofx_2x_sample.ofx"));

        assertThat(batch.transactions()).hasSize(1);
        NormalizedTransactionDTO tx = batch.transactions().get(0);
        // DTPOSTED = "20260712130000.000[-3:EST]" — só os 8 primeiros dígitos (a DATA) importam.
        assertThat(tx.fields().get("transaction_date").value()).isEqualTo("2026-07-12");
        assertThat(tx.fields().get("amount").value()).isEqualTo(new java.math.BigDecimal("89.90"));
        assertThat(tx.fields().get("direction").value()).isEqualTo("debit");
        assertThat(tx.fields().get("description").value()).isEqualTo("Compra cartao debito");
        assertThat(tx.fields().get("external_id").value()).isEqualTo("OFX2X-0001");
    }

    @Test
    void fitidDuplicadoNaoImpedeAExtracao() {
        // Dedup por FITID é responsabilidade do ImportService (Onda 4) — o extrator só EXTRAI;
        // nenhuma linha é descartada aqui.
        NormalizedBatchDTO batch = extractor.extract(input("ofx_fitid_duplicado.ofx"));

        assertThat(batch.transactions()).hasSize(2);
        assertThat(batch.transactions().get(0).fields().get("external_id").value())
                .isEqualTo(batch.transactions().get(1).fields().get("external_id").value());
    }

    @Test
    void arquivoSemStmttrnLancaExtractionException() {
        assertThatThrownBy(() -> extractor.extract(input("ofx_sem_stmttrn.ofx")))
                .isInstanceOf(ExtractionException.class);
    }
}
