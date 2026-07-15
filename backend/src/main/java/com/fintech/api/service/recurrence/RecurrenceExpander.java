package com.fintech.api.service.recurrence;

import org.dmfs.rfc5545.DateTime;
import org.dmfs.rfc5545.recur.Freq;
import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException;
import org.dmfs.rfc5545.recur.RecurrenceRule;
import org.dmfs.rfc5545.recur.RecurrenceRuleIterator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Wrapper fino sobre lib-recur (RFC 5545). Concentra duas responsabilidades:
 * expandir um RRULE numa janela de datas e validar que o RRULE está no
 * subconjunto financeiro suportado.
 *
 * <p><b>Gotcha 1 (mês 0-based):</b> o {@link DateTime} do lib-recur usa mês 0-based
 * (janeiro = 0), diferente de {@link LocalDate}. As conversões abaixo compensam.
 *
 * <p><b>Gotcha 2 (validação por chave, não por hasPart):</b> o parser lax do lib-recur
 * <i>descarta silenciosamente</i> partes inválidas no contexto (ex.: {@code BYSETPOS} sem
 * outro {@code BYxxx}), então {@code rule.hasPart(BYSETPOS)} retornaria false e a regra
 * passaria. Por isso a validação varre as <b>chaves do próprio rrule</b> contra um conjunto
 * proibido — determinístico e independente do modo do parser. (Isso também evita referenciar
 * o enum aninhado {@code RecurrenceRule.Part}, cuja ordem de init no lib-recur é frágil.)
 */
@Component
public class RecurrenceExpander {

    // Partes de "app de agenda" — fora do pensamento mensal do orçamento. Chaves RRULE cruas.
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "BYDAY", "BYSETPOS", "BYWEEKNO", "BYYEARDAY", "BYHOUR", "BYMINUTE", "BYSECOND");

    public List<LocalDate> expand(String rrule, LocalDate startDate, LocalDate from, LocalDate to) {
        List<LocalDate> result = new ArrayList<>();
        try {
            RecurrenceRule rule = new RecurrenceRule(rrule);
            RecurrenceRuleIterator it = rule.iterator(toDateTime(startDate));

            // Pula direto para o início da janela (eficiência). Só faz sentido quando a janela
            // começa depois da âncora — caso contrário a iteração já parte do start.
            if (from.isAfter(startDate)) {
                it.fastForward(toDateTime(from));
            }

            DateTime upperBound = toDateTime(to.plusDays(1)); // 'to' inclusivo
            while (it.hasNext()) {
                DateTime next = it.nextDateTime();
                if (!next.before(upperBound)) break; // saiu da janela (ou COUNT/UNTIL acabou antes)
                result.add(toLocalDate(next));
            }
        } catch (InvalidRecurrenceRuleException e) {
            // rrule inválido nunca deveria chegar aqui (validado na entrada), mas a projeção
            // não pode derrubar a lista inteira por uma regra ruim: ignora.
            return List.of();
        }
        return result;
    }

    public boolean isSupported(String rrule) {
        if (rrule == null || rrule.isBlank()) return false;
        try {
            // Parse só para garantir validade sintática e ler a frequência.
            Freq freq = new RecurrenceRule(rrule).getFreq();
            // Só MONTHLY/YEARLY: o resto (weekly/daily) é de app de agenda, não de finanças.
            if (freq != Freq.MONTHLY && freq != Freq.YEARLY) return false;
        } catch (InvalidRecurrenceRuleException e) {
            return false;
        }
        // Rejeita partes proibidas varrendo as chaves cruas (parser lax descarta algumas — gotcha 2).
        for (String part : rrule.toUpperCase().split(";")) {
            String key = part.split("=", 2)[0].trim();
            if (FORBIDDEN_KEYS.contains(key)) return false;
        }
        return true;
    }

    private DateTime toDateTime(LocalDate d) {
        // mês 0-based no lib-recur; data "floating" (all-day, sem timezone) basta para datas de calendário.
        return new DateTime(d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
    }

    private LocalDate toLocalDate(DateTime dt) {
        return LocalDate.of(dt.getYear(), dt.getMonth() + 1, dt.getDayOfMonth());
    }
}
