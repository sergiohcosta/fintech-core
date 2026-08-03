package com.fintech.api.service.imports.vision;

import com.fintech.api.service.imports.ExtractionException;
import com.fintech.api.service.imports.LlmReceiptExtractionDTO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Implementação de {@link VisionModelClient} sobre o {@code ChatClient} do Spring AI, adaptador
 * Ollama do homelab (extraída do {@code VisionExtractor} na Onda 1 — refatoração pura, sem
 * mudança de comportamento).
 *
 * <p>{@code @Order(20)}: mesma numeração usada pelo {@code ExtractionRouter} para os extratores
 * determinísticos — reserva o espaço abaixo (10) para o provider gerenciado (Gemini) que a Onda
 * seguinte introduz como primário.
 *
 * <p><b>Onda 2:</b> o {@code ChatClient} injetado agora é {@link Qualifier qualified} —
 * {@code VisionAiConfig} passou a expor um {@code ChatClient} por provider (não mais um único
 * genérico), porque com dois starters Spring AI no classpath a auto-config do
 * {@code ChatClient.Builder} deixa de resolver (candidato de {@code ChatModel} não é mais único).
 */
@Component
@Order(20)
public class OllamaVisionClient implements VisionModelClient {

    private final ChatClient chatClient;
    private final String model;

    public OllamaVisionClient(
            @Qualifier("ollamaVisionChatClient") ChatClient chatClient,
            @Value("${spring.ai.ollama.chat.options.model:llama3.2-vision}") String model) {
        this.chatClient = chatClient;
        this.model = model;
    }

    @Override
    public LlmReceiptExtractionDTO extract(String prompt, MimeType mimeType, Resource imageResource) {
        // ByteArrayResource anônimo com getFilename() pra Spring AI serializar corretamente
        // (sem filename, o multipart fica malformado → Ollama rejeita com zlib error). É um
        // conhecimento caro (custou depuração de runtime) e é ESPECÍFICO deste provider — outro
        // client (ex.: API do Gemini) tem seu próprio empacotamento e não precisa deste truque.
        String filename = resolveFilename(mimeType);
        Resource namedResource = wrapWithFilename(imageResource, filename);

        try {
            return chatClient.prompt()
                    .user(u -> u.text(prompt).media(mimeType, namedResource))
                    .call()
                    .entity(LlmReceiptExtractionDTO.class);
        } catch (Exception e) {
            // Onda 4: mesma política do GeminiVisionClient (classificação vive no cliente, não
            // aqui reaproveitado de outro provider — cada um desembrulha a exceção do SEU SDK).
            // Falha de DISPONIBILIDADE aqui é a exceção nativa do RestClient que o Spring AI usa
            // para falar com o servidor Ollama: HttpStatusCodeException (4xx/5xx) e
            // ResourceAccessException (timeout, conexão recusada — sem resposta HTTP nenhuma).
            String reasonCode = classifyAvailability(e);
            if (reasonCode != null) {
                // Mensagem redigida por NÓS — nunca o texto cru do provider nem a chave (Ollama
                // não usa chave, mas a regra é a mesma dos dois clients por consistência).
                throw new VisionProviderUnavailableException(
                        reasonCode,
                        "Ollama indisponível (" + VisionProviderErrorClassifier.friendlyReason(reasonCode) + ").",
                        e);
            }
            throw new ExtractionException("Falha ao extrair dados da imagem via modelo de visão.", e);
        }
    }

    /**
     * @return a classificação de disponibilidade, ou {@code null} se a falha não é de
     *         disponibilidade (o chamador trata como falha de conteúdo).
     */
    private String classifyAvailability(Exception e) {
        if (e instanceof HttpStatusCodeException httpError) {
            return VisionProviderErrorClassifier.reasonForHttpStatus(httpError.getStatusCode().value());
        }
        if (e instanceof ResourceAccessException) {
            return VisionProviderErrorClassifier.REASON_UNAVAILABLE;
        }
        return null;
    }

    @Override
    public String providerId() {
        return "ollama";
    }

    @Override
    public String modelId() {
        return model;
    }

    /**
     * Reembala o conteúdo do {@code Resource} recebido num {@link ByteArrayResource} com
     * {@code getFilename()} preenchido. {@code getContentAsByteArray()} funciona para qualquer
     * {@code Resource} (não só {@code ByteArrayResource}), então este client não precisa assumir
     * o tipo concreto que o {@code VisionExtractor} constrói.
     */
    private Resource wrapWithFilename(Resource original, String filename) {
        byte[] content;
        try {
            content = original.getContentAsByteArray();
        } catch (java.io.IOException e) {
            throw new ExtractionException("Falha ao ler o conteúdo da imagem para envio ao modelo de visão.", e);
        }
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private String resolveFilename(MimeType mime) {
        if (mime == null) {
            return "receipt.jpg";
        }
        String subtype = mime.getSubtype().toLowerCase();
        return switch (subtype) {
            case "png" -> "receipt.png";
            case "gif" -> "receipt.gif";
            case "webp" -> "receipt.webp";
            default -> "receipt.jpg";
        };
    }
}
