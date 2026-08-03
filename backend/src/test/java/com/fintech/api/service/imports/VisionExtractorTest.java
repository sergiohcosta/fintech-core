package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.service.imports.vision.VisionModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unitário do extrator de visão com {@link VisionModelClient} FAKE (Onda 1 — a mecânica do
 * {@code ChatClient} saiu daqui e migrou para {@code OllamaVisionClientTest}). Prova o mapeamento
 * saída-do-modelo → schema normalizado e o guarda-corpo (§2.g): schema íntegro não basta, o
 * conteúdo é revalidado do nosso lado. Também prova o {@code supports()} por magic number.
 */
class VisionExtractorTest {

    // Magic number JPEG real (FF D8 FF) — supports() decide por bytes, não pelo mimeType.
    private static final byte[] IMAGE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    private static ExtractionInput input(byte[] content, String mimeType) {
        return new ExtractionInput(content, "recibo.jpg", mimeType, ImportMode.NEW_TRANSACTIONS);
    }

    /** Fake de {@link VisionModelClient} que devolve {@code fixture} sem tocar em provider nenhum. */
    private record FakeVisionModelClient(LlmReceiptExtractionDTO fixture, RuntimeException error)
            implements VisionModelClient {

        @Override
        public LlmReceiptExtractionDTO extract(String prompt, MimeType mimeType, Resource imageResource) {
            if (error != null) {
                throw error;
            }
            return fixture;
        }

        @Override
        public String providerId() {
            return "ollama";
        }

        @Override
        public String modelId() {
            return "qwen2.5vl";
        }
    }

    private VisionExtractor visionReturning(LlmReceiptExtractionDTO fixture) {
        return new VisionExtractor(List.of(new FakeVisionModelClient(fixture, null)), "2026-07-24");
    }

    private VisionExtractor visionThrowing(RuntimeException error) {
        return new VisionExtractor(List.of(new FakeVisionModelClient(null, error)), "2026-07-24");
    }

    private LlmReceiptExtractionDTO fullReceipt() {
        return new LlmReceiptExtractionDTO(
                new BigDecimal("127.50"), 0.98,
                "2026-06-28", 0.95,
                "PADARIA SAO JOSE", 0.90,
                "debit", 0.99,
                "pix", 0.85,
                0.94, false);
    }

    @Test
    void mapeiaSaidaDoModeloParaSchemaNormalizado() {
        NormalizedBatchDTO batch = visionReturning(fullReceipt())
                .extract(input(IMAGE, "image/jpeg"));

        assertThat(batch.sourceType()).isEqualTo(ImportSourceType.IMAGE);
        assertThat(batch.importMode()).isEqualTo(ImportMode.NEW_TRANSACTIONS);
        // Proveniência: sabe qual provider+modelo gerou o dado — mesmo formato de antes da Onda 1.
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
                null, 0.10, "2026-06-28", 0.9, "x", 0.9, "debit", 0.9, null, null, 0.5, false);
        assertThatThrownBy(() -> visionReturning(semValor).extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class);

        // amount <= 0 é implausível para um comprovante → também rejeita.
        LlmReceiptExtractionDTO valorZero = new LlmReceiptExtractionDTO(
                BigDecimal.ZERO, 0.9, "2026-06-28", 0.9, "x", 0.9, "debit", 0.9, null, null, 0.9, false);
        assertThatThrownBy(() -> visionReturning(valorZero).extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class);
    }

    @Test
    void dataIlegivelNaoDerrubaExtracaoMasZeraConfianca() {
        LlmReceiptExtractionDTO dataInvalida = new LlmReceiptExtractionDTO(
                new BigDecimal("42.00"), 0.9, "sem-data", 0.7, "MERCADO", 0.9, "credit", 0.9, null, null, 0.9, null);

        NormalizedTransactionDTO tx = visionReturning(dataInvalida)
                .extract(input(IMAGE, "image/png"))
                .transactions().get(0);

        // Data ilegível → valor null + confiança 0 (força revisão), mas a extração segue.
        assertThat(tx.fields().get("transaction_date").value()).isNull();
        assertThat(tx.fields().get("transaction_date").confidence()).isEqualByComparingTo("0");
        // "credit" preservado; payment_method null é OMITIDO do mapa.
        assertThat(tx.fields().get("direction").value()).isEqualTo("credit");
        assertThat(tx.fields()).doesNotContainKey("payment_method");
    }

    /**
     * #193 — print de extrato (vários lançamentos) é FORA DO ESCOPO da Fase 1 (1 imagem = 1
     * transação). Antes desse guarda-corpo o modelo escolhia uma linha arbitrária, o valor passava
     * no check de plausibilidade e as demais sumiam CALADAS. Recusa explícita > perda silenciosa.
     */
    @Test
    void recusaImagemComMultiplasTransacoes() {
        // Valor plausível de propósito: prova que a recusa NÃO depende do amount ser inválido —
        // se a ordem das validações se inverter, este teste quebra.
        LlmReceiptExtractionDTO extrato = new LlmReceiptExtractionDTO(
                new BigDecimal("89.90"), 0.95, "2026-06-28", 0.95, "MERCADO", 0.9,
                "debit", 0.95, null, null, 0.93, true);

        assertThatThrownBy(() -> visionReturning(extrato).extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class)
                .hasMessage(VisionExtractor.MULTIPLE_TRANSACTIONS_MESSAGE);
    }

    /**
     * Retrocompatibilidade: modelo que não preenche a flag (null) ou a preenche com false segue
     * extraindo. Ausência de sinal não é sinal de recusa — senão trocaríamos perda silenciosa de
     * dado por recusa indevida de comprovante válido.
     */
    @Test
    void flagAusenteNaoRecusaExtracao() {
        LlmReceiptExtractionDTO semFlag = new LlmReceiptExtractionDTO(
                new BigDecimal("10.00"), 0.9, "2026-06-28", 0.9, "X", 0.9,
                "debit", 0.9, null, null, 0.9, null);

        assertThat(visionReturning(semFlag)
                .extract(input(IMAGE, "image/jpeg"))
                .transactions()).hasSize(1);
    }

    @Test
    void falhaDoProviderViraExtractionException() {
        assertThatThrownBy(() -> visionThrowing(new ExtractionException("ollama indisponível"))
                .extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class);
    }

    @Test
    void nenhumDadoRetornadoPeloClientViraExtractionException() {
        assertThatThrownBy(() -> visionReturning(null).extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class);
    }

    @Test
    void usaOPrimeiroClientDaLista() {
        // Onda 1: só há um client, mas a mecânica "primeiro da lista" já é exercida aqui — a Onda
        // seguinte soma um 2º client sem precisar reescrever este teste.
        VisionModelClient primeiro = new FakeVisionModelClient(fullReceipt(), null);
        VisionModelClient nuncaChamado = new VisionModelClient() {
            @Override
            public LlmReceiptExtractionDTO extract(String prompt, MimeType mimeType, Resource imageResource) {
                throw new AssertionError("client fora da posição 0 não deveria ser chamado nesta Onda");
            }

            @Override
            public String providerId() {
                return "nunca-chamado";
            }

            @Override
            public String modelId() {
                return "nunca-chamado";
            }
        };

        NormalizedBatchDTO batch = new VisionExtractor(List.of(primeiro, nuncaChamado), "2026-07-24")
                .extract(input(IMAGE, "image/jpeg"));

        assertThat(batch.extractorUsed()).isEqualTo("vision_ollama_qwen2.5vl");
    }

    // --- supports() — magic number, não mimeType ---

    private VisionExtractor extractorForSupportsOnly() {
        // Nenhum client é chamado nestes testes — supports() não toca no provider.
        return new VisionExtractor(List.of(new FakeVisionModelClient(fullReceipt(), null)), "2026-07-24");
    }

    @Test
    void supportsAceitaJpegPngGifWebpPorMagicNumber() {
        VisionExtractor extractor = extractorForSupportsOnly();

        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] gif = {0x47, 0x49, 0x46, '8', '9', 'a'};
        byte[] webp = {0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

        assertThat(extractor.supports(input(jpeg, "application/octet-stream"))).isTrue();
        assertThat(extractor.supports(input(png, "application/octet-stream"))).isTrue();
        assertThat(extractor.supports(input(gif, "application/octet-stream"))).isTrue();
        assertThat(extractor.supports(input(webp, "application/octet-stream"))).isTrue();
    }

    @Test
    void supportsRejeitaConteudoQueNaoEImagemMesmoComMimeTypeMentindo() {
        VisionExtractor extractor = extractorForSupportsOnly();

        // mimeType diz "image/jpeg", mas o conteúdo é texto puro (ex.: CSV) — supports() não confia nisso.
        byte[] textoPlano = "data,valor\n2026-01-01,10.00\n".getBytes();
        assertThat(extractor.supports(input(textoPlano, "image/jpeg"))).isFalse();
    }
}
