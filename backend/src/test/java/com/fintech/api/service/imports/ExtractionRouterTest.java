package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O {@link ExtractionRouter} é o funil do roadmap §1.2 — escolhe o {@link TransactionExtractor}
 * por CONTEÚDO, nunca por {@code Content-Type} do cliente (ele mente: um {@code .ofx} chega como
 * {@code application/octet-stream}). Onda 1 só prova o mecanismo com extratores fake; OFX/CSV
 * de verdade entram nas Ondas 2–3.
 */
class ExtractionRouterTest {

    private static ExtractionInput anyInput(String mimeType) {
        return new ExtractionInput(new byte[]{1, 2, 3}, "arquivo", mimeType, ImportMode.NEW_TRANSACTIONS);
    }

    private TransactionExtractor fakeExtractor(boolean supports, ImportSourceType sourceType) {
        TransactionExtractor extractor = mock(TransactionExtractor.class);
        when(extractor.supports(any())).thenReturn(supports);
        when(extractor.sourceType()).thenReturn(sourceType);
        return extractor;
    }

    @Test
    void roteiaParaOPrimeiroExtratorQueAceitaMesmoComMimeTypeMentindo() {
        TransactionExtractor csv = fakeExtractor(true, ImportSourceType.CSV);
        NormalizedBatchDTO fixture = new NormalizedBatchDTO(
                ImportMode.NEW_TRANSACTIONS, ImportSourceType.CSV, "csv_generic_v1", "1", List.of());
        when(csv.extract(any())).thenReturn(fixture);

        TransactionExtractor imagem = fakeExtractor(false, ImportSourceType.IMAGE);

        ExtractionRouter router = new ExtractionRouter(List.of(csv, imagem));

        // Content-Type mente ("application/octet-stream" para um .csv) — router ignora, decide por conteúdo.
        NormalizedBatchDTO result = router.extract(anyInput("application/octet-stream"));

        assertThat(result.sourceType()).isEqualTo(ImportSourceType.CSV);
    }

    @Test
    void nenhumExtratorReconheceOFormatoVira400SemCriarBatch() {
        TransactionExtractor nenhum = fakeExtractor(false, ImportSourceType.IMAGE);
        ExtractionRouter router = new ExtractionRouter(List.of(nenhum));

        assertThatThrownBy(() -> router.extract(anyInput("application/pdf")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void routeExpoeOExtratorEscolhidoSemExecutarAExtracao() {
        TransactionExtractor ofx = fakeExtractor(true, ImportSourceType.OFX);
        ExtractionRouter router = new ExtractionRouter(List.of(ofx));

        TransactionExtractor escolhido = router.route(anyInput("application/octet-stream"));

        assertThat(escolhido).isSameAs(ofx);
    }
}
