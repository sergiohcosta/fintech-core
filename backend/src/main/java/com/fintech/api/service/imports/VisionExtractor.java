package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 *
 * <p>{@code @Order(LOWEST)}: é o ÚLTIMO extrator do funil do {@link ExtractionRouter} (roadmap
 * §1.2 — padrão universal → genérico → IA). IA é cara e ambígua; só entra quando nenhum parser
 * determinístico (OFX, CSV) reconheceu o arquivo.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class VisionExtractor implements TransactionExtractor {

    // Magic numbers dos formatos de imagem aceitos — supports() decide por BYTES, nunca pelo
    // mimeType do cliente (o browser erra/mente o Content-Type com frequência).
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] GIF = {0x47, 0x49, 0x46};
    private static final byte[] WEBP_RIFF = {0x52, 0x49, 0x46, 0x46};

    private final ChatClient chatClient;
    private final String model;
    private final String extractorVersion;

    // Formato pt-BR de data — fallback quando o modelo devolve dd/MM/yyyy apesar do pedido de ISO.
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    // Mensagem exibida ao usuário quando a imagem está fora do escopo da Fase 1 (#193). Fica
    // pública porque é contrato de comportamento coberto por teste, não texto solto.
    public static final String MULTIPLE_TRANSACTIONS_MESSAGE =
            "Esta imagem parece conter vários lançamentos (extrato ou fatura). Por enquanto só "
                    + "conseguimos ler um comprovante por vez — envie o comprovante de uma única "
                    + "transação ou lance manualmente.";

    // Prompt fixo. Português alinhado ao domínio (comprovantes/recibos BR). Pedimos confiança
    // por campo E agregada — é o que alimenta o requires_review derivado depois. As instruções
    // de formato/schema JSON são anexadas automaticamente pelo Spring AI via .entity(...).
    private static final String PROMPT = """
            Você é um assistente que extrai dados de comprovantes financeiros (recibos, notas,
            comprovantes de PIX, faturas de compra). Analise a imagem e extraia APENAS os dados
            visíveis. Leia os dígitos DIRETAMENTE da imagem — NUNCA use números escritos nestas
            instruções. Se um campo não estiver legível, use confiança baixa (próxima de 0.0)
            para aquele campo.

            Regras:
            - amount: o VALOR TOTAL exato mostrado na imagem (o campo "Valor total", "Valor" ou
              similar), como número decimal com ponto. No Brasil a vírgula é o separador decimal
              e o ponto é separador de milhar: converta removendo os separadores de milhar e
              trocando a vírgula decimal por ponto. Leia os dígitos da imagem; nunca invente nem
              copie números deste texto.
            - transactionDate: a data da transação no formato ISO yyyy-MM-dd. Copie a data EXATA
              da imagem, INCLUSIVE o ano — nunca altere nem presuma o ano.
            - description: o nome do RECEBEDOR/estabelecimento (a empresa ou pessoa), NÃO o tipo
              da transação (não use "Pix Enviado", "Pagamento", "Compra aprovada" e afins).
            - direction: "debit" para saída de dinheiro (compra, despesa, pagamento, Pix enviado);
              "credit" para entrada (recebimento, estorno, Pix recebido). Compra é "debit".
            - paymentMethod: pix, credito, debito, dinheiro ou boleto, conforme a imagem.
            - Para cada campo, informe uma confiança de 0.0 a 1.0 na sua leitura.
            - overallConfidence: sua confiança agregada na extração completa.
            - multipleTransactionsDetected: true SOMENTE se a imagem mostrar uma LISTA de vários
              lançamentos (extrato bancário, fatura de cartão, histórico) — várias linhas, cada
              uma com sua própria data e seu próprio valor. Um comprovante único é false, mesmo
              que mostre vários números (taxas, saldo, total). Conte as LINHAS de lançamento.
            """;

    public VisionExtractor(
            ChatClient chatClient,
            @Value("${spring.ai.ollama.chat.options.model:llama3.2-vision}") String model,
            @Value("${import.vision.extractor-version:unknown}") String extractorVersion) {
        this.chatClient = chatClient;
        this.model = model;
        this.extractorVersion = extractorVersion;
    }

    @Override
    public boolean supports(ExtractionInput input) {
        byte[] content = input.content();
        return startsWith(content, JPEG) || startsWith(content, PNG) || startsWith(content, GIF)
                || startsWith(content, WEBP_RIFF);
    }

    private boolean startsWith(byte[] content, byte[] magic) {
        if (content == null || content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.IMAGE;
    }

    @Override
    public String extractorVersion() {
        return extractorVersion;
    }

    @Override
    public NormalizedBatchDTO extract(ExtractionInput input) {
        byte[] imageBytes = input.content();
        ImportMode mode = input.mode();
        // mimeType do cliente é só um HINT pro Spring AI montar o Resource — quem decide se é
        // imagem de verdade é o supports() por magic number, chamado antes pelo router.
        String mimeType = input.mimeType() != null ? input.mimeType() : "image/jpeg";
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
        // ORDEM IMPORTA: a recusa por multi-transação vem ANTES da validação de valor. Num print
        // de extrato o modelo escolhe uma linha arbitrária e devolve um amount perfeitamente
        // plausível — se o check de valor rodasse primeiro, o caso fora de escopo passaria e as
        // demais linhas seriam descartadas em silêncio (#193).
        //
        // TRUE.equals (e não `raw.multipleTransactionsDetected()`) porque null = modelo que não
        // preencheu a flag: nesse caso extrai normalmente. Ausência de sinal não é sinal de recusa
        // — senão trocaríamos perda silenciosa de dado por recusa indevida de comprovante válido.
        if (Boolean.TRUE.equals(raw.multipleTransactionsDetected())) {
            throw new ExtractionException(MULTIPLE_TRANSACTIONS_MESSAGE);
        }

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
        // O modelo às vezes devolve a data em pt-BR (dd/MM/yyyy) apesar do pedido de ISO.
        // Tenta ISO e depois o formato brasileiro — recuperar a data é melhor que descartá-la;
        // só zera a confiança quando NENHUM formato conhecido casa (aí o usuário completa na revisão).
        for (DateTimeFormatter fmt : List.of(DateTimeFormatter.ISO_LOCAL_DATE, BR_DATE)) {
            try {
                LocalDate parsed = LocalDate.parse(trimmed, fmt);
                return new NormalizedDate(parsed.toString(), clampConfidence(rawConfidence));
            } catch (DateTimeParseException ignored) {
                // tenta o próximo formato
            }
        }
        return new NormalizedDate(null, BigDecimal.ZERO);
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
