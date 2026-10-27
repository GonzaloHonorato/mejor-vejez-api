package cl.adndigital.mejorvejez.sucesion;

import jakarta.validation.constraints.NotNull;

/**
 * Una reasignación pedida al completar la sucesión (regla 15 de la Fase 7):
 * la tarea {@code idTarea}, que debe ser una condición pendiente, pasa a
 * {@code idUsuario}, que no puede ser el coordinador saliente. La aplica
 * {@code tareas.ServicioAsignacionTareas#asignar} con el motivo fijo
 * "Sucesión de la coordinación".
 */
public record SolicitudReasignacion(
        @NotNull(message = "La tarea es obligatoria.")
        Long idTarea,
        @NotNull(message = "El nuevo responsable es obligatorio.")
        Long idUsuario) {
}
