package cl.adndigital.mejorvejez.planificacion;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Nueva cantidad disponible de un medicamento del plan ({@code PUT
 * .../detalles/{idDetalle}/cantidad}), en la misma unidad que la dosis. No
 * toca la cantidad inicial.
 */
public record SolicitudActualizarCantidad(
        @NotNull(message = "La cantidad disponible es obligatoria.")
        @PositiveOrZero(message = "La cantidad disponible no puede ser negativa.")
        @Digits(integer = 9, fraction = 3, message = "La cantidad disponible admite hasta 9 enteros y 3 decimales.")
        BigDecimal cantidadDisponible) {
}
