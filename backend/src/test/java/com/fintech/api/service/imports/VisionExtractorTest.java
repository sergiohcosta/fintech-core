package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unitário do extrator de visão com o {@link ChatClient} MOCKADO (deep stubs) — a suíte NUNCA
 * bate no Ollama real. Prova o mapeamento saída-do-modelo → schema normalizado e o guarda-corpo
 * (§2.g): schema íntegro não basta, o conteúdo é revalidado do nosso lado.
 */
class VisionExtractorTest {

    private static final byte[] IMAGE = {1, 2, 3};

    /** Constrói um extrator cujo ChatClient devolve {@code fixture} na chamada .entity(...). */
    @SuppressWarnings("unchecked")
    private VisionExtractor visionReturning(LlmReceiptExtractionDTO fixture) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                .user(any(Consumer.class))
                .call()
                .entity(LlmReceiptExtractionDTO.class))
                .thenReturn(fixture);
        return new VisionExtractor(chatClient, "qwen2.5vl", "2026-07-24");
    }

    /** Constrói um extrator cujo ChatClient lança na chamada .entity(...) (falha de provider). */
    @SuppressWarnings("unchecked")
    private VisionExtractor visionThrowing(RuntimeException error) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                .user(any(Consumer.class))
                .call()
                .entity(LlmReceiptExtractionDTO.class))
                .thenThrow(error);
        return new VisionExtractor(chatClient, "qwen2.5vl", "2026-07-24");
    }

    private LlmReceiptExtractionDTO fullReceipt() {
        return new LlmReceiptExtractionDTO(
                new BigDecimal("127.50"), 0.98,
                "2026-06-28", 0.95,
                "PADARIA SAO JOSE", 0.90,
                "debit", 0.99,
                "pix", 0.85,
                0.94);
    }

    @Test
    void mapeiaSaidaDoModeloParaSchemaNormalizado() {
        NormalizedBatchDTO batch = visionReturning(fullReceipt())
                .extract(IMAGE, "image/jpeg", ImportMode.NEW_TRANSACTIONS);

        assertThat(batch.sourceType()).isEqualTo(ImportSourceType.IMAGE);
        assertThat(batch.importMode()).isEqualTo(ImportMode.NEW_TRANSACTIONS);
        // Proveniência: sabe qual modelo gerou o dado.
        assertThat(batch.extractorUsed()).isEqualTo("vision_ollama_qwen2.5vl");
        assertThat(batch.extractorVersion()).isEqualTo("2026-07-24");
        assertThat(batch.transactions()).hasSize(1);

        NormalizedTransactionDTO tx = batch.transactions().get(0);
        assertThat(tx.fields().get("amount").value()).isEqualTo(new BigDecimal("127.50"));
        assertThat(tx.fields().get("amount").confidence()).isEqualByComparingTo("0.98");
        assertThat(tx.fields().get("transaction_date").value()).isEqualTo("2026-06-28");
        assertThat(tx.fields().get("description").value()).isEqualTo("PADARIA SAO JOSE");
        assertThat(tx.fields().get("direction").value()).isEqualTo("debit");
        assertThat(tx.fields().get("payment_method").value()).isEqualTo("pix");
        assertThat(tx.overallConfidence()).isEqualByComparingTo("0.94");
        // requires_review NÃO é decidido pelo modelo — quem deriva é o ImportService (§2.f).
        assertThat(tx.requiresReview()).isNull();
    }

    @Test
    void guardaCorpoRejeitaValorAusenteOuNaoPlausivel() {
        // amount null → não há transação a lançar → falha a extração (batch vai a FAILED no service).
        LlmReceiptExtractionDTO semValor = new LlmReceiptExtractionDTO(
                null, 0.10, "2026-06-28", 0.9, "x", 0.9, "debit", 0.9, null, null, 0.5);
        assertThatThrownBy(() -> visionReturning(semValor).extract(IMAGE, "image/jpeg", ImportMode.NEW_TRANSACTIONS))
                .isInstanceOf(ExtractionException.class);

        // amount <= 0 é implausível para um comprovante → também rejeita.
        LlmReceiptExtractionDTO valorZero = new LlmReceiptExtractionDTO(
                BigDecimal.ZERO, 0.9, "2026-06-28", 0.9, "x", 0.9, "debit", 0.9, null, null, 0.9);
        assertThatThrownBy(() -> visionReturning(valorZero).extract(IMAGE, "image/jpeg", ImportMode.NEW_TRANSACTIONS))
                .isInstanceOf(ExtractionException.class);
    }

    @Test
    void dataIlegivelNaoDerrubaExtracaoMasZeraConfianca() {
        LlmReceiptExtractionDTO dataInvalida = new LlmReceiptExtractionDTO(
                new BigDecimal("42.00"), 0.9, "sem-data", 0.7, "MERCADO", 0.9, "credit", 0.9, null, null, 0.9);

        NormalizedTransactionDTO tx = visionReturning(dataInvalida)
                .extract(IMAGE, "image/png", ImportMode.NEW_TRANSACTIONS)
                .transactions().get(0);

        // Data ilegível → valor null + confiança 0 (força revisão), mas a extração segue.
        assertThat(tx.fields().get("transaction_date").value()).isNull();
        assertThat(tx.fields().get("transaction_date").confidence()).isEqualByComparingTo("0");
        // "credit" preservado; payment_method null é OMITIDO do mapa.
        assertThat(tx.fields().get("direction").value()).isEqualTo("credit");
        assertThat(tx.fields()).doesNotContainKey("payment_method");
    }

    @Test
    void falhaDoProviderViraExtractionException() {
        assertThatThrownBy(() -> visionThrowing(new RuntimeException("ollama indisponível"))
                .extract(IMAGE, "image/jpeg", ImportMode.NEW_TRANSACTIONS))
                .isInstanceOf(ExtractionException.class);
    }
}
