package com.fintech.api.service.imports.templates;

import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NubankExtratoTemplateTest {

    private final NubankExtratoTemplate template = new NubankExtratoTemplate();

    @Test
    void matchesReconheceCnpjNubankEHeaderDeMovimentacoes() {
        String texto = "algum texto\n18.236.120/0001-58\nMovimentações\nfim";
        assertThat(template.matches(texto)).isTrue();
    }

    @Test
    void matchesRejeitaTextoSemCnpjOuSemHeader() {
        assertThat(template.matches("Movimentações sem cnpj nenhum")).isFalse();
        assertThat(template.matches("18.236.120/0001-58 sem o header certo")).isFalse();
    }

    @Test
    void templateIdENubankExtratoV1() {
        assertThat(template.templateId()).isEqualTo("nubank_extrato_v1");
    }

    @Test
    void parseReconheceEntradaDeLinhaUnica() {
        String texto = "Movimentações\n"
                + "05 JUL 2026 Total de entradas + 4.708,35\n"
                + "Resgate RDB 4.708,35\n"
                + "Total de saídas - 0,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("transaction_date").value()).isEqualTo("2026-07-05");
        assertThat((BigDecimal) tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("4708.35"));
        assertThat(tx.fields().get("description").value()).isEqualTo("Resgate RDB");
        assertThat(tx.fields().get("direction").value()).isEqualTo("credit");
    }

    @Test
    void parseReconheceSaidaComContraparteMultilinha() {
        String texto = "Movimentações\n"
                + "10 JUL 2026 Total de entradas + 0,00\n"
                + "Total de saídas - 593,21\n"
                + "Transferência enviada pelo Pix MERCADO PAGO INSTITUICAO DE PAGAMENTO\n"
                + "LTDA - 10.573.521/0001-91 - MERCADO PAGO IP\n"
                + "LTDA. (0323) Agência: 1 Conta: 1488917887-3\n"
                + "593,21\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat((BigDecimal) tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("593.21"));
        assertThat(tx.fields().get("direction").value()).isEqualTo("debit");
        assertThat(tx.fields().get("description").value())
                .isEqualTo("Transferência enviada pelo Pix MERCADO PAGO INSTITUICAO DE PAGAMENTO "
                        + "LTDA - 10.573.521/0001-91 - MERCADO PAGO IP "
                        + "LTDA. (0323) Agência: 1 Conta: 1488917887-3");
    }

    @Test
    void parseDistingueDirecaoPelaSecaoCorrenteNaoPeloRotulo() {
        // "Resgate RDB" aparece nos dois lados — só a seção ("Total de entradas"/"saídas")
        // corrente decide a direção.
        String texto = "Movimentações\n"
                + "06 JUL 2026 Total de entradas + 250,00\n"
                + "Resgate RDB 250,00\n"
                + "Total de saídas - 250,00\n"
                + "Aplicação RDB 250,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(2);
        assertThat(transacoes.get(0).fields().get("direction").value()).isEqualTo("credit");
        assertThat(transacoes.get(1).fields().get("direction").value()).isEqualTo("debit");
    }

    @Test
    void parseNaoGeraTransacaoParaAsLinhasDeSubtotal() {
        String texto = "Movimentações\n"
                + "05 JUL 2026 Total de entradas + 4.708,35\n"
                + "Resgate RDB 4.708,35\n"
                + "Total de saídas - 4.708,35\n"
                + "Transferência enviada pelo Pix SERGIO HENRIQUE COSTA (Transferência enviada) 4.708,35\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        // 2 transações reais (1 entrada, 1 saída) — as 2 linhas "Total de X" não contam.
        assertThat(transacoes).hasSize(2);
    }
}
