package com.fintech.api.service.imports.vision;

import com.fintech.api.service.imports.ExtractionException;
import com.fintech.api.service.imports.LlmReceiptExtractionDTO;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
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
                new ByteArrayResource(new byte[] {1, 2, 3}),
                LlmReceiptExtractionDTO.class,
                null);

        assertThat(result).isEqualTo(fullReceipt());
    }

    @Test
    void falhaDoChatClientViraExtractionException() {
        ChatClient chatClient = chatClientThrowing(new RuntimeException("gemini indisponível (429)"));
        GeminiVisionClient client = new GeminiVisionClient(chatClient, "gemini-2.5-flash");

        assertThatThrownBy(() -> client.extract(
                "prompt fixo",
                MimeTypeUtils.parseMimeType("image/jpeg"),
                new ByteArrayResource(new byte[] {1, 2, 3}),
                LlmReceiptExtractionDTO.class,
                null))
                .isInstanceOf(ExtractionException.class);
    }

    // --- #194 — maxOutputTokens: null preserva o comportamento de antes (sem regressão no
    // caminho de comprovante); não-nulo aplica o teto via GoogleGenAiChatOptions.maxOutputTokens. ---

    @SuppressWarnings("unchecked")
    @Test
    void naoChamaOptionsQuandoMaxOutputTokensENulo() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(LlmReceiptExtractionDTO.class)).thenReturn(fullReceipt());

        GeminiVisionClient client = new GeminiVisionClient(chatClient, "gemini-2.5-flash");

        client.extract(
                "prompt fixo", MimeTypeUtils.parseMimeType("image/jpeg"),
                new ByteArrayResource(new byte[] {1, 2, 3}), LlmReceiptExtractionDTO.class, null);

        verify(requestSpec, org.mockito.Mockito.never())
                .options(org.mockito.ArgumentMatchers.any());
    }

    // Mocks explícitos (não deep-stub): precisamos verificar .options() numa instância
    // ESPECÍFICA da cadeia, e o deep-stub não garante a mesma referência entre a chamada real
    // de produção e a expressão usada no verify() (identidade de mock, não só tipo).
    @SuppressWarnings("unchecked")
    @Test
    void aplicaMaxOutputTokensQuandoInformado() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec afterOptions = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.ChatOptions.class)))
                .thenReturn(afterOptions);
        when(afterOptions.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(LlmReceiptExtractionDTO.class)).thenReturn(fullReceipt());

        GeminiVisionClient client = new GeminiVisionClient(chatClient, "gemini-2.5-flash");

        client.extract(
                "prompt fixo", MimeTypeUtils.parseMimeType("image/jpeg"),
                new ByteArrayResource(new byte[] {1, 2, 3}), LlmReceiptExtractionDTO.class, 4096);

        org.mockito.ArgumentCaptor<org.springframework.ai.chat.prompt.ChatOptions> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.ChatOptions.class);
        verify(requestSpec).options(captor.capture());
        assertThat(captor.getValue().getMaxTokens()).isEqualTo(4096);
    }

    // --- Onda 4 — classificação de disponibilidade (spec §3.2: "Quando cair pro próximo") ---

    /**
     * 429 (RESOURCE_EXHAUSTED, cota do free tier estourada) é o cenário CENTRAL da feature —
     * vira {@link VisionProviderUnavailableException} com reasonCode "quota" para o
     * {@code VisionExtractor} tentar o Ollama em seguida.
     */
    @Test
    void classifica429ComoIndisponibilidadePorCota() {
        ChatClient chatClient = chatClientThrowing(
                new ClientException(429, "RESOURCE_EXHAUSTED", "Quota exceeded for quota metric ..."));
        GeminiVisionClient client = new GeminiVisionClient(chatClient, "gemini-2.5-flash");

        assertThatThrownBy(() -> client.extract(
                "prompt fixo", MimeTypeUtils.parseMimeType("image/jpeg"), new ByteArrayResource(new byte[] {1}),
                LlmReceiptExtractionDTO.class, null))
                .isInstanceOf(VisionProviderUnavailableException.class)
                .extracting(e -> ((VisionProviderUnavailableException) e).reasonCode())
                .isEqualTo("quota");
    }

    /** 5xx (erro do lado do provider, não da nossa requisição) também é falha de disponibilidade. */
    @Test
    void classifica5xxComoIndisponibilidade() {
        ChatClient chatClient = chatClientThrowing(
                new ServerException(503, "UNAVAILABLE", "The model is overloaded. Please try again later."));
        GeminiVisionClient client = new GeminiVisionClient(chatClient, "gemini-2.5-flash");

        assertThatThrownBy(() -> client.extract(
                "prompt fixo", MimeTypeUtils.parseMimeType("image/jpeg"), new ByteArrayResource(new byte[] {1}),
                LlmReceiptExtractionDTO.class, null))
                .isInstanceOf(VisionProviderUnavailableException.class)
                .extracting(e -> ((VisionProviderUnavailableException) e).reasonCode())
                .isEqualTo("unavailable");
    }

    /**
     * 404 (ex.: nome de modelo inválido) NÃO está na lista da spec ("429/5xx/timeout/401/403/400")
     * — não é disponibilidade, é config quebrada. Continua ExtractionException, sem fallback (um
     * modelo mal configurado no Gemini não vira "tenta o Ollama").
     */
    @Test
    void statusForaDaListaDeDisponibilidadeContinuaExtractionException() {
        ChatClient chatClient = chatClientThrowing(new ClientException(404, "NOT_FOUND", "Model not found."));
        GeminiVisionClient client = new GeminiVisionClient(chatClient, "gemini-2.5-flash");

        assertThatThrownBy(() -> client.extract(
                "prompt fixo", MimeTypeUtils.parseMimeType("image/jpeg"), new ByteArrayResource(new byte[] {1}),
                LlmReceiptExtractionDTO.class, null))
                .isInstanceOf(ExtractionException.class)
                .isNotInstanceOf(VisionProviderUnavailableException.class);
    }

    /**
     * Regra inegociável do plano ("nunca logar a chave, nem parcialmente"): mesmo quando a
     * mensagem CRUA do provider ecoa algo parecido com uma chave (401 típico de credencial
     * inválida), a exceção que o client propaga é a NOSSA (redigida), nunca repassa
     * {@code e.getMessage()} do erro original.
     */
    @Test
    void mensagemDeIndisponibilidadeNaoContemAChaveDeApi() {
        String chaveFake = "AIzaSyFAKE1234567890abcdefFAKEKEY";
        ChatClient chatClient = chatClientThrowing(
                new ClientException(401, "UNAUTHENTICATED", "API key " + chaveFake + " is invalid or expired."));
        GeminiVisionClient client = new GeminiVisionClient(chatClient, "gemini-2.5-flash");

        assertThatThrownBy(() -> client.extract(
                "prompt fixo", MimeTypeUtils.parseMimeType("image/jpeg"), new ByteArrayResource(new byte[] {1}),
                LlmReceiptExtractionDTO.class, null))
                .isInstanceOf(VisionProviderUnavailableException.class)
                .extracting(Throwable::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .doesNotContain(chaveFake);
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
        client.extract("prompt fixo", mime, original, LlmReceiptExtractionDTO.class, null);

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
