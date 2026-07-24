package com.fintech.api.domain.enums;

/** Mídia de origem da extração. Fase 1 cobre só IMAGE; o resto é roadmap (Fases 2–3). */
public enum ImportSourceType { IMAGE, PDF_TEXT, PDF_SCANNED, CSV, OFX, AUDIO }
