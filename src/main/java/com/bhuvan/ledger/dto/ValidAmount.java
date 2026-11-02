package com.bhuvan.ledger.dto;

import com.bhuvan.ledger.util.Money;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A positive rupee amount string with at most two decimals, e.g. "1000" or "1000.50". Null is left to @NotNull. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidAmount.Validator.class)
public @interface ValidAmount {

    String message() default "amount must be a positive value like 1000 or 1000.50, with at most 2 decimals";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidAmount, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            try {
                Money.parse(value); // same rules the service uses, including "not zero"
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }
}
