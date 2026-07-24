package com.fintech.api.domain.imports;

import com.fintech.api.domain.enums.StagedTransactionStatus;
import com.fintech.api.domain.tenant.Tenant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Transação em STAGING: o dado extraído (probabilístico) antes de virar {@code Transaction}.
 * Vive em tabela própria para não contaminar o núcleo com dado incerto (spec §2.a).
 *
 * <p>{@code tenant} é DENORMALIZADO (também está no batch): defesa nº1 contra vazamento de
 * tenant — toda leitura filtra o tenant direto nesta linha, sem depender de um JOIN correto
 * em {@code import_batches} (spec §3).
 */
@Entity
@Table(name = "staged_transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StagedTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    @ToString.Exclude
    private ImportBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude
    private Tenant tenant;

    /**
     * {@code {value, confidence}} por campo, keyed pelo nome do campo. JSONB nativo do
     * Hibernate 6 ({@code @JdbcTypeCode(SqlTypes.JSON)}) — zero dependência extra (spec §2.b).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, StagedFieldValue> fields;

    @Column(name = "suggested_category_code", length = 100)
    private String suggestedCategoryCode;

    @Column(name = "suggested_category_confidence")
    private BigDecimal suggestedCategoryConfidence;

    @Column(name = "overall_confidence")
    private BigDecimal overallConfidence;

    // Derivado no código por threshold (nunca gravado pelo modelo — spec §2.f).
    @Column(name = "requires_review", nullable = false)
    private boolean requiresReview;

    // Reservado para dedup (Fase 4). Coluna existe desde já a custo zero.
    @Column(name = "duplicate_candidate_of")
    private UUID duplicateCandidateOf;

    // Preenchido no commit (Fase 1), quando a staged é promovida a Transaction.
    @Column(name = "promoted_transaction_id")
    private UUID promotedTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StagedTransactionStatus status;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
