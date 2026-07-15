package com.fintech.api.service.recurrence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceExpanderTest {

    private final RecurrenceExpander expander = new RecurrenceExpander();

    @Test
    void expandeMensalNoDiaFixoDentroDaJanela() {
        List<LocalDate> dates = expander.expand(
                "FREQ=MONTHLY;BYMONTHDAY=15", LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31));
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 15), LocalDate.of(2026, 5, 15));
    }

    // Teste A (reframado): "fim do mês" = BYMONTHDAY=-1 ancora no último dia válido,
    // resolvendo nativamente fev (28/29), abr (30), mar (31).
    @Test
    void ultimoDiaDoMesAncoraNoDiaValido() {
        List<LocalDate> dates = expander.expand(
                "FREQ=MONTHLY;BYMONTHDAY=-1", LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 2, 1), LocalDate.of(2024, 4, 30));
        assertThat(dates).containsExactly(
                LocalDate.of(2024, 2, 29),  // 2024 é bissexto
                LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 4, 30));
    }

    @Test
    void respeitaCountComoLimiteRigido() {
        List<LocalDate> dates = expander.expand(
                "FREQ=MONTHLY;BYMONTHDAY=10;COUNT=2", LocalDate.of(2026, 1, 10),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(dates).containsExactly(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 2, 10));
    }

    @Test
    void aceitaSubconjuntoERejeitaForaDele() {
        assertThat(expander.isSupported("FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=5")).isTrue();
        assertThat(expander.isSupported("FREQ=YEARLY;BYMONTHDAY=1")).isTrue();
        assertThat(expander.isSupported("FREQ=WEEKLY;BYDAY=MO")).isFalse();
        assertThat(expander.isSupported("FREQ=MONTHLY;BYSETPOS=-1")).isFalse();
        assertThat(expander.isSupported("lixo")).isFalse();
    }
}
