package com.fintech.api.domain.imports;

import com.fintech.api.domain.enums.ImportBatchStatus;
import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lote de importação: agrupa as transações extraídas de uma mesma origem (uma imagem, um CSV...)
 * e carrega a proveniência (qual extrator/versão gerou os dados) e o estado do ciclo de revisão.
 */
@Entity
@Table(name = "import_batches")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)  // entidade JPA: identidade só pelo ID
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    @ToString.Exclude
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_mode", nullable = false, length = 20)
    private ImportMode importMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private ImportSourceType sourceType;

    @Column(name = "extractor_used", length = 100)
    private String extractorUsed;

    @Column(name = "extractor_version", length = 50)
    private String extractorVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportBatchStatus status;

    // Motivo legível da falha — só preenchido quando status = FAILED (#193). Texto pronto para
    // exibição, redigido pelo backend; nunca a mensagem crua de uma exceção de infra.
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    // SHA-256 (hex) dos bytes do arquivo — chave de dedup por (tenant, hash), Onda 4 da Fase 2.
    // NULL em batches de mock/legado (sem arquivo de origem).
    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    // Nome do arquivo enviado — proveniência, exibido na mensagem de conflito (409) de reimportação.
    @Column(name = "source_filename")
    private String sourceFilename;

    // Proveniência ESTRUTURADA (V28) — ao lado de extractorUsed (que continua sendo a string
    // legível para humano). Estas colunas são a forma CONSULTÁVEL do mesmo fato: permitem
    // GROUP BY provider e "quantos batches caíram em fallback" sem LIKE frágil em extractorUsed.
    @Column(name = "extractor_provider", length = 30)
    private String extractorProvider;

    // NULL para parser determinístico (CSV/OFX/PDF texto) — não existe "modelo" nesse caminho.
    @Column(name = "extractor_model", length = 100)
    private String extractorModel;

    // Provider tentado ANTES e que falhou por indisponibilidade. NULL = não houve fallback —
    // é este campo, sozinho, que responde "houve fallback?" (spec §5.1: NULL já é a resposta).
    @Column(name = "fallback_from", length = 30)
    private String fallbackFrom;

    // Motivo curto do fallback (quota, unavailable, auth, rejected_input). Só populado quando
    // fallbackFrom não é NULL. Onda 4 (política de fallback) é quem grava; esta Onda só abre a coluna.
    @Column(name = "fallback_reason", length = 200)
    private String fallbackReason;

    // Latência (ms) da chamada ao provider que VENCEU (não soma tentativas de fallback anteriores).
    @Column(name = "extraction_latency_ms")
    private Integer extractionLatencyMs;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
