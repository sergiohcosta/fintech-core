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
    void extraiValorComSimboloDeMoeda() {
        // Bug real: "R$ 74,87" sobrevivia à troca de separador ("R$ 74.87"), estourava
        // NumberFormatException em toda a coluna e derrubava o batch inteiro (FAILED).
        NormalizedBatchDTO batch = extractor.extract(input("csv_simbolo_moeda.csv"));

        assertThat(batch.transactions()).hasSize(2);
        NormalizedTransactionDTO primeira = batch.transactions().get(0);
        assertThat(primeira.fields().get("amount").value()).isEqualTo(new BigDecimal("74.87"));
        assertThat(primeira.fields().get("amount").confidence()).isEqualByComparingTo("1.0");
        assertThat(primeira.fields().get("direction").value()).isEqualTo("credit");

        NormalizedTransactionDTO segunda = batch.transactions().get(1);
        assertThat(segunda.fields().get("amount").value()).isEqualTo(new BigDecimal("1200.00"));
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

    /**
     * Bug real de produção: um CSV de banco com aspas soltas (fora do padrão RFC 4180) faz o
     * commons-csv lançar {@code CSVException} empacotada em {@link UncheckedIOException} — NÃO
     * em {@link IOException} — dentro de {@code getRecords()}. O catch original só pegava
     * {@code IOException}, então o erro subia cru até o {@code GlobalExceptionHandler} como 500.
     * Extração deve degradar com uma exceção NOSSA (ou supports()=false), nunca vazar a exceção
     * da lib como erro interno não tratado.
     */
    /**
     * Bug real de produção: um CSV de banco com aspas soltas (fora do padrão RFC 4180) faz o
     * commons-csv lançar {@code CSVException} empacotada em {@link UncheckedIOException} — NÃO
     * em {@link IOException} — ao parsear o arquivo inteiro de uma vez. Cada linha agora é
     * parseada ISOLADAMENTE: a linha com aspa solta vira "linha ruim" (confiança 0, mesma régua
     * de dado ilegível), mas a OUTRA linha do mesmo arquivo é extraída normalmente — o arquivo
     * inteiro não é mais descartado por causa de uma linha malformada.
     */
    @Test
    void linhaComAspaSoltaViraLinhaRuimIsoladaSemDerrubarAsDemais() {
        NormalizedBatchDTO batch = extractor.extract(input("csv_aspas_soltas_invalidas.csv"));

        assertThat(batch.transactions()).hasSize(2);

        NormalizedTransactionDTO ruim = batch.transactions().get(0);
        assertThat(ruim.fields().get("amount").value()).isNull();
        assertThat(ruim.fields().get("amount").confidence()).isEqualByComparingTo("0");
        assertThat(ruim.overallConfidence()).isEqualByComparingTo("0");

        NormalizedTransactionDTO boa = batch.transactions().get(1);
        assertThat(boa.fields().get("amount").value()).isEqualTo(new BigDecimal("30.00"));
        assertThat(boa.fields().get("description").value()).isEqualTo("OUTRA LOJA");
    }

    /**
     * Campo entre aspas com QUEBRA DE LINHA dentro é RFC 4180 válido e vale por UM registro —
     * quebrar por linha física geraria uma transação fantasma (a "segunda metade" da descrição).
     */
    @Test
    void deveTratarCampoComQuebraDeLinhaEntreAspasComoUmaTransacaoSo() {
        NormalizedBatchDTO batch = extractor.extract(input("csv_campo_multilinha.csv"));

        assertThat(batch.transactions()).hasSize(2);

        NormalizedTransactionDTO primeira = batch.transactions().get(0);
        assertThat(primeira.fields().get("amount").value()).isEqualTo(new BigDecimal("42.50"));
        assertThat(primeira.fields().get("description").value())
                .isEqualTo("MERCADO BOM PRECO\nFILIAL CENTRO");

        NormalizedTransactionDTO segunda = batch.transactions().get(1);
        assertThat(segunda.fields().get("amount").value()).isEqualTo(new BigDecimal("30.00"));
        assertThat(segunda.fields().get("description").value()).isEqualTo("OUTRA LOJA");
    }
}
