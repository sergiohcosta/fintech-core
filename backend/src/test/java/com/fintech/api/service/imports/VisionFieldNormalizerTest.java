package com.fintech.api.service.imports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unitário PURO do pós-processamento determinístico (#241). Sem Spring, sem mock — as três
 * funções do {@link VisionFieldNormalizer} são puras justamente para que a garantia de
 * vocabulário fechado seja testável sem nenhum atrito. Os casos vêm do PoC do
 * {@code qwen3-vl:8b-instruct}: datas em dd/mm/aaaa e variantes, e paymentMethod/direction em
 * texto livre com acento e caixa livres.
 */
class VisionFieldNormalizerTest {

    // --- normalizeDate ---

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
            // Formatos exigidos pelo #241, um caso por forma + as variações que o PoC observou.
            "2026-06-28, 2026-06-28",        // ISO (o formato PEDIDO — precisa continuar passando reto)
            "28/06/2026, 2026-06-28",        // dd/MM/yyyy (o mais comum em comprovante BR)
            "28/6/2026, 2026-06-28",         // d/M/yyyy (mês/dia sem zero à esquerda)
            "8/6/2026, 2026-06-08",          // d/M/yyyy com dia de 1 dígito
            "28.06.26, 2026-06-28",          // dd.MM.yy (ponto + ano de 2 dígitos → 20xx)
            "28-06-2026, 2026-06-28",        // dd-MM-yyyy (hífen no lugar da barra)
            "01/01/27, 2027-01-01",          // ano de 2 dígitos vira 20xx, não 19xx
            "' 28/06/2026 ', 2026-06-28",    // trim antes de casar
    })
    void normalizeDateAceitaFormatosBrEIso(String entrada, String esperado) {
        assertThat(VisionFieldNormalizer.normalizeDate(entrada)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "\"{0}\" → null")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "sem-data", "hoje", "28/06/2026a", "28062026", "31/02/2026", "13/13/2026",
            "2026-13-01", "2026-02-31", "12/31/2026"})
    void normalizeDateDevolveNullQuandoNaoParseia(String entrada) {
        // Inclui datas IMPOSSÍVEIS (31/02) e formato não suportado (US: 12/31/2026) — o regex
        // aceita a forma, o LocalDate.of é quem rejeita o calendário; os dois juntos compõem o contrato.
        assertThat(VisionFieldNormalizer.normalizeDate(entrada)).isNull();
    }

    // --- normalizePaymentMethod ---

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
            "pix, pix",
            "PIX, pix",
            "Pix eletrônico, pix",
            "Cartao de Credito, credito",    // o caso do PoC: texto livre com acento ausente
            "crédito, credito",              // e com acento — a caixa/acento não pode importar
            "CREDITO, credito",
            "cartão de débito, debito",
            "Numerário, dinheiro",           // sinônimo BR que aparece em recibo
            "dinheiro, dinheiro",
            "Espécie, dinheiro",
            "Boleto Bancário, boleto",
            "Transferência, transferencia",
            "TED, transferencia",
            "DOC, transferencia",
            "Transferência/TED/DOC, transferencia",  // o PoC devolveu a tríade inteira junto
    })
    void normalizePaymentMethodMapeiaTextoLivreParaVocabularioFechado(String entrada, String esperado) {
        assertThat(VisionFieldNormalizer.normalizePaymentMethod(entrada)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "\"{0}\" → null")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "cripto", "cartao", "vale-refeicao", "outro"})
    void normalizePaymentMethodDevolveNullParaForaDoVocabulario(String entrada) {
        // Vocabulário é FECHADO por decisão do #241: fora dele não se chuta o mais próximo.
        assertThat(VisionFieldNormalizer.normalizePaymentMethod(entrada)).isNull();
    }

    // --- normalizeDirection ---

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
            "debit, debit",
            "Debit, debit",
            "debito, debit",
            "DÉBITO, debit",      // variação PT com acento+caixa alta
            "credit, credit",
            "Credit, credit",
            "credito, credit",
            "Crédito, credit",
            "'  Crédito  ', credit",
    })
    void normalizeDirectionGaranteVocabularioFechado(String entrada, String esperado) {
        assertThat(VisionFieldNormalizer.normalizeDirection(entrada)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "\"{0}\" → null")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "saída", "entrada", "n/d", "revertido"})
    void normalizeDirectionDevolveNullParaNaoReconhecido(String entrada) {
        // Decisão #241: NÃO cair mais em "debit" como default — direção fabricada é pior que
        // ausente em campo de dinheiro. O extrator preserva o original com confiança 0.0.
        assertThat(VisionFieldNormalizer.normalizeDirection(entrada)).isNull();
    }

    @Test
    void funcoesSaoPurasNullSafe() {
        assertThat(VisionFieldNormalizer.normalizeDate(null)).isNull();
        assertThat(VisionFieldNormalizer.normalizePaymentMethod(null)).isNull();
        assertThat(VisionFieldNormalizer.normalizeDirection(null)).isNull();
    }
}
