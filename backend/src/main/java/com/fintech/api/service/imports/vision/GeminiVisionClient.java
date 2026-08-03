package com.fintech.api.service.imports.vision;

import com.fintech.api.service.imports.ExtractionException;
import com.fintech.api.service.imports.LlmReceiptExtractionDTO;
import org.springframework.ai.chat.client.ChatClient;
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
 * / Ollama fallback" ({@code @Order(10)}, abaixo do Ollama em 20 — a ORDEM de tentativa entre
 * providers é decidida pelo {@code VisionExtractor}/spec, ainda não implementada nesta Onda:
 * hoje o extrator usa "primeiro da lista", e a lista ordenada por {@code @Order} já entrega o
 * Gemini primeiro quando ele existe).
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
 */
@Component
@Order(10)
@ConditionalOnExpression("!'${spring.ai.google.genai.api-key:}'.isBlank()")
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
    public LlmReceiptExtractionDTO extract(String prompt, MimeType mimeType, Resource imageResource) {
        try {
            // Diferente do Ollama, o client do Gemini não precisa do truque de Resource com
            // getFilename() preenchido (isso é uma exigência específica do multipart do Ollama,
            // documentada no OllamaVisionClient) — o SDK do Gemini serializa mídia como base64
            // inline, sem depender de nome de arquivo.
            return chatClient.prompt()
                    .user(u -> u.text(prompt).media(mimeType, imageResource))
                    .call()
                    .entity(LlmReceiptExtractionDTO.class);
        } catch (Exception e) {
            // Qualquer falha do provider/parse vira ExtractionException → hoje isso ainda derruba
            // o batch (FAILED); a política de "cair pro Ollama só em falha de DISPONIBILIDADE"
            // (429/5xx/timeout/401/403/400) é da Onda 4 — aqui a porta só devolve o dado cru ou
            // propaga a falha, sem decidir fallback (spec: guarda-corpo fica no VisionExtractor).
            throw new ExtractionException("Falha ao extrair dados da imagem via modelo de visão (Gemini).", e);
        }
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
