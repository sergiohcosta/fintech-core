package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extrator de visão — implementação da porta {@link TransactionExtractor} sobre o
 * {@code ChatClient} do Spring AI (adaptador Ollama por default, agnóstico por config).
 *
 * <p>Fluxo: monta um prompt fixo + a imagem ({@code .media()}), pede a saída TIPADA
 * ({@code .entity(LlmReceiptExtractionDTO.class)} — o Spring AI força/valida o SCHEMA), e então
 * <b>revalida a PLAUSIBILIDADE</b> do nosso lado (guarda-corpo §2.g): schema íntegro não garante
 * conteúdo são — o modelo pode alucinar um valor com formato válido. Só depois mapeia para o
 * {@link NormalizedBatchDTO} da Fase 0.
 *
 * <p>{@code requires_review} NÃO é decidido aqui (§2.f) — o {@code ImportService} deriva por
 * threshold. Este extrator só produz valores + confiança por campo.
 */
@Component
@Slf4j
public class VisionExtractor implements TransactionExtractor {

    private final ChatClient chatClient;
    private final String model;
    private final String extractorVersion;

    // Prompt fixo. Português alinhado ao domínio (comprovantes/recibos BR). Pedimos confiança
    // por campo E agregada — é o que alimenta o requires_review derivado depois. As instruções
    // de formato/schema JSON são anexadas automaticamente pelo Spring AI via .entity(...).
    private static final String PROMPT = """
            Você é um assistente que extrai dados de comprovantes financeiros (recibos, notas,
            comprovantes de PIX, faturas de compra). Analise a imagem e extraia APENAS os dados
            visíveis do comprovante. Não invente valores: se um campo não estiver legível na
            imagem, use confiança baixa (próxima de 0.0) para aquele campo.

            Regras:
            - amount: o valor monetário total, como número decimal com ponto (ex.: 127.50).
            - transactionDate: a data da transação no formato ISO yyyy-MM-dd.
            - description: nome do estabelecimento ou breve descrição do que foi pago.
            - direction: "debit" se for uma saída de dinheiro (compra, despesa, pagamento);
              "credit" se for uma entrada (recebimento, estorno). Comprovante de compra é "debit".
            - paymentMethod: pix, credito, debito, dinheiro ou boleto, se identificável.
            - Para cada campo, informe uma confiança de 0.0 a 1.0 na sua leitura.
            - overallConfidence: sua confiança agregada na extração completa.
            """;

    public VisionExtractor(
            ChatClient chatClient,
            @Value("${spring.ai.ollama.chat.options.model:qwen2.5vl}") String model,
            @Value("${import.vision.extractor-version:unknown}") String extractorVersion) {
        this.chatClient = chatClient;
        this.model = model;
        this.extractorVersion = extractorVersion;
    }

    @Override
    public NormalizedBatchDTO extract(byte[] imageBytes, String mimeType, ImportMode mode) {
        MimeType mime = MimeTypeUtils.parseMimeType(mimeType);
        Resource imageResource = new ByteArrayResource(imageBytes);

        long startNanos = System.nanoTime();
        LlmReceiptExtractionDTO raw;
        try {
            raw = chatClient.prompt()
                    .user(u -> u.text(PROMPT).media(mime, imageResource))
                    .call()
                    .entity(LlmReceiptExtractionDTO.class);
        } catch (Exception e) {
            // Qualquer falha do provider/parse vira ExtractionException → batch FAILED (fallback manual).
            throw new ExtractionException("Falha ao extrair dados da imagem via modelo de visão.", e);
        }
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        if (raw == null) {
            throw new ExtractionException("Modelo de visão não retornou dados estruturados.");
        }

        // Critério de saída: latência conhecida (no homelab o custo em $ é zero; medimos tempo).
        // Tokens não são expostos pelo caminho .entity(); latência é a métrica que importa aqui.
        log.info("Extração de visão concluída: model={}, extractorVersion={}, latencyMs={}, overallConfidence={}",
                model, extractorVersion, latencyMs, raw.overallConfidence());

        return toNormalizedBatch(raw, mode);
    }

    /**
     * GUARDA-CORPO (§2.g) + mapeamento. Revalida a plausibilidade e converte a saída plana do
     * modelo para o schema normalizado da Fase 0. O {@code amount} é obrigatório e plausível
     * (&gt; 0) — sem ele não há transação a lançar, então falha a extração. Data ilegível não
     * derruba a extração (o usuário completa na revisão), mas zera a confiança para exigir olho.
     */
    private NormalizedBatchDTO toNormalizedBatch(LlmReceiptExtractionDTO raw, ImportMode mode) {
        if (raw.amount() == null || raw.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ExtractionException(
                    "Valor extraído ausente ou não plausível (amount=" + raw.amount() + ").");
        }

        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();
        fields.put("amount", new StagedFieldValueDTO(raw.amount(), clampConfidence(raw.amountConfidence())));

        NormalizedDate date = normalizeDate(raw.transactionDate(), raw.transactionDateConfidence());
        fields.put("transaction_date", new StagedFieldValueDTO(date.isoValue(), date.confidence()));

        fields.put("description",
                new StagedFieldValueDTO(blankToNull(raw.description()), clampConfidence(raw.descriptionConfidence())));
        fields.put("direction",
                new StagedFieldValueDTO(normalizeDirection(raw.direction()), clampConfidence(raw.directionConfidence())));
        if (blankToNull(raw.paymentMethod()) != null) {
            fields.put("payment_method",
                    new StagedFieldValueDTO(raw.paymentMethod(), clampConfidence(raw.paymentMethodConfidence())));
        }

        NormalizedTransactionDTO tx = new NormalizedTransactionDTO(
                null,
                fields,
                null,  // suggested_category — heurística é Fase 4
                null,
                clampConfidence(raw.overallConfidence()),
                null,  // requires_review derivado no ImportService (§2.f), nunca pelo modelo
                null);

        // extractorUsed carrega a proveniência: qual modelo gerou o dado (ex.: vision_ollama_qwen2.5vl).
        String extractorUsed = "vision_ollama_" + model;
        return new NormalizedBatchDTO(mode, ImportSourceType.IMAGE, extractorUsed, extractorVersion, List.of(tx));
    }

    /** Normaliza para "debit"/"credit"; qualquer coisa não reconhecida cai em "debit" (compra é o caso comum). */
    private String normalizeDirection(String raw) {
        return "credit".equalsIgnoreCase(raw != null ? raw.trim() : null) ? "credit" : "debit";
    }

    /**
     * Parseia a data ISO. Se ausente/ilegível, devolve valor null com confiança 0.0 — força a
     * revisão sem derrubar a extração inteira (o usuário completa a data na tela de revisão).
     */
    private NormalizedDate normalizeDate(String rawDate, Double rawConfidence) {
        String trimmed = blankToNull(rawDate);
        if (trimmed == null) {
            return new NormalizedDate(null, BigDecimal.ZERO);
        }
        try {
            LocalDate parsed = LocalDate.parse(trimmed);
            return new NormalizedDate(parsed.toString(), clampConfidence(rawConfidence));
        } catch (DateTimeParseException e) {
            return new NormalizedDate(null, BigDecimal.ZERO);
        }
    }

    /** Confiança em [0,1]; null vira 0.0 (ausência = duvidoso). */
    private BigDecimal clampConfidence(Double confidence) {
        if (confidence == null) {
            return BigDecimal.ZERO;
        }
        double clamped = Math.max(0.0, Math.min(1.0, confidence));
        return BigDecimal.valueOf(clamped);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private record NormalizedDate(String isoValue, BigDecimal confidence) {}
}
