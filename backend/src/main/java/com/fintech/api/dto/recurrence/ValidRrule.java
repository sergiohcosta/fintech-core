package com.fintech.api.dto.recurrence;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = RruleValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidRrule {
    String message() default "RRULE fora do subconjunto suportado (FREQ MONTHLY/YEARLY, INTERVAL, BYMONTHDAY, UNTIL, COUNT)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
