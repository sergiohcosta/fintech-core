package com.fintech.api.service.imports.vision;

/**
 * Falha de DISPONIBILIDADE de um provider de visão — 429 (cota estourada), 5xx, timeout/erro de
 * transporte, 401/403 (autenticação) ou 400 (requisição rejeitada pelo transporte). É a Onda 4 do
 * plano "extração Gemini primário / Ollama fallback".
 *
 * <p><b>Distinta de {@link com.fintech.api.service.imports.ExtractionException}</b> (falha de
 * CONTEÚDO: imagem ilegível, extrato multi-transação #193, {@code amount} implausível) — a
 * distinção é o que decide se o {@code VisionExtractor} tenta o PRÓXIMO provider da lista ou
 * desiste na hora. Falha de disponibilidade é reversível tentando outro provider; falha de
 * conteúdo NÃO É — o próximo modelo veria a MESMA imagem ilegível ou o MESMO extrato fora de
 * escopo, pagaria latência dobrada e chegaria à mesma conclusão (spec: "Quando cair pro próximo").
 *
 * <p>A classificação (qual exceção nativa do SDK de cada provider vira esta) vive DENTRO de cada
 * {@code VisionModelClient} — só o cliente sabe o que um erro do seu SDK significa. Esta classe é
 * só o VEÍCULO da decisão já tomada; o {@code VisionExtractor} nunca reclassifica, só reage.
 */
public class VisionProviderUnavailableException extends RuntimeException {

    /** quota | unavailable | auth | rejected_input — mesmo vocabulário de {@code fallback_reason} (V28). */
    private final String reasonCode;

    /**
     * @param reasonCode classificação curta (ver {@link VisionProviderErrorClassifier}) — vai
     *                    direto para a coluna {@code fallback_reason}, então precisa já nascer
     *                    curta e sem dado sensível.
     * @param message    mensagem redigida por NÓS (nunca o texto cru do provider, nunca a chave
     *                    de API) — é o que aparece em log e, se todos os providers esgotarem,
     *                    também no {@code failureReason} exibido ao usuário.
     * @param cause       exceção nativa do SDK do provider — preservada só para stacktrace de
     *                    depuração; seu {@code getMessage()} NUNCA é repassado ao chamador.
     */
    public VisionProviderUnavailableException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
