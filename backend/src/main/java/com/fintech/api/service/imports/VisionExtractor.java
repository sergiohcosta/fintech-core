package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.service.imports.vision.VisionModelClient;
import com.fintech.api.service.imports.vision.VisionProviderErrorClassifier;
import com.fintech.api.service.imports.vision.VisionProviderUnavailableException;
import lombok.extern.slf4j.Slf4j;
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
 * Extrator de visão — implementação da porta {@link TransactionExtractor}. A partir da Onda 1
 * (plano "extração Gemini primário / Ollama fallback") a chamada ao modelo em si vive atrás da
 * porta {@link VisionModelClient} — este extrator não sabe mais que existe um {@code ChatClient}
 * ou um Ollama; recebe uma {@link List} de clients ordenada por {@code @Order} (Gemini 10, Ollama
 * 20) e tenta em ordem.
 *
 * <p><b>Política de fallback (Onda 4, spec §3.2):</b> só uma falha de DISPONIBILIDADE
 * ({@link VisionProviderUnavailableException} — 429/5xx/timeout/401/403/400) dispara a tentativa
 * do PRÓXIMO client da lista. Qualquer outra exceção (incl. {@link ExtractionException} de
 * conteúdo — imagem ilegível, extrato multi-transação #193, {@code amount} implausível) propaga
 * IMEDIATAMENTE, sem tentar o próximo — falha de conteúdo repetida no outro modelo pagaria
 * latência dobrada pra chegar à MESMA conclusão, e mascararia o {@code failureReason} de #193. Essa
 * distinção é garantida por CONSTRUÇÃO: o guarda-corpo de plausibilidade só roda depois que um
 * client já "venceu" (retornou sem lançar), então é fisicamente impossível ele disparar fallback.
 *
 * <p>Fluxo: monta o prompt fixo + a imagem, delega ao {@link VisionModelClient} escolhido (que
 * devolve a saída TIPADA crua — {@link LlmReceiptExtractionDTO}), e então
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

    private final List<VisionModelClient> visionModelClients;
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
            List<VisionModelClient> visionModelClients,
            @Value("${import.vision.extractor-version:unknown}") String extractorVersion) {
        this.visionModelClients = visionModelClients;
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
        if (visionModelClients.isEmpty()) {
            // Config quebrada (nenhum client habilitado, ex.: sem GEMINI_API_KEY e sem Ollama) —
            // falha EXPLÍCITA, não um NullPointerException em visionModelClients.get(0).
            throw new ExtractionException("Nenhum provider de visão configurado.");
        }

        byte[] imageBytes = input.content();
        ImportMode mode = input.mode();
        // mimeType do cliente é só um HINT pro client montar o Resource — quem decide se é
        // imagem de verdade é o supports() por magic number, chamado antes pelo router.
        String mimeType = input.mimeType() != null ? input.mimeType() : "image/jpeg";
        MimeType mime = MimeTypeUtils.parseMimeType(mimeType);
        Resource imageResource = new ByteArrayResource(imageBytes);

        VisionModelClient winner = null;
        LlmReceiptExtractionDTO raw = null;
        long latencyMs = 0;

        // Proveniência do fallback (V28): só populada se ALGUM client tentado antes do vencedor
        // falhou por disponibilidade. fallbackFrom == null já responde "houve fallback?" (spec
        // §5.1) — guardamos o PRIMEIRO que falhou (com só 2 providers configurados hoje, é
        // exatamente "de quem" a extração precisou fugir).
        String fallbackFrom = null;
        String fallbackReason = null;
        VisionProviderUnavailableException lastFailure = null;

        for (VisionModelClient client : visionModelClients) {
            long startNanos = System.nanoTime();
            try {
                raw = client.extract(PROMPT, mime, imageResource);
                latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                winner = client;
                break;
            } catch (VisionProviderUnavailableException e) {
                // Só ESTA exceção é capturada aqui — qualquer outra (ExtractionException de
                // conteúdo, erro inesperado) propaga direto pro chamador, sem tentar o próximo
                // client. É essa seletividade do catch que implementa a regra central da Onda.
                if (fallbackFrom == null) {
                    fallbackFrom = client.providerId();
                    fallbackReason = e.reasonCode();
                }
                lastFailure = e;

                // 401/403 é ERROR, não WARN: degradar pro próximo provider é UX melhor que
                // falhar de vez, mas silenciar um provider morto por credencial inválida faria
                // ninguém perceber que ele está fora do ar (spec: "401/403 — cai pro fallback,
                // mas loga ERROR").
                if (VisionProviderErrorClassifier.REASON_AUTH.equals(e.reasonCode())) {
                    log.error("Provider de visão indisponível por falha de autenticação, tentando "
                                    + "próximo (se houver): provider={}, reason={}, motivo={}",
                            client.providerId(), e.reasonCode(), e.getMessage());
                } else {
                    log.warn("Provider de visão indisponível, tentando próximo (se houver): "
                                    + "provider={}, reason={}, motivo={}",
                            client.providerId(), e.reasonCode(), e.getMessage());
                }
            }
        }

        if (winner == null) {
            // Todos os providers da lista falharam por disponibilidade — o motivo do ÚLTIMO erro
            // (spec: "ExtractionException com o motivo do ÚLTIMO"), numa mensagem redigida por
            // nós (nunca o texto cru do provider — lastFailure.getMessage() já é nosso, não do
            // SDK; ver VisionProviderUnavailableException).
            String reason = lastFailure != null ? lastFailure.getMessage() : "nenhum provider disponível";
            throw new ExtractionException(
                    "Não foi possível extrair os dados da imagem: todos os provedores de visão "
                            + "configurados estão indisponíveis no momento (" + reason + ").",
                    lastFailure);
        }

        if (raw == null) {
            throw new ExtractionException("Modelo de visão não retornou dados estruturados.");
        }

        // Critério de saída: latência conhecida (no homelab o custo em $ é zero; medimos tempo).
        // Tokens não são expostos pelo caminho .entity(); latência é a métrica que importa aqui.
        log.info("Extração de visão concluída: provider={}, model={}, extractorVersion={}, latencyMs={}, "
                        + "overallConfidence={}, fallbackFrom={}, fallbackReason={}",
                winner.providerId(), winner.modelId(), extractorVersion, latencyMs, raw.overallConfidence(),
                fallbackFrom, fallbackReason);

        return toNormalizedBatch(raw, mode, winner, latencyMs, fallbackFrom, fallbackReason);
    }

    /**
     * GUARDA-CORPO (§2.g) + mapeamento. Revalida a plausibilidade e converte a saída plana do
     * modelo para o schema normalizado da Fase 0. O {@code amount} é obrigatório e plausível
     * (&gt; 0) — sem ele não há transação a lançar, então falha a extração. Data ilegível não
     * derruba a extração (o usuário completa na revisão), mas zera a confiança para exigir olho.
     */
    private NormalizedBatchDTO toNormalizedBatch(
            LlmReceiptExtractionDTO raw, ImportMode mode, VisionModelClient client, long latencyMs,
            String fallbackFrom, String fallbackReason) {
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

        // extractorUsed carrega a proveniência: qual provider+modelo gerou o dado
        // (ex.: vision_ollama_qwen2.5vl) — mesmo formato de antes da Onda 1, agora vindo do client
        // VENCEDOR (o que efetivamente respondeu, não necessariamente o primeiro tentado).
        String extractorUsed = "vision_" + client.providerId() + "_" + client.modelId();
        // Proveniência estruturada (V28): quem MEDE é o extrator (aqui), quem GRAVA é o
        // ImportService — a fronteira "extrator não toca banco" não muda. fallbackFrom/Reason
        // (Onda 4) só vêm preenchidos quando um provider anterior falhou por disponibilidade.
        return new NormalizedBatchDTO(
                mode, ImportSourceType.IMAGE, extractorUsed, extractorVersion, List.of(tx),
                client.providerId(), client.modelId(), (int) latencyMs, fallbackFrom, fallbackReason);
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
