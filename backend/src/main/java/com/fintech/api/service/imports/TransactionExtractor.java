package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;

/**
 * PORTA de extração — a fronteira agnóstica entre o pipeline de importação e a tecnologia que
 * lê o documento. O {@code ImportService} fala só com o {@link ExtractionRouter}, que fala só
 * com esta interface; nunca com Ollama, um parser de CSV ou qualquer tecnologia diretamente.
 *
 * <p>Por que uma porta e não uma chamada direta a cada tecnologia? Três motivos concretos:
 * <ul>
 *   <li><b>Swap de provider por config</b> — trocar Ollama por outro modelo é trocar o starter
 *       Maven + properties, sem tocar em regra de negócio.</li>
 *   <li><b>Extensão por adição, não edição</b> — CSV/OFX/PDF (Fase 2–3) implementam a MESMA
 *       porta; o {@link ExtractionRouter} escolhe por {@link #supports}, o commit e o staging
 *       não mudam de forma.</li>
 *   <li><b>Testabilidade</b> — cada implementação é testada isolada, sem tocar em IA nem nas
 *       demais.</li>
 * </ul>
 *
 * <p>A saída é sempre um {@link NormalizedBatchDTO} — o contrato normalizado (roadmap §1.3).
 * A saída de qualquer extrator é <i>untrusted input</i>: a implementação revalida a plausibilidade
 * do nosso lado antes de devolver (guarda-corpo). {@code requires_review} NÃO é decidido aqui —
 * quem deriva por threshold é sempre o {@code ImportService}.
 */
public interface TransactionExtractor {

    /**
     * Extrai um batch normalizado a partir da entrada bruta.
     *
     * @param input bytes + proveniência (filename, mimeType, modo de importação)
     * @return batch normalizado com N transações (N=1 na Fase 1 — imagem)
     * @throws ExtractionException quando a extração falha ou a saída não passa no guarda-corpo
     */
    NormalizedBatchDTO extract(ExtractionInput input);

    /**
     * Decide, por CONTEÚDO (nunca por {@code mimeType} do cliente — ele mente, ex.: {@code .ofx}
     * chega como {@code application/octet-stream}), se este extrator sabe processar {@code input}.
     * O {@link ExtractionRouter} chama isso em ordem ({@code @Order}) e usa o primeiro que aceitar.
     */
    boolean supports(ExtractionInput input);

    /**
     * De qual {@link ImportSourceType} este extrator é responsável. Usado pelo {@code ImportService}
     * para gravar o batch mesmo no caminho de FALHA — quando {@link #extract} lança antes de
     * produzir um {@code NormalizedBatchDTO}, o {@code sourceType} ainda precisa ser conhecido
     * (a coluna é {@code NOT NULL}).
     */
    ImportSourceType sourceType();

    /**
     * Versão deste extrator — usada pelo {@code ImportService} para gravar {@code extractorVersion}
     * no batch mesmo no caminho de FALHA (onde não há {@link NormalizedBatchDTO} pra ler o campo).
     */
    String extractorVersion();
}
