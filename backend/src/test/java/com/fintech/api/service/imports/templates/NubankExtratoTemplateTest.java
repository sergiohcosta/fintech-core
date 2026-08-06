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

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, new byte[0]);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("transaction_date").value()).isEqualTo("2026-07-05");
        assertThat((BigDecimal) tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("4708.35"));
        assertThat(tx.fields().get("description").value()).isEqualTo("Resgate RDB");
        assertThat(tx.fields().get("direction").value()).isEqualTo("credit");
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

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, new byte[0]);

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
                + "Transferência enviada pelo Pix FULANO DE TAL (Transferência enviada) 4.708,35\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, new byte[0]);

        // 2 transações reais (1 entrada, 1 saída) — as 2 linhas "Total de X" não contam.
        assertThat(transacoes).hasSize(2);
    }

    @Test
    void parseDescartaLinhaDeDecoracaoSemAcumularNaProximaTransacao() {
        String texto = "Movimentações\n"
                + "10 JUL 2026 Total de entradas + 151,91\n"
                + "Transferência recebida pelo Pix FULANO DE TAL - CAIXA 151,91\n"
                + "ECONOMICA FEDERAL (0104) Agência: 0001 Conta:\n"
                + "12345-6\n"
                + "Total de saídas - 0,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, new byte[0]);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat((BigDecimal) tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("151.91"));
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
                + "UNIBANCO S.A. (0341) Agência: 0002 Conta: 65432-1\n"
                + "Total de saídas - 450,00\n"
                + "Resgate RDB 1.400,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, new byte[0]);

        assertThat(transacoes).hasSize(2);
        // A primeira transação fecha na própria linha, sem absorver o rodapé de página que
        // vem depois. A segunda ("Resgate RDB") tem descrição limpa, sem lixo de rodapé.
        assertThat(transacoes.get(0).fields().get("description").value())
                .isEqualTo("Transferência recebida pelo Pix CICLANO DA SILVA - ITAÚ");
        assertThat(transacoes.get(1).fields().get("description").value()).isEqualTo("Resgate RDB");
        assertThat((BigDecimal) transacoes.get(1).fields().get("amount").value())
                .isEqualByComparingTo(new BigDecimal("1400.00"));
    }

    @Test
    void parseIgnoraLinhaDeRodapeAntesDaSecaoDeMovimentacoesQueHerdariaEstadoDeUmResumo() {
        // PDFs reais de extrato Nubank trazem uma página de "Resumo" ANTES da seção detalhada
        // "Movimentações" — com seu próprio bloco de data + "Total de saídas" + valor (para
        // ilustrar o saldo do período). Sem escopar a leitura ao que vem depois do header
        // "Movimentações", esse resumo é lido pela MESMA state machine, seta data/direção
        // correntes, e uma linha de rodapé com padrão monetário ("Saldo em conta") vira
        // transação fantasma — mesmo a real seção "Movimentações" nunca tendo começado ainda.
        String texto = "05 JUL 2026 Total de saídas - 999,99\n"
                + "Saldo em conta 12.345,67\n"
                + "Movimentações\n"
                + "05 JUL 2026 Total de entradas + 4.708,35\n"
                + "Resgate RDB 4.708,35\n"
                + "Total de saídas - 0,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, new byte[0]);

        // Só a transação real, dentro da seção "Movimentações" — o resumo anterior (e a
        // fantasma "Saldo em conta" que ele geraria sem o escopo) não deve aparecer.
        assertThat(transacoes).hasSize(1);
        assertThat(transacoes.get(0).fields().get("description").value()).isEqualTo("Resgate RDB");
    }
}
