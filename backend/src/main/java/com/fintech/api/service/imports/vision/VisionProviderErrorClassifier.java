package com.fintech.api.service.imports.vision;

/**
 * Mapeamento comum de código HTTP → classificação de indisponibilidade (spec §3.2, plano "extração
 * Gemini primário / Ollama fallback", Onda 4). Compartilhado por {@link GeminiVisionClient} e
 * {@link OllamaVisionClient}: a DECISÃO de qual exceção nativa do SDK vira falha de disponibilidade
 * continua no cliente (só ele sabe desembrulhar o erro do seu transporte) — esta classe só evita
 * duplicar a TABELA "qual status vira qual motivo", que é a mesma para os dois providers.
 *
 * <p>Só os status listados na spec ("Quando cair pro próximo: 429/5xx/timeout/401/403/400") viram
 * falha de disponibilidade. Qualquer outro (ex.: 404, 422) devolve {@code null} — o cliente chamador
 * trata como falha de CONTEÚDO ({@code ExtractionException}), não dispara fallback.
 */
public final class VisionProviderErrorClassifier {

    // Públicas (não package-private): o VisionExtractor, em com.fintech.api.service.imports, usa
    // REASON_AUTH para decidir ERROR vs. WARN no log de fallback (spec: "401/403 — loga ERROR").
    public static final String REASON_QUOTA = "quota";
    public static final String REASON_UNAVAILABLE = "unavailable";
    public static final String REASON_AUTH = "auth";
    public static final String REASON_REJECTED_INPUT = "rejected_input";

    private VisionProviderErrorClassifier() {
    }

    /**
     * @return o código de classificação para o status HTTP informado, ou {@code null} se o status
     *         não é considerado falha de disponibilidade.
     */
    public static String reasonForHttpStatus(int statusCode) {
        if (statusCode == 429) {
            return REASON_QUOTA;
        }
        if (statusCode == 401 || statusCode == 403) {
            return REASON_AUTH;
        }
        if (statusCode == 400) {
            return REASON_REJECTED_INPUT;
        }
        if (statusCode >= 500 && statusCode <= 599) {
            return REASON_UNAVAILABLE;
        }
        return null;
    }

    /** Texto curto em PT-BR para compor a mensagem redigida por nós (nunca o texto cru do provider). */
    public static String friendlyReason(String reasonCode) {
        return switch (reasonCode) {
            case REASON_QUOTA -> "limite de cota atingido";
            case REASON_AUTH -> "falha de autenticação com o provedor";
            case REASON_REJECTED_INPUT -> "requisição rejeitada pelo provedor";
            case REASON_UNAVAILABLE -> "provedor indisponível no momento";
            default -> "provedor indisponível no momento";
        };
    }
}
