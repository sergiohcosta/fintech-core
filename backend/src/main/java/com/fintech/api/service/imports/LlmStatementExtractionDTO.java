package com.fintech.api.service.imports;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.math.BigDecimal;
import java.util.List;

/**
 * Formato de saída TIPADO para o caminho de EXTRATO (#194 — extração multi-transação a partir
 * de imagem única, spec {@code 2026-08-13-extracao-multi-transacao-imagem-design.md} §6.1).
 * Pedido numa SEGUNDA chamada ao mesmo {@code VisionModelClient} vencedor, só quando a 1ª
 * chamada (schema de {@link LlmReceiptExtractionDTO}) sinaliza
 * {@code multipleTransactionsDetected=true} — o Spring AI vincula o JSON Schema de saída
 * ANTES da chamada, então não dá pra pedir os dois formatos na mesma chamada.
 *
 * <p>Totais separados por direção (não um único total ambíguo, §2.a da spec): a maioria dos
 * prints de extrato não imprime total nenhum (ambos nullable), e quando imprime, geralmente é
 * só de um lado (ex.: "Total de saídas"). Sem {@code paymentMethod} por linha (raramente
 * impresso em extrato, diferente do comprovante) e sem {@code multipleTransactionsDetected}
 * (aqui já sabemos que é lista — a flag só existe no schema da 1ª chamada).
 */
public record LlmStatementExtractionDTO(

        @JsonPropertyDescription("Lista de TODOS os lançamentos visíveis na imagem, na ordem em que aparecem. "
                + "Cada linha do extrato/histórico com sua própria data e valor vira um item aqui. NUNCA inclua "
                + "linha de saldo, cabeçalho, rodapé ou total como se fosse um lançamento.")
        List<Line> lines,

        @JsonPropertyDescription("Total de débitos (saídas) declarado na imagem, se houver um valor impresso "
                + "somando as saídas do período (ex.: 'Total de saídas'). null se a imagem não mostrar esse total "
                + "— não estime nem calcule, só copie se estiver impresso.")
        BigDecimal declaredTotalDebits,

        @JsonPropertyDescription("Total de créditos (entradas) declarado na imagem, se houver um valor impresso "
                + "somando as entradas do período (ex.: 'Total de entradas'). null se a imagem não mostrar esse "
                + "total — não estime nem calcule, só copie se estiver impresso.")
        BigDecimal declaredTotalCredits,

        @JsonPropertyDescription("Confiança de 0.0 a 1.0 na extração completa da lista.")
        Double overallConfidence) {

    public record Line(

            @JsonPropertyDescription("Valor da transação como número decimal com ponto, sempre positivo — a "
                    + "direção informa o sinal, não o valor. Leia os dígitos da imagem; nunca invente.")
            BigDecimal amount,

            @JsonPropertyDescription("Confiança de 0.0 a 1.0 na leitura do valor.")
            Double amountConfidence,

            @JsonPropertyDescription("Data da transação no formato ISO yyyy-MM-dd. Leia a data EXATA mostrada na "
                    + "linha, inclusive o ano quando visível — nunca presuma um ano que não está na imagem.")
            String transactionDate,

            @JsonPropertyDescription("Confiança de 0.0 a 1.0 na leitura da data.")
            Double transactionDateConfidence,

            @JsonPropertyDescription("Descrição/estabelecimento da linha, como aparece no extrato.")
            String description,

            @JsonPropertyDescription("Confiança de 0.0 a 1.0 na leitura da descrição.")
            Double descriptionConfidence,

            @JsonPropertyDescription("Direção do dinheiro NESTA linha: 'debit' para saída/despesa, 'credit' para "
                    + "entrada/receita. Leia pela seção/rótulo da linha, nunca infira só pelo sinal do valor.")
            String direction,

            @JsonPropertyDescription("Confiança de 0.0 a 1.0 na leitura da direção.")
            Double directionConfidence) {}
}
