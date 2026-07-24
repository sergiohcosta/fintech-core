package com.fintech.api.domain.enums;

/** Ciclo de vida de um batch: PENDING → EXTRACTED → REVIEWED → COMMITTED (ou FAILED). */
public enum ImportBatchStatus { PENDING, EXTRACTED, REVIEWED, COMMITTED, FAILED }
