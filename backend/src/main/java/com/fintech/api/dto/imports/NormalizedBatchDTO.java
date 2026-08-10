package com.fintech.api.dto.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * "Batch normalizado" de ENTRADA do {@code createBatch}: o pacote inteiro que um extrator
 * produziu (proveniência + lista de transações no schema normalizado). Na Fase 0 é o corpo do
 * endpoint {@code POST /api/imports/mock}, que prova o pipeline ponta a ponta sem extrator real.
 *
 * <p>{@code source} do §5.2 (nested {@code {type, extractor_used, extractor_version}}) é achatado
 * aqui para bater 1:1 com as colunas de {@code import_batches}.
 *
 * <p><b>Proveniência estruturada (V28, Onda 3):</b> {@code extractorProvider}/{@code
 * extractorModel}/{@code extractionLatencyMs}/{@code fallbackFrom}/{@code fallbackReason} são os
 * campos que o EXTRATOR conhece (quem mediu a chamada) — quem PERSISTE é o {@code ImportService},
 * preservando a fronteira atual: extratores não tocam banco. Todos nullable e opcionais: CSV/OFX/
 * PDF texto não têm provider/modelo/latência (parser determinístico, não chamada a modelo), e
 * fallback só é preenchido quando o extrator efetivamente tentou outro provider antes (Onda 4).
 *
 * <p><b>Fatura-alvo do documento (V30, spec 2026-08-09):</b> {@code targetInvoiceReferenceYear}/
 * {@code Month} são o mês de referência da fatura que O DOCUMENTO IMPORTADO representa (não uma
 * inferência por transação) — só extratores cujo documento tem "1 fatura = 1 vencimento único"
 * populam (hoje, só {@code ItauFaturaTemplate}). {@code null} = extrator sem esse conceito; o
 * commit cai no caminho existente (recalcula por {@code resolveInvoiceMonth}).
 */
public record NormalizedBatchDTO(
        @NotNull ImportMode importMode,
        @NotNull ImportSourceType sourceType,
        String extractorUsed,
        String extractorVersion,
        @NotNull @Valid List<NormalizedTransactionDTO> transactions,
        String extractorProvider,
        String extractorModel,
        Integer extractionLatencyMs,
        String fallbackFrom,
        String fallbackReason,
        Integer targetInvoiceReferenceYear,
        Integer targetInvoiceReferenceMonth) {

    /**
     * Construtor de compatibilidade para os extratores/testes que ainda não informam proveniência
     * estruturada nem fatura-alvo (CSV, OFX, PDF texto genérico — sem provider/modelo/latência a
     * medir, sem conceito de vencimento único de documento). Evita reescrever todo chamador
     * existente só para acrescentar campos opcionais.
     */
    public NormalizedBatchDTO(
            ImportMode importMode,
            ImportSourceType sourceType,
            String extractorUsed,
            String extractorVersion,
            List<NormalizedTransactionDTO> transactions) {
        this(importMode, sourceType, extractorUsed, extractorVersion, transactions,
                null, null, null, null, null, null, null);
    }

    /**
     * Construtor de compatibilidade para chamadores que informam proveniência estruturada (V28)
     * mas não fatura-alvo (V30) — forma usada antes desta entrega.
     */
    public NormalizedBatchDTO(
            ImportMode importMode,
            ImportSourceType sourceType,
            String extractorUsed,
            String extractorVersion,
            List<NormalizedTransactionDTO> transactions,
            String extractorProvider,
            String extractorModel,
            Integer extractionLatencyMs,
            String fallbackFrom,
            String fallbackReason) {
        this(importMode, sourceType, extractorUsed, extractorVersion, transactions,
                extractorProvider, extractorModel, extractionLatencyMs, fallbackFrom, fallbackReason,
                null, null);
    }
}
