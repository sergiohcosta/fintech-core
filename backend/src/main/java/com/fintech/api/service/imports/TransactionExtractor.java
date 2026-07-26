package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.dto.imports.NormalizedBatchDTO;

/**
 * PORTA de extração — a fronteira agnóstica entre o pipeline de importação e a tecnologia que
 * lê o documento. O {@code ImportService} fala só com esta interface; nunca com Ollama, OpenAI
 * ou qualquer provider diretamente.
 *
 * <p>Por que uma porta e não uma chamada direta ao {@code ChatClient}? Três motivos concretos:
 * <ul>
 *   <li><b>Swap de provider por config</b> — trocar Ollama por outro modelo é trocar o starter
 *       Maven + properties, sem tocar em regra de negócio.</li>
 *   <li><b>Extensão futura</b> — CSV/OFX/PDF (fases 2–3) implementam a MESMA porta, convergindo
 *       para o {@link NormalizedBatchDTO}; o commit e o staging não mudam.</li>
 *   <li><b>Testabilidade</b> — o serviço é testado com um extrator falso, sem tocar em IA.</li>
 * </ul>
 *
 * <p>A saída é sempre um {@link NormalizedBatchDTO} — o contrato normalizado (roadmap §1.3).
 * A saída do modelo é <i>untrusted input</i>: a implementação revalida do nosso lado antes de
 * devolver (guarda-corpo §2.g). {@code requires_review} NÃO é decidido aqui — quem deriva por
 * threshold é o {@code ImportService} (§2.f).
 */
public interface TransactionExtractor {

    /**
     * Extrai um batch normalizado a partir dos bytes de uma imagem.
     *
     * @param imageBytes conteúdo binário da imagem do comprovante
     * @param mimeType   tipo MIME da imagem (ex.: {@code image/jpeg})
     * @param mode       modo de importação (novas transações vs. conciliação)
     * @return batch normalizado com UMA transação (Fase 1 é 1 comprovante = 1 transação)
     * @throws ExtractionException quando a extração falha ou a saída não passa no guarda-corpo
     */
    NormalizedBatchDTO extract(byte[] imageBytes, String mimeType, ImportMode mode);
}
