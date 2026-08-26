package com.fintech.api.service.imports.vision;

import com.fintech.api.service.imports.ExtractionException;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * Implementação de {@link VisionModelClient} sobre o Gemini (Google AI Studio, tier free) —
 * provider PRIMÁRIO de extração por visão a partir da Onda 2 do plano "extração Gemini primário
 * / Ollama fallback" ({@code @Order(10)}, abaixo do Ollama em 20). A partir da Onda 4, o
 * {@code VisionExtractor} tenta os clients NESSA ordem e cai para o próximo só quando este lança
 * {@link VisionProviderUnavailableException} (ver {@link #classifyAvailability}).
 *
 * <p><b>Gate de disponibilidade (§ "Gemini sem chave: bean não existe"):</b> a anotação óbvia
 * seria {@code @ConditionalOnProperty(name = "spring.ai.google.genai.api-key")} — mas ela NÃO
 * funciona aqui, e vale registrar por quê (armadilha real, encontrada testando, não suposição):
 * {@code @ConditionalOnProperty} sem {@code havingValue} só olha se a chave existe no
 * {@code Environment} ({@code containsProperty}), não se o VALOR é não-vazio. Como
 * {@code application.properties} define
 * {@code spring.ai.google.genai.api-key=${GEMINI_API_KEY:}} (precisa do default vazio para o
 * autoconfigure do Gemini não estourar {@code PlaceholderResolutionException} quando a env var
 * não existe), a property SEMPRE está presente no Environment — só o VALOR é vazio. Resultado:
 * {@code @ConditionalOnProperty} bateria {@code true} mesmo sem chave, e o Gemini apareceria na
 * lista de {@code VisionModelClient} de um clone novo do repositório (exatamente o que a Onda
 * proíbe). {@code @ConditionalOnExpression} resolve o placeholder primeiro (texto) e SÓ DEPOIS
 * avalia SpEL sobre o resultado — dá para checar "não-vazio" de verdade, não apenas "existe".
 *
 * <p><b>Por que a expressão lê {@code GEMINI_API_KEY} (a env var crua) e não
 * {@code spring.ai.google.genai.api-key}</b> (a property derivada dela, revisão pós-Onda 2): é a
 * MESMA fonte que {@link com.fintech.api.config.GeminiAutoConfigExclusionPostProcessor} lê para
 * decidir se exclui a auto-configuration do Gemini inteira. Se este componente e aquele
 * post-processor lessem fontes diferentes que hoje só COINCIDEM (a property é
 * {@code ${GEMINI_API_KEY:}}), um cenário futuro onde algo popula
 * {@code spring.ai.google.genai.api-key} sem tocar a env var (ex.: {@code @TestPropertySource})
 * faria as duas decisões DIVERGIREM: o post-processor excluiria a auto-configuration (nenhum
 * {@code GoogleGenAiChatModel} no contexto) enquanto este componente tentaria criá-lo mesmo
 * assim — um {@code UnsatisfiedDependencyException} confuso, em vez da degradação silenciosa que
 * a Onda pretende. Ler a mesma env var nos dois lugares elimina essa divergência pela raiz. Os
 * dois PRECISAM mudar juntos se um dia decidirem de fontes diferentes — ver o javadoc espelhado
 * em {@code VisionAiConfig.geminiVisionChatClient}.
 */
@Component
@Order(10)
@ConditionalOnExpression("!'${GEMINI_API_KEY:}'.isBlank()")
public class GeminiVisionClient implements VisionModelClient {

    private final ChatClient chatClient;
    private final String model;

    public GeminiVisionClient(
            @Qualifier("geminiVisionChatClient") ChatClient chatClient,
            @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}") String model) {
        this.chatClient = chatClient;
        this.model = model;
    }

    @Override
    public <T> T extract(
            String prompt, MimeType mimeType, Resource imageResource, Class<T> responseType, Integer maxOutputTokens) {
        try {
            // Diferente do Ollama, o client do Gemini não precisa do truque de Resource com
            // getFilename() preenchido (isso é uma exigência específica do multipart do Ollama,
            // documentada no OllamaVisionClient) — o SDK do Gemini serializa mídia como base64
            // inline, sem depender de nome de arquivo.
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .user(u -> u.text(prompt).media(mimeType, imageResource));
            // maxOutputTokens=null preserva EXATAMENTE o comportamento de antes do #194 (nenhuma
            // .options() chamada) — só o caminho novo de extrato passa um teto explícito.
            // thinkingLevel(LOW): Gemini 3.x reserva parte do maxOutputTokens pra "pensar" antes
            // de responder (sem opção de desligar — só LOW/HIGH) — sem isso, a maior parte do teto
            // ia pro raciocínio interno e o JSON de saída truncava no meio (achado em produção,
            // 2026-08-25: extrato Itaú truncava mesmo em 8192 tokens). Tarefa é extração estruturada
            // de texto visível, não requer raciocínio — LOW maximiza o espaço pro payload real.
            if (maxOutputTokens != null) {
                spec = spec.options(GoogleGenAiChatOptions.builder()
                        .maxOutputTokens(maxOutputTokens)
                        .thinkingLevel(GoogleGenAiThinkingLevel.LOW)
                        .build());
            }
            return spec.call().entity(responseType);
        } catch (Exception e) {
            // Onda 4: classifica ANTES de decidir a exceção. Falha de DISPONIBILIDADE (cota, 5xx,
            // timeout, auth) vira VisionProviderUnavailableException — só essa dispara fallback pro
            // próximo provider da lista (VisionExtractor). Qualquer outra falha (parse do schema,
            // erro inesperado) continua ExtractionException — falha de conteúdo, sem fallback.
            String reasonCode = classifyAvailability(e);
            if (reasonCode != null) {
                // Mensagem redigida por NÓS — nunca o texto cru do provider (pode ecoar detalhe
                // interno da API) nem a chave (que nem aparece nestas exceções, mas não arriscamos:
                // só repassamos o código de status + classificação, nunca e.getMessage()).
                throw new VisionProviderUnavailableException(
                        reasonCode,
                        "Gemini indisponível (" + VisionProviderErrorClassifier.friendlyReason(reasonCode) + ").",
                        e);
            }
            throw new ExtractionException("Falha ao extrair dados da imagem via modelo de visão (Gemini).", e);
        }
    }

    /**
     * Desembrulha a exceção nativa do SDK do Gemini ({@code com.google.genai.errors}) e devolve a
     * classificação de disponibilidade, ou {@code null} se não for uma falha de disponibilidade
     * (nesse caso o chamador trata como falha de conteúdo).
     *
     * <p>{@link ApiException#code()} é o status HTTP devolvido pela API do Gemini — cobre tanto
     * {@code ClientException} (4xx) quanto {@code ServerException} (5xx), então basta checar o
     * supertipo. {@link GenAiIOException} embrulha falha de TRANSPORTE (timeout, conexão recusada)
     * — sem status HTTP nenhum, mas é indisponibilidade de qualquer forma.
     */
    private String classifyAvailability(Exception e) {
        if (e instanceof ApiException apiException) {
            return VisionProviderErrorClassifier.reasonForHttpStatus(apiException.code());
        }
        if (e instanceof GenAiIOException) {
            return VisionProviderErrorClassifier.REASON_UNAVAILABLE;
        }
        return null;
    }

    @Override
    public String providerId() {
        return "gemini";
    }

    @Override
    public String modelId() {
        return model;
    }
}
