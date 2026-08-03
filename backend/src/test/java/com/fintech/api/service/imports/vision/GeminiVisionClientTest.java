package com.fintech.api.service.imports.vision;

import com.fintech.api.service.imports.ExtractionException;
import com.fintech.api.service.imports.LlmReceiptExtractionDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.math.BigDecimal;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unitário do {@link GeminiVisionClient} com o {@code ChatClient} MOCKADO (deep stubs) — a
 * suíte NUNCA bate na API do Gemini de verdade. Espelha {@code OllamaVisionClientTest}, exceto
 * pelo teste de filename do multipart: essa exigência é ESPECÍFICA do Ollama (documentada no
 * {@code OllamaVisionClient}), o client do Gemini repassa o {@code Resource} recebido sem
 * reembalar.
 */
class GeminiVisionClientTest {

    private LlmReceiptExtractionDTO fullReceipt() {
        return new LlmReceiptExtractionDTO(
                new BigDecimal("127.50"), 0.98,
                "2026-06-28", 0.95,
                "PADARIA SAO JOSE", 0.90,
                "debit", 0.99,
                "pix", 0.85,
                0.94, false);
    }

    @SuppressWarnings("unchecked")
    private ChatClient chatClientReturning(LlmReceiptExtractionDTO fixture) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                .user(any(Consumer.class))
                .call()
                .entity(LlmReceiptExtractionDTO.class))
                .thenReturn(fixture);
        return chatClient;
    }

    @SuppressWarnings("unchecked")
    private ChatClient chatClientThrowing(RuntimeException error) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                .user(any(Consumer.class))
                .call()
                .entity(LlmReceiptExtractionDTO.class))
                .thenThrow(error);
        return chatClient;
    }

    @Test
    void providerIdEModelIdRefletemOGeminiEOModeloConfigurado() {
        GeminiVisionClient client = new GeminiVisionClient(mock(ChatClient.class), "gemini-2.5-flash");

        assertThat(client.providerId()).isEqualTo("gemini");
        assertThat(client.modelId()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void extractDelegaAoChatClientEDevolveASaidaCrua() {
        ChatClient chatClient = chatClientReturning(fullReceipt());
        GeminiVisionClient client = new GeminiVisionClient(chatClient, "gemini-2.5-flash");

        LlmReceiptExtractionDTO result = client.extract(
                "prompt fixo",
                MimeTypeUtils.parseMimeType("image/jpeg"),
                new ByteArrayResource(new byte[] {1, 2, 3}));

        assertThat(result).isEqualTo(fullReceipt());
    }

    @Test
    void falhaDoChatClientViraExtractionException() {
        ChatClient chatClient = chatClientThrowing(new RuntimeException("gemini indisponível (429)"));
        GeminiVisionClient client = new GeminiVisionClient(chatClient, "gemini-2.5-flash");

        assertThatThrownBy(() -> client.extract(
                "prompt fixo",
                MimeTypeUtils.parseMimeType("image/jpeg"),
                new ByteArrayResource(new byte[] {1, 2, 3})))
                .isInstanceOf(ExtractionException.class);
    }

    /**
     * Diferente do Ollama, o Gemini NÃO precisa de um {@code Resource} com filename — o
     * {@code Resource} original chega intacto ao {@code .media(...)}, sem reembalagem.
     */
    @SuppressWarnings("unchecked")
    @Test
    void resourceOriginalEEnviadoSemReembalagem() {
        ChatClient chatClient = chatClientReturning(fullReceipt());
        GeminiVisionClient client = new GeminiVisionClient(chatClient, "gemini-2.5-flash");
        Resource original = new ByteArrayResource(new byte[] {1, 2, 3});

        MimeType mime = MimeTypeUtils.parseMimeType("image/png");
        client.extract("prompt fixo", mime, original);

        ArgumentCaptor<Consumer<ChatClient.PromptUserSpec>> userSpec = ArgumentCaptor.forClass(Consumer.class);
        verify(chatClient.prompt(), atLeastOnce()).user(userSpec.capture());

        ChatClient.PromptUserSpec spec = mock(ChatClient.PromptUserSpec.class);
        when(spec.text(anyString())).thenReturn(spec);
        userSpec.getValue().accept(spec);

        ArgumentCaptor<Resource> resource = ArgumentCaptor.forClass(Resource.class);
        verify(spec).media(any(MimeType.class), resource.capture());
        assertThat(resource.getValue()).isSameAs(original);
    }
}
