package cl.adndigital.mejorvejez.comun;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Implementa {@link TelefonoChilenoValido}. */
public class ValidadorTelefonoChileno implements ConstraintValidator<TelefonoChilenoValido, String> {

    @Override
    public boolean isValid(String valor, ConstraintValidatorContext contexto) {
        return valor == null || valor.isBlank() || TelefonoChileno.esValido(valor);
    }
}
