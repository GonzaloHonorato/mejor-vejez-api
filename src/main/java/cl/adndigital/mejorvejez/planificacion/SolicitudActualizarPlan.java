package cl.adndigital.mejorvejez.planificacion;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Datos para actualizar un plan de medicamentos ({@code PUT
 * .../planes/{idPlan}}). Sin {@code idAdultoMayor}: el plan no cambia de
 * adulto mayor una vez creado.
 *
 * <p>El constructor compacto recorta {@code nombrePlan} antes de que corra
 * Bean Validation, igual que {@link SolicitudCrearPlan}.
 */
public record SolicitudActualizarPlan(
        @NotBlank(message = "El nombre del plan es obligatorio.")
        @Size(max = 160, message = "El nombre del plan no puede superar los 160 caracteres.")
        String nombrePlan,
        @NotNull(message = "La fecha de inicio de la vigencia es obligatoria.")
        LocalDate vigenteDesde,
        LocalDate vigenteHasta) {

    public SolicitudActualizarPlan {
        nombrePlan = nombrePlan == null ? null : nombrePlan.trim();
    }
}
