package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.service.imports.vision.VisionModelClient;
import com.fintech.api.service.imports.vision.VisionProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        @SuppressWarnings("unchecked")
        public <T> T extract(
                String prompt, MimeType mimeType, Resource imageResource, Class<T> responseType, Integer maxOutputTokens) {
            if (error != null) {
                throw error;
            }
            return (T) fixture;
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
        return new VisionExtractor(List.of(new FakeVisionModelClient(fixture, null)), "2026-07-24", 60, 4096);
    }

    private VisionExtractor visionThrowing(RuntimeException error) {
        return new VisionExtractor(List.of(new FakeVisionModelClient(null, error)), "2026-07-24", 60, 4096);
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
        // Proveniência ESTRUTURADA (V28, Onda 3): o extrator mede provider/modelo/latência — quem
        // grava é o ImportService, mas o dado já nasce aqui, não é reconstruído depois.
        assertThat(batch.extractorProvider()).isEqualTo("ollama");
        assertThat(batch.extractorModel()).isEqualTo("qwen2.5vl");
        assertThat(batch.extractionLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
        // Sem fallback nesta Onda (a política em si é a Onda 4) — o client fake é o único da lista.
        assertThat(batch.fallbackFrom()).isNull();
        assertThat(batch.fallbackReason()).isNull();
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

    // --- #194 — caminho de EXTRATO: 2ª chamada ao MESMO winner quando a 1ª sinaliza
    // multipleTransactionsDetected=true. #193 (recusa explícita) foi SUBSTITUÍDO por aceite com
    // requires_review forçado (spec 2026-08-13, §2.d) — não convive em paralelo com a recusa.

    private LlmReceiptExtractionDTO receiptSinalizandoExtrato() {
        // Valor plausível de propósito: prova que o roteamento pro caminho de extrato NÃO
        // depende do amount da 1ª chamada ser inválido.
        return new LlmReceiptExtractionDTO(
                new BigDecimal("89.90"), 0.95, "2026-06-28", 0.95, "MERCADO", 0.9,
                "debit", 0.95, null, null, 0.93, true);
    }

    private LlmStatementExtractionDTO.Line linha(String amount, String data, String desc, String direction) {
        return new LlmStatementExtractionDTO.Line(new BigDecimal(amount), 0.95, data, 0.95, desc, 0.9, direction, 0.95);
    }

    @Test
    void aceitaExtratoEExtraiTodasAsLinhasComRequiresReviewForcado() {
        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), eq(LlmReceiptExtractionDTO.class), any()))
                .thenReturn(receiptSinalizandoExtrato());
        LlmStatementExtractionDTO statement = new LlmStatementExtractionDTO(
                List.of(
                        linha("50.00", "2026-06-01", "MERCADO A", "debit"),
                        linha("30.00", "2026-06-02", "MERCADO B", "credit"),
                        linha("10.00", "2026-06-03", "MERCADO C", "debit")),
                null, null, 0.9);
        when(gemini.extract(any(), any(), any(), eq(LlmStatementExtractionDTO.class), any()))
                .thenReturn(statement);

        NormalizedBatchDTO batch = new VisionExtractor(List.of(gemini), "2026-07-24", 60, 4096)
                .extract(input(IMAGE, "image/jpeg"));

        // Prova que o maxOutputTokens do CONSTRUTOR (4096) chega de fato na 2ª chamada — sem
        // isso, um bug trocando statementMaxLines/statementMaxOutputTokens no construtor passaria
        // despercebido (achado de code review).
        verify(gemini).extract(any(), any(), any(), eq(LlmStatementExtractionDTO.class), eq(4096));

        assertThat(batch.transactions()).hasSize(3);
        // TODA linha do extrato sai marcada — staged rollout incondicional (spec §2.d), não
        // depende de confiança.
        assertThat(batch.transactions()).allSatisfy(tx -> assertThat(tx.requiresReview()).isTrue());
        assertThat(batch.transactions().get(0).fields().get("amount").value()).isEqualTo(new BigDecimal("50.00"));
        assertThat(batch.transactions().get(1).fields().get("direction").value()).isEqualTo("credit");
        // extractorUsed sinaliza o caminho de extrato — permite medir volume/custo separado do
        // comprovante e é o marcador usado pra revisitar o gate de confiança no futuro (spec §3).
        assertThat(batch.extractorUsed()).isEqualTo("vision_statement_gemini_gemini-2.5-flash");
        // overallConfidence da linha reflete a do MODELO (statement.overallConfidence()=0.9),
        // nunca hardcoded — ImportService.patchStaged re-deriva requires_review usando esse valor
        // (deriveRequiresReview: overallConfidence < 0.90 → true); hardcoded 1.0 desativaria essa
        // via silenciosamente na hora de editar a staged.
        assertThat(batch.transactions()).allSatisfy(tx -> assertThat(tx.overallConfidence()).isEqualByComparingTo("0.9"));
    }

    /**
     * Achado de code review: `overallConfidence` da linha precisa vir do modelo, não de um valor
     * fixo — senão o floor forçado (spec §2.d) fica inerte no momento em que o usuário edita a
     * staged: `patchStaged` re-deriva `requires_review` só a partir de `overallConfidence` +
     * confiança de `amount`, e um `overallConfidence` sempre alto mascara um extrato de baixa
     * confiança agregada.
     */
    @Test
    void overallConfidenceBaixaDoExtratoPersisteParaAlimentarReDerivacaoNoPatch() {
        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), eq(LlmReceiptExtractionDTO.class), any()))
                .thenReturn(receiptSinalizandoExtrato());
        when(gemini.extract(any(), any(), any(), eq(LlmStatementExtractionDTO.class), any()))
                .thenReturn(new LlmStatementExtractionDTO(
                        List.of(linha("50.00", "2026-06-01", "X", "debit")), null, null, 0.5));

        NormalizedBatchDTO batch = new VisionExtractor(List.of(gemini), "2026-07-24", 60, 4096)
                .extract(input(IMAGE, "image/jpeg"));

        assertThat(batch.transactions().get(0).overallConfidence()).isEqualByComparingTo("0.5");
    }

    @Test
    void segundaChamadaNaoTentaOutroProviderQuandoFalhaPorIndisponibilidade() {
        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), eq(LlmReceiptExtractionDTO.class), any()))
                .thenReturn(receiptSinalizandoExtrato());
        when(gemini.extract(any(), any(), any(), eq(LlmStatementExtractionDTO.class), any()))
                .thenThrow(new VisionProviderUnavailableException("quota", "Gemini indisponível (limite de cota atingido).", null));

        VisionModelClient ollama = mockClient("ollama", "qwen2.5vl");

        VisionExtractor extractor = new VisionExtractor(List.of(gemini, ollama), "2026-07-24", 60, 4096);

        // Troca de provider no meio da extração misturaria leituras de modelos diferentes da
        // mesma imagem — a falha propaga, não tenta o Ollama (spec §6.3).
        assertThatThrownBy(() -> extractor.extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class);
        verify(ollama, never()).extract(any(), any(), any(), any(), any());
    }

    @Test
    void excedeLimiteDeLinhasLancaExtractionException() {
        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), eq(LlmReceiptExtractionDTO.class), any()))
                .thenReturn(receiptSinalizandoExtrato());
        List<LlmStatementExtractionDTO.Line> muitasLinhas = IntStream.range(0, 61)
                .mapToObj(i -> linha("1.00", "2026-06-01", "X" + i, "debit"))
                .toList();
        when(gemini.extract(any(), any(), any(), eq(LlmStatementExtractionDTO.class), any()))
                .thenReturn(new LlmStatementExtractionDTO(muitasLinhas, null, null, 0.9));

        VisionExtractor extractor = new VisionExtractor(List.of(gemini), "2026-07-24", 60, 4096);

        assertThatThrownBy(() -> extractor.extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("mais de 60 lançamentos");
    }

    @Test
    void zeroLinhasNoExtratoLancaExtractionException() {
        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), eq(LlmReceiptExtractionDTO.class), any()))
                .thenReturn(receiptSinalizandoExtrato());
        when(gemini.extract(any(), any(), any(), eq(LlmStatementExtractionDTO.class), any()))
                .thenReturn(new LlmStatementExtractionDTO(List.of(), null, null, 0.9));

        VisionExtractor extractor = new VisionExtractor(List.of(gemini), "2026-07-24", 60, 4096);

        assertThatThrownBy(() -> extractor.extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("nenhum lançamento");
    }

    @Test
    void amountNegativoNormalizaParaAbsolutoEZeraConfianca() {
        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), eq(LlmReceiptExtractionDTO.class), any()))
                .thenReturn(receiptSinalizandoExtrato());
        LlmStatementExtractionDTO.Line linhaNegativa =
                new LlmStatementExtractionDTO.Line(new BigDecimal("-42.00"), 0.9, "2026-06-01", 0.9, "X", 0.9, "debit", 0.9);
        when(gemini.extract(any(), any(), any(), eq(LlmStatementExtractionDTO.class), any()))
                .thenReturn(new LlmStatementExtractionDTO(List.of(linhaNegativa), null, null, 0.9));

        NormalizedBatchDTO batch = new VisionExtractor(List.of(gemini), "2026-07-24", 60, 4096)
                .extract(input(IMAGE, "image/jpeg"));

        NormalizedTransactionDTO tx = batch.transactions().get(0);
        assertThat(tx.fields().get("amount").value()).isEqualTo(new BigDecimal("42.00"));
        // Sinal ambíguo/conflitante com direction — zera a confiança do CAMPO (não descarta a
        // linha, spec §6.4).
        assertThat(tx.fields().get("amount").confidence()).isEqualByComparingTo("0");
    }

    @Test
    void reconciliacaoDivergenteNaoDescartaNenhumaLinha() {
        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), eq(LlmReceiptExtractionDTO.class), any()))
                .thenReturn(receiptSinalizandoExtrato());
        // declaredTotalDebits (200) NÃO bate com a soma real das linhas (50) — fora de qualquer
        // tolerância razoável.
        when(gemini.extract(any(), any(), any(), eq(LlmStatementExtractionDTO.class), any()))
                .thenReturn(new LlmStatementExtractionDTO(
                        List.of(linha("50.00", "2026-06-01", "X", "debit")),
                        new BigDecimal("200.00"), null, 0.9));

        NormalizedBatchDTO batch = new VisionExtractor(List.of(gemini), "2026-07-24", 60, 4096)
                .extract(input(IMAGE, "image/jpeg"));

        // Mismatch é sinal de log, nunca motivo pra descartar dado (issue #194 exige não-destrutivo).
        assertThat(batch.transactions()).hasSize(1);
    }

    @Test
    void semTotalDeclaradoPulaReconciliacaoSemErro() {
        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), eq(LlmReceiptExtractionDTO.class), any()))
                .thenReturn(receiptSinalizandoExtrato());
        when(gemini.extract(any(), any(), any(), eq(LlmStatementExtractionDTO.class), any()))
                .thenReturn(new LlmStatementExtractionDTO(
                        List.of(linha("50.00", "2026-06-01", "X", "debit")), null, null, 0.9));

        NormalizedBatchDTO batch = new VisionExtractor(List.of(gemini), "2026-07-24", 60, 4096)
                .extract(input(IMAGE, "image/jpeg"));

        assertThat(batch.transactions()).hasSize(1);
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
    void usaOPrimeiroClientDaListaQuandoElePropriaSucesso() {
        // Onda 4: o primeiro só NÃO é usado se falhar por INDISPONIBILIDADE (ver testes de
        // fallback abaixo) — sucesso do primeiro nunca invoca o segundo.
        VisionModelClient primeiro = new FakeVisionModelClient(fullReceipt(), null);
        VisionModelClient nuncaChamado = new VisionModelClient() {
            @Override
            public <T> T extract(
                    String prompt, MimeType mimeType, Resource imageResource, Class<T> responseType, Integer maxOutputTokens) {
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

        NormalizedBatchDTO batch = new VisionExtractor(List.of(primeiro, nuncaChamado), "2026-07-24", 60, 4096)
                .extract(input(IMAGE, "image/jpeg"));

        assertThat(batch.extractorUsed()).isEqualTo("vision_ollama_qwen2.5vl");
    }

    // --- supports() — magic number, não mimeType ---

    private VisionExtractor extractorForSupportsOnly() {
        // Nenhum client é chamado nestes testes — supports() não toca no provider.
        return new VisionExtractor(List.of(new FakeVisionModelClient(fullReceipt(), null)), "2026-07-24", 60, 4096);
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

    // --- Onda 4 — política de fallback por falha de DISPONIBILIDADE (spec §3.2) ---
    //
    // Os fakes destes testes usam Mockito (não o FakeVisionModelClient record acima) porque
    // precisamos de verify(never()) para travar a regra central: falha de CONTEÚDO nunca aciona
    // o próximo provider.

    private VisionModelClient mockClient(String providerId, String modelId) {
        VisionModelClient client = mock(VisionModelClient.class);
        when(client.providerId()).thenReturn(providerId);
        when(client.modelId()).thenReturn(modelId);
        return client;
    }

    @Test
    void caiParaOSegundoProviderQuandoOPrimeiroEIndisponivel() {
        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), any(), any()))
                .thenThrow(new VisionProviderUnavailableException("quota", "Gemini indisponível (limite de cota atingido).", null));

        VisionModelClient ollama = mockClient("ollama", "qwen2.5vl");
        when(ollama.extract(any(), any(), any(), any(), any())).thenReturn(fullReceipt());

        NormalizedBatchDTO batch = new VisionExtractor(List.of(gemini, ollama), "2026-07-24", 60, 4096)
                .extract(input(IMAGE, "image/jpeg"));

        // O resultado vem do SECUNDÁRIO (quem respondeu) — extractorUsed reflete quem venceu.
        assertThat(batch.extractorUsed()).isEqualTo("vision_ollama_qwen2.5vl");
        assertThat(batch.extractorProvider()).isEqualTo("ollama");
        // Proveniência do fallback: de quem a extração precisou fugir, e por quê.
        assertThat(batch.fallbackFrom()).isEqualTo("gemini");
        assertThat(batch.fallbackReason()).isEqualTo("quota");
    }

    /**
     * #194 substituiu a recusa do #193 por aceite (ver seção "caminho de EXTRATO" acima) — este
     * teste cobria "falha de CONTEÚDO na revalidação não dispara fallback" usando a antiga recusa
     * como exemplo; agora usa uma falha de conteúdo real do caminho novo (zero linhas
     * reconhecidas na 2ª chamada), preservando a mesma garantia de regra central.
     */
    @Test
    void naoTentaOSegundoProviderQuandoASegundaChamadaFalhaPorConteudo() {
        LlmReceiptExtractionDTO sinalizaExtrato = new LlmReceiptExtractionDTO(
                new BigDecimal("89.90"), 0.95, "2026-06-28", 0.95, "MERCADO", 0.9,
                "debit", 0.95, null, null, 0.93, true);

        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), eq(LlmReceiptExtractionDTO.class), any()))
                .thenReturn(sinalizaExtrato);
        when(gemini.extract(any(), any(), any(), eq(LlmStatementExtractionDTO.class), any()))
                .thenReturn(new LlmStatementExtractionDTO(List.of(), null, null, 0.9));

        VisionModelClient ollama = mockClient("ollama", "qwen2.5vl");

        VisionExtractor extractor = new VisionExtractor(List.of(gemini, ollama), "2026-07-24", 60, 4096);

        assertThatThrownBy(() -> extractor.extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class);

        // A asserção que trava a regra central: falha de CONTEÚDO na 2ª chamada nunca dispara
        // fallback pro próximo provider — nem na 1ª (regra de sempre), nem aqui.
        verify(ollama, never()).extract(any(), any(), any(), any(), any());
    }

    @Test
    void naoTentaOSegundoProviderQuandoOAmountDoPrimeiroEInvalido() {
        LlmReceiptExtractionDTO semValor = new LlmReceiptExtractionDTO(
                null, 0.10, "2026-06-28", 0.9, "x", 0.9, "debit", 0.9, null, null, 0.5, false);

        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), any(), any())).thenReturn(semValor);

        VisionModelClient ollama = mockClient("ollama", "qwen2.5vl");

        VisionExtractor extractor = new VisionExtractor(List.of(gemini, ollama), "2026-07-24", 60, 4096);

        assertThatThrownBy(() -> extractor.extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class);

        verify(ollama, never()).extract(any(), any(), any(), any(), any());
    }

    @Test
    void todosIndisponiveisLancaExtractionExceptionComMotivoDoUltimo() {
        VisionModelClient gemini = mockClient("gemini", "gemini-2.5-flash");
        when(gemini.extract(any(), any(), any(), any(), any()))
                .thenThrow(new VisionProviderUnavailableException("quota", "Gemini indisponível (limite de cota atingido).", null));

        VisionModelClient ollama = mockClient("ollama", "qwen2.5vl");
        when(ollama.extract(any(), any(), any(), any(), any()))
                .thenThrow(new VisionProviderUnavailableException("unavailable", "Ollama indisponível (provedor indisponível no momento).", null));

        VisionExtractor extractor = new VisionExtractor(List.of(gemini, ollama), "2026-07-24", 60, 4096);

        // ExtractionException (não VisionProviderUnavailableException) — é isso que o
        // ImportService sabe capturar para marcar o batch FAILED. Motivo do ÚLTIMO erro (Ollama).
        assertThatThrownBy(() -> extractor.extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class)
                .isNotInstanceOf(VisionProviderUnavailableException.class)
                .hasMessageContaining("Ollama indisponível");
    }

    @Test
    void listaVaziaLancaExtractionExceptionEmVezDeNullPointerException() {
        VisionExtractor extractor = new VisionExtractor(List.of(), "2026-07-24", 60, 4096);

        assertThatThrownBy(() -> extractor.extract(input(IMAGE, "image/jpeg")))
                .isInstanceOf(ExtractionException.class);
    }
}
