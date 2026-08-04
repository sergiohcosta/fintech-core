package com.fintech.api.service.imports.vision;

import com.fintech.api.service.imports.ExtractionException;
import com.fintech.api.service.imports.LlmReceiptExtractionDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.ResourceAccessException;

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
 * Unitário do {@link OllamaVisionClient} com o {@code ChatClient} MOCKADO (deep stubs) — a suíte
 * NUNCA bate no Ollama real. Extraído do antigo {@code VisionExtractorTest} na Onda 1: cobre só a
 * MECÂNICA do {@code ChatClient} (montagem do prompt/imagem, filename do multipart, mapeamento de
 * falha do provider) — o guarda-corpo de plausibilidade (#193, validação de amount) NÃO é
 * responsabilidade deste client e é testado em {@code VisionExtractorTest}.
 */
class OllamaVisionClientTest {

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
    void providerIdEModelIdRefletemOOllamaEOModeloConfigurado() {
        OllamaVisionClient client = new OllamaVisionClient(mock(ChatClient.class), "qwen2.5vl");

        assertThat(client.providerId()).isEqualTo("ollama");
        assertThat(client.modelId()).isEqualTo("qwen2.5vl");
    }

    @Test
    void extractDelegaAoChatClientEDevolveASaidaCrua() {
        ChatClient chatClient = chatClientReturning(fullReceipt());
        OllamaVisionClient client = new OllamaVisionClient(chatClient, "qwen2.5vl");

        LlmReceiptExtractionDTO result = client.extract(
                "prompt fixo",
                MimeTypeUtils.parseMimeType("image/jpeg"),
                new ByteArrayResource(new byte[] {1, 2, 3}));

        assertThat(result).isEqualTo(fullReceipt());
    }

    @Test
    void falhaDoChatClientViraExtractionException() {
        ChatClient chatClient = chatClientThrowing(new RuntimeException("ollama indisponível"));
        OllamaVisionClient client = new OllamaVisionClient(chatClient, "qwen2.5vl");

        assertThatThrownBy(() -> client.extract(
                "prompt fixo",
                MimeTypeUtils.parseMimeType("image/jpeg"),
                new ByteArrayResource(new byte[] {1, 2, 3})))
                .isInstanceOf(ExtractionException.class);
    }

    // --- Revisão final de branch: o OllamaApi.Builder do Spring AI instala por padrão o
    // RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER, que converte 4xx/5xx do Ollama em
    // TransientAiException/NonTransientAiException ANTES de chegar ao client — o
    // HttpStatusCodeException que os testes anteriores injetavam nunca é lançado na prática.
    // Estes testes injetam as exceções REAIS que o Spring AI produz. ---

    @Test
    void classificaTransientAiExceptionDoOllamaComoIndisponibilidade() {
        ChatClient chatClient = chatClientThrowing(
                new TransientAiException("Service Unavailable"));
        OllamaVisionClient client = new OllamaVisionClient(chatClient, "qwen2.5vl");

        assertThatThrownBy(() -> client.extract(
                "prompt fixo", MimeTypeUtils.parseMimeType("image/jpeg"), new ByteArrayResource(new byte[] {1})))
                .isInstanceOf(VisionProviderUnavailableException.class)
                .extracting(e -> ((VisionProviderUnavailableException) e).reasonCode())
                .isEqualTo("unavailable");
    }

    /** Timeout/conexão recusada com o Ollama do homelab (sem resposta HTTP nenhuma) — indisponibilidade. */
    @Test
    void classificaTimeoutDoOllamaComoIndisponibilidade() {
        ChatClient chatClient = chatClientThrowing(
                new ResourceAccessException("Read timed out", new java.net.SocketTimeoutException("Read timed out")));
        OllamaVisionClient client = new OllamaVisionClient(chatClient, "qwen2.5vl");

        assertThatThrownBy(() -> client.extract(
                "prompt fixo", MimeTypeUtils.parseMimeType("image/jpeg"), new ByteArrayResource(new byte[] {1})))
                .isInstanceOf(VisionProviderUnavailableException.class)
                .extracting(e -> ((VisionProviderUnavailableException) e).reasonCode())
                .isEqualTo("unavailable");
    }

    /**
     * NonTransientAiException (4xx não-retryável, ex.: modelo não puxado no Ollama local) é falha
     * de CONTEÚDO da requisição, não de disponibilidade do provedor — sem status code embutido não
     * dá para diferenciar "rejeitado" de "não encontrado", mas repetir com outro provider não
     * resolveria nenhum dos dois. Ver comentário de {@code classifyAvailability}.
     */
    @Test
    void classificaNonTransientAiExceptionDoOllamaComoFalhaDeConteudo() {
        ChatClient chatClient = chatClientThrowing(
                new NonTransientAiException("Not Found"));
        OllamaVisionClient client = new OllamaVisionClient(chatClient, "qwen2.5vl");

        assertThatThrownBy(() -> client.extract(
                "prompt fixo", MimeTypeUtils.parseMimeType("image/jpeg"), new ByteArrayResource(new byte[] {1})))
                .isInstanceOf(ExtractionException.class)
                .isNotInstanceOf(VisionProviderUnavailableException.class);
    }

    /**
     * O {@code Resource} enviado ao Ollama PRECISA ter filename: sem ele o Spring AI monta um
     * multipart malformado e o Ollama rejeita com erro de zlib — a extração inteira quebrava em
     * runtime, sem nada no código dizendo que o filename era obrigatório. Extensão coerente com o
     * mimeType; mimeType desconhecido cai em .jpg (o caso mais comum de comprovante).
     */
    @Test
    void resourceEnviadoAoOllamaCarregaFilenameCoerenteComOMimeType() {
        assertThat(capturedFilename("image/png")).isEqualTo("receipt.png");
        assertThat(capturedFilename("image/gif")).isEqualTo("receipt.gif");
        assertThat(capturedFilename("image/webp")).isEqualTo("receipt.webp");
        assertThat(capturedFilename("image/jpeg")).isEqualTo("receipt.jpg");
        assertThat(capturedFilename("image/tiff")).isEqualTo("receipt.jpg");
    }

    /**
     * Reexecuta o {@code Consumer} que o client passou para {@code .user(...)} contra um
     * {@code PromptUserSpec} mockado — é assim que se enxerga o Resource que o Spring AI receberia,
     * sem subir provider nenhum.
     */
    @SuppressWarnings("unchecked")
    private String capturedFilename(String mimeType) {
        ChatClient chatClient = chatClientReturning(fullReceipt());
        OllamaVisionClient client = new OllamaVisionClient(chatClient, "qwen2.5vl");

        MimeType mime = MimeTypeUtils.parseMimeType(mimeType);
        client.extract("prompt fixo", mime, new ByteArrayResource(new byte[] {1, 2, 3}));

        // atLeastOnce: a própria montagem do stub (when(...user(any())...)) já conta uma invocação;
        // getValue() devolve a ÚLTIMA capturada, que é o Consumer real passado pelo extract().
        ArgumentCaptor<Consumer<ChatClient.PromptUserSpec>> userSpec = ArgumentCaptor.forClass(Consumer.class);
        verify(chatClient.prompt(), atLeastOnce()).user(userSpec.capture());

        ChatClient.PromptUserSpec spec = mock(ChatClient.PromptUserSpec.class);
        when(spec.text(anyString())).thenReturn(spec);
        userSpec.getValue().accept(spec);

        ArgumentCaptor<Resource> resource = ArgumentCaptor.forClass(Resource.class);
        verify(spec).media(any(MimeType.class), resource.capture());
        return resource.getValue().getFilename();
    }
}
