package com.fintech.api.dto.imports;

import java.math.BigDecimal;

/**
 * Par {@code {value, confidence}} de um campo — a unidade do contrato normalizado (roadmap §1.3),
 * na borda da API. {@code value} é {@link Object} porque o valor é heterogêneo por campo
 * (número para amount, string ISO para datas). Espelha {@code domain.imports.StagedFieldValue}.
 */
public record StagedFieldValueDTO(Object value, BigDecimal confidence) {}
