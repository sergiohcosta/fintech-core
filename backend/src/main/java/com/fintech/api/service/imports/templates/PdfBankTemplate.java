package com.fintech.api.service.imports.templates;

import com.fintech.api.dto.imports.NormalizedTransactionDTO;

import java.util.List;

/**
 * Template de reconhecimento de transações por banco, dentro do {@code PdfTextExtractor}
 * (spec: registry de templates, Fase 3). Roda ANTES da heurística genérica de linha —
 * {@code matches()} decide por assinatura de conteúdo (nunca nome de arquivo), o primeiro
 * que aceitar processa via {@code parse()}. Nenhum bate → heurística genérica atual,
 * comportamento inalterado.
 */
public interface PdfBankTemplate {

    /** Assinatura de conteúdo (ex.: CNPJ da instituição + rótulo de seção conhecido). */
    boolean matches(String fullText);

    /** Reconhece as transações do documento. Só chamado quando {@link #matches} devolveu {@code true}. */
    List<NormalizedTransactionDTO> parse(String fullText);

    /** Identificador gravado em {@code extractor_used} quando este template processa. */
    String templateId();
}
