package com.fintech.api.service.imports;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.math.BigDecimal;

/**
 * Formato de saída TIPADO que o {@code VisionExtractor} pede ao modelo via
 * {@code ChatClient.call().entity(LlmReceiptExtractionDTO.class)} — o Spring AI gera o JSON
 * Schema a partir deste record e instrui o modelo a preenchê-lo.
 *
 * <p><b>Por que um DTO separado do {@code NormalizedTransactionDTO}?</b> O DTO normalizado da
 * Fase 0 achatou {@code fields} num {@code Map<String, {value, confidence}>} para bater 1:1 com
 * as colunas de staging. Esse formato de mapa aninhado é ruim como schema de saída de LLM
 * (schema genérico, o modelo erra mais). Aqui usamos um record PLANO, um campo por dado do
 * comprovante + sua confiança — schema simples que o modelo de visão preenche melhor. O
 * {@code VisionExtractor} então MAPEIA este record para o {@code NormalizedBatchDTO} da Fase 0.
 *
 * <p>Este é um detalhe de implementação do extrator: nunca cruza a borda do controller. As
 * anotações {@code @JsonPropertyDescription} enriquecem o schema gerado, guiando o modelo.
 *
 * <p>Confiança por campo é {@link Double} de 0.0 a 1.0. O {@code amount} é o campo mais crítico
 * (dele deriva boa parte do {@code requires_review}); {@code direction} = "debit" (saída/despesa)
 * ou "credit" (entrada/receita).
 */
public record LlmReceiptExtractionDTO(

        @JsonPropertyDescription("Valor total lido na imagem, como número decimal com ponto (separador decimal). Sem símbolo de moeda. Leia os dígitos da imagem; não use números deste schema.")
        BigDecimal amount,

        @JsonPropertyDescription("Confiança de 0.0 a 1.0 na leitura do valor.")
        Double amountConfidence,

        @JsonPropertyDescription("Data da compra/transação no formato ISO yyyy-MM-dd (formato: 4 dígitos de ano, mês, dia). Leia a data da imagem, inclusive o ano EXATO mostrado — nunca altere nem presuma o ano.")
        String transactionDate,

        @JsonPropertyDescription("Confiança de 0.0 a 1.0 na leitura da data.")
        Double transactionDateConfidence,

        @JsonPropertyDescription("Nome do recebedor/estabelecimento (a empresa ou pessoa que recebeu o pagamento, ex.: o campo 'Recebedor'), NÃO o tipo da transação (não use 'Pix Enviado', 'Pagamento').")
        String description,

        @JsonPropertyDescription("Confiança de 0.0 a 1.0 na leitura da descrição.")
        Double descriptionConfidence,

        @JsonPropertyDescription("Direção do dinheiro: 'debit' para saída/despesa, 'credit' para entrada/receita. Comprovante de compra é 'debit'.")
        String direction,

        @JsonPropertyDescription("Confiança de 0.0 a 1.0 na leitura da direção.")
        Double directionConfidence,

        @JsonPropertyDescription("Método de pagamento se visível: pix, credito, debito, dinheiro, boleto. null se não identificado.")
        String paymentMethod,

        @JsonPropertyDescription("Confiança de 0.0 a 1.0 na leitura do método de pagamento.")
        Double paymentMethodConfidence,

        @JsonPropertyDescription("Confiança agregada de 0.0 a 1.0 na extração completa do comprovante.")
        Double overallConfidence) {}
