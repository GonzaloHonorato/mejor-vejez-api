package cl.adndigital.mejorvejez.planificacion;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Datos para crear un plan de medicamentos ({@code POST .../planes}).
 *
 * <p>El constructor compacto recorta {@code nombrePlan} antes de que corra
 * Bean Validation, igual que {@code adultos.SolicitudGuardarAdultoMayor}.
 */
public record SolicitudCrearPlan(
        @NotNull(message = "El adulto mayor es obligatorio.")
        Long idAdultoMayor,
        @NotBlank(message = "El nombre del plan es obligatorio.")
        @Size(max = 160, message = "El nombre del plan no puede superar los 160 caracteres.")
        String nombrePlan,
        @NotNull(message = "La fecha de inicio de la vigencia es obligatoria.")
        LocalDate vigenteDesde,
        LocalDate vigenteHasta) {

    public SolicitudCrearPlan {
        nombrePlan = nombrePlan == null ? null : nombrePlan.trim();
    }
}
