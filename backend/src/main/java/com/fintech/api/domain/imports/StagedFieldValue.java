package com.fintech.api.domain.imports;

import java.math.BigDecimal;

/**
 * Par {@code {value, confidence}} de um campo extraído — a unidade do contrato normalizado
 * (roadmap §1.3). É o valor persistido dentro do JSONB {@code staged_transactions.fields},
 * keyed pelo nome do campo (amount, transaction_date, description, ...).
 *
 * <p>{@code value} é intencionalmente {@link Object}: o valor é heterogêneo por campo
 * (número para amount, string ISO para datas, string livre para description). JSON acomoda
 * isso nativamente; enrijecer num tipo por campo derrotaria a decisão de guardar em JSONB
 * flexível (spec §2.b) — só se promove um campo a coluna própria quando surgir necessidade
 * real de indexá-lo.
 *
 * <p>Objeto de domínio (não DTO): mora dentro da entidade e nunca cruza a borda do controller.
 */
public record StagedFieldValue(Object value, BigDecimal confidence) {}
