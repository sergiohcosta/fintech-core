package com.fintech.api.exception;

import com.fintech.api.domain.imports.ImportBatch;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * O arquivo (mesmo {@code source_hash}) já foi importado antes por este tenant. 409 em vez de
 * duplicar em silêncio — reimportar o mesmo extrato sem querer dobraria um mês inteiro de
 * lançamentos (o pior desfecho possível num app financeiro). {@code ?force=true} contorna
 * (reimportar é caso legítimo: extrato corrigido pelo banco, etc.).
 */
public class DuplicateImportException extends RuntimeException {

    private final UUID batchId;
    private final LocalDateTime createdAt;
    private final String filename;

    public DuplicateImportException(ImportBatch existing) {
        super("Este arquivo já foi importado em " + existing.getCreatedAt()
                + " (batch " + existing.getId() + "). Use force=true para reimportar mesmo assim.");
        this.batchId = existing.getId();
        this.createdAt = existing.getCreatedAt();
        this.filename = existing.getSourceFilename();
    }

    public UUID getBatchId() {
        return batchId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getFilename() {
        return filename;
    }
}
