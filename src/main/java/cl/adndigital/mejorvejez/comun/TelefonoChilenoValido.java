package cl.adndigital.mejorvejez.comun;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * El texto anotado, si viene, es un teléfono chileno según {@link
 * TelefonoChileno}. Nulo o vacío pasan: para exigirlo, se combina con
 * {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = ValidadorTelefonoChileno.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TelefonoChilenoValido {

    String message() default TelefonoChileno.MENSAJE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
