package com.fintech.api.dto.recurrence;

import com.fintech.api.service.recurrence.RecurrenceExpander;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

/**
 * Valida o campo {@code rrule} contra o subconjunto financeiro suportado. O
 * {@link RecurrenceExpander} é injetado — Hibernate Validator resolve validadores como
 * beans Spring (SpringConstraintValidatorFactory), sem configuração extra.
 */
@RequiredArgsConstructor
public class RruleValidator implements ConstraintValidator<ValidRrule, String> {

    private final RecurrenceExpander expander;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return false;
        return expander.isSupported(value);
    }
}
