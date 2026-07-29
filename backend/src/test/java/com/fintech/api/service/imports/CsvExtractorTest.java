package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unitário PURO (sem Spring) do {@link CsvExtractor} — prova as heurísticas determinísticas
 * (charset/BOM, delimitador, sinônimo de header, decimal pt-BR vs. padrão, direção pelo sinal)
 * com fixtures fictícias em {@code src/test/resources/imports/}.
 */
class CsvExtractorTest {

    private final CsvExtractor extractor = new CsvExtractor("v1-test");

    private static byte[] fixture(String filename) {
        try (InputStream in = CsvExtractorTest.class.getResourceAsStream("/imports/" + filename)) {
            if (in == null) {
                throw new IllegalStateException("Fixture não encontrada: " + filename);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ExtractionInput input(String filename) {
        return new ExtractionInput(fixture(filename), filename, "text/csv", ImportMode.NEW_TRANSACTIONS);
    }

    @Test
    void extraiCsvComVirgulaDecimalPontoEDataIso() {
        NormalizedBatchDTO batch = extractor.extract(input("csv_virgula_iso.csv"));

        assertThat(batch.sourceType()).isEqualTo(ImportSourceType.CSV);
        assertThat(batch.extractorUsed()).isEqualTo("csv_generic_v1");
        assertThat(batch.transactions()).hasSize(2);

        NormalizedTransactionDTO primeira = batch.transactions().get(0);
        assertThat(primeira.fields().get("amount").value()).isEqualTo(new BigDecimal("55.90"));
        assertThat(primeira.fields().get("amount").confidence()).isEqualByComparingTo("1.0");
        assertThat(primeira.fields().get("transaction_date").value()).isEqualTo("2026-07-01");
        assertThat(primeira.fields().get("description").value()).isEqualTo("PADARIA TESTE");
        // Header casou por nome ("description") → confiança máxima na identificação da coluna.
        assertThat(primeira.fields().get("description").confidence()).isEqualByComparingTo("1.0");
        // Valor positivo → credit (inferência pelo sinal, por isso confiança 0.7, não 1.0).
        assertThat(primeira.fields().get("direction").value()).isEqualTo("credit");
        assertThat(primeira.fields().get("direction").confidence()).isEqualByComparingTo("0.7");

        NormalizedTransactionDTO segunda = batch.transactions().get(1);
        // Valor negativo → debit; amount é sempre gravado em módulo (mesma convenção do OFX/Fase 1).
        assertThat(segunda.fields().get("amount").value()).isEqualTo(new BigDecimal("1200.00"));
        assertThat(segunda.fields().get("direction").value()).isEqualTo("debit");
    }

    @Test
    void extraiCsvPtBrComBomPontoEVirgulaEDecimalComVirgula() {
        NormalizedBatchDTO batch = extractor.extract(input("csv_pontovirgula_ptbr.csv"));

        assertThat(batch.transactions()).hasSize(2);

        NormalizedTransactionDTO primeira = batch.transactions().get(0);
        // "28/07/2026" (dd/MM/yyyy) → ISO "2026-07-28". BOM não vazou pro valor da 1ª célula.
        assertThat(primeira.fields().get("transaction_date").value()).isEqualTo("2026-07-28");
        assertThat(primeira.fields().get("amount").value()).isEqualTo(new BigDecimal("55.90"));
        assertThat(primeira.fields().get("description").value()).isEqualTo("Padaria Teste");

        NormalizedTransactionDTO segunda = batch.transactions().get(1);
        // "-1.200,00" — ponto de milhar + vírgula decimal (pt-BR) → 1200.00, não 1.20000.
        assertThat(segunda.fields().get("amount").value()).isEqualTo(new BigDecimal("1200.00"));
        assertThat(segunda.fields().get("direction").value()).isEqualTo("debit");
    }

    @Test
    void extraiCampoComVirgulaDentroDeAspasSemQuebrarColunas() {
        NormalizedBatchDTO batch = extractor.extract(input("csv_aspas_com_delimitador.csv"));

        assertThat(batch.transactions()).hasSize(1);
        NormalizedTransactionDTO tx = batch.transactions().get(0);
        // A vírgula DENTRO das aspas não pode ser lida como delimitador de coluna.
        assertThat(tx.fields().get("description").value()).isEqualTo("Compra em, Loja Teste");
        assertThat(tx.fields().get("amount").value()).isEqualTo(new BigDecimal("42.50"));
    }

    @Test
    void supportsRejeitaHeaderIrreconhecivel() {
        assertThat(extractor.supports(input("csv_header_irreconhecivel.csv"))).isFalse();
    }

    @Test
    void supportsAceitaHeaderComSinonimosReconhecidos() {
        assertThat(extractor.supports(input("csv_virgula_iso.csv"))).isTrue();
        assertThat(extractor.supports(input("csv_pontovirgula_ptbr.csv"))).isTrue();
    }

    @Test
    void linhaInvalidaNaoDerrubaOBatchEZeraConfiancaDaquelaLinha() {
        NormalizedBatchDTO batch = extractor.extract(input("csv_linha_invalida.csv"));

        // 3 linhas de dado → 3 transações, mesmo com a do meio ilegível.
        assertThat(batch.transactions()).hasSize(3);

        NormalizedTransactionDTO ruim = batch.transactions().get(1);
        assertThat(ruim.fields().get("amount").value()).isNull();
        assertThat(ruim.fields().get("amount").confidence()).isEqualByComparingTo("0");
        assertThat(ruim.fields().get("transaction_date").value()).isNull();
        assertThat(ruim.overallConfidence()).isEqualByComparingTo("0");

        NormalizedTransactionDTO boa = batch.transactions().get(0);
        assertThat(boa.fields().get("amount").value()).isEqualTo(new BigDecimal("30.00"));
    }
}
