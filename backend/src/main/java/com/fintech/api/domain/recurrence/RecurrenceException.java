package com.fintech.api.domain.recurrence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma EXDATE (RFC 5545): a ocorrência que o usuário "Pulou". Tabela esparsa — só
 * ganha linha quando há um pulo de fato; uma regra nunca pulada tem zero linhas.
 */
@Entity
@Table(name = "recurrence_exceptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RecurrenceException {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    @ToString.Exclude
    private RecurrenceRule rule;

    @Column(name = "occurrence_date", nullable = false)
    private LocalDate occurrenceDate;
}
