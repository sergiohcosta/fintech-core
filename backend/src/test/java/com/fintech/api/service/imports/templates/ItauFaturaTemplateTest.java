package com.fintech.api.service.imports.templates;

import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItauFaturaTemplateTest {

    private final ItauFaturaTemplate template = new ItauFaturaTemplate();

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
            "SERGIO HENRIQUE COSTA\nVencimento 10/03/2025\n"
            + "60.872.504/0001-23\n";

    @Test
    void parseReconheceTransacaoSimplesDentroDaSecaoDeLancamentos() {
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/02 SUBWAY FAZENDINHA 49,00\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("transaction_date").value()).isEqualTo("2025-02-03");
        assertThat((BigDecimal) tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("49.00"));
        assertThat(tx.fields().get("description").value()).isEqualTo("SUBWAY FAZENDINHA");
        assertThat(tx.fields().get("direction").value()).isEqualTo("debit");
    }

    @Test
    void parseRemoveMarcadorDeParcelaColadoAoEstabelecimento() {
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "28/11 Foco Aluguel de Ca04/06 112,67\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(1);
        assertThat(transacoes.get(0).fields().get("description").value()).isEqualTo("Foco Aluguel de Ca");
        // Mês do lançamento (11) > mês de vencimento (03) → ano anterior ao de vencimento.
        assertThat(transacoes.get(0).fields().get("transaction_date").value()).isEqualTo("2024-11-28");
    }

    @Test
    void parseIgnoraComprasParceladasDeProximasFaturas() {
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "28/11 Foco Aluguel de Ca04/06 112,67\n"
                + "Compras parceladas - próximas faturas\n"
                + "28/11 Foco Aluguel de Ca05/06 112,67\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        // Só a parcela do ciclo corrente (04/06) — a próxima parcela (05/06), que aparece na
        // seção de preview de faturas futuras, não vira transação deste batch.
        assertThat(transacoes).hasSize(1);
    }

    @Test
    void parseTrataValorNegativoComoCreditEEstornoDeAnuidade() {
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/03 ESTORNO DE ANUIDADE DIF - 29,50\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat((BigDecimal) tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("29.50"));
        assertThat(tx.fields().get("direction").value()).isEqualTo("credit");
        assertThat(tx.fields().get("description").value()).isEqualTo("ESTORNO DE ANUIDADE DIF");
    }
}
