package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;

/**
 * Entrada agnóstica de formato para a porta {@link TransactionExtractor}. Substitui os parâmetros
 * soltos da Fase 1 ({@code imageBytes, mimeType}) por um envelope único — cada fase nova (CSV,
 * OFX, PDF) só precisa ler os campos que lhe interessam, sem quebrar a assinatura da porta.
 *
 * <p>{@code filename} entra aqui porque é PISTA de detecção (extensão) e proveniência (o que o
 * usuário enviou) — nenhum extrator confia nele sozinho (routing é por conteúdo), mas ele ajuda
 * a desempatar e a registrar {@code source_filename} no batch (Onda 4).
 */
public record ExtractionInput(byte[] content, String filename, String mimeType, ImportMode mode) {}
