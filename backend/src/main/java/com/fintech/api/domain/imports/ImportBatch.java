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

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
