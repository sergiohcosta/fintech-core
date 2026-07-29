package com.fintech.api.service.imports;

import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Escolhe QUAL {@link TransactionExtractor} processa a entrada, por conteúdo (nunca por
 * {@code Content-Type} do browser — ele mente: um {@code .ofx} chega como
 * {@code application/octet-stream}). A ORDEM da lista injetada pelo Spring (via {@code @Order}
 * em cada extrator) É o funil do roadmap §1.2: padrão universal (OFX) → genérico (CSV) → IA
 * (imagem). Cada fase futura adiciona uma classe nova aqui; o {@code ImportService} não muda.
 *
 * <p>Formato NENHUM extrator reconhece é diferente de um extrator reconhecer o formato e falhar
 * ao processá-lo: o primeiro caso vira {@link BusinessException} (400 — nem chega a existir
 * batch, pois não há {@code sourceType} para gravar); o segundo é {@link ExtractionException},
 * capturada pelo {@code ImportService} e gravada como batch {@code FAILED} (§Onda 1 do plano).
 */
@Service
@Slf4j
public class ExtractionRouter {

    private final List<TransactionExtractor> extractors;

    public ExtractionRouter(List<TransactionExtractor> extractors) {
        this.extractors = extractors;
    }

    /** Encontra o extrator responsável por {@code input}, sem executar a extração ainda. */
    public TransactionExtractor route(ExtractionInput input) {
        return extractors.stream()
                .filter(e -> e.supports(input))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "Não reconhecemos o formato deste arquivo. Envie uma imagem de "
                                + "comprovante, um extrato CSV ou um arquivo OFX."));
    }

    public NormalizedBatchDTO extract(ExtractionInput input) {
        TransactionExtractor extractor = route(input);
        log.info("Extração roteada: filename={}, extrator={}", input.filename(), extractor.getClass().getSimpleName());
        return extractor.extract(input);
    }
}
