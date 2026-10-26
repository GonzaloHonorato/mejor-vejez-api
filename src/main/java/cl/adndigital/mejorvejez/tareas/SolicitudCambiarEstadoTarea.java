package cl.adndigital.mejorvejez.tareas;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Datos para cambiar el estado de una tarea de cuidado (HU-07, TSA-40,
 * {@code POST .../tareas/{idTarea}/estado}). {@code observacion} es
 * obligatoria solo para {@code NO_REALIZADA} y {@code CANCELADA} (regla 10
 * de la Fase 4); la exige {@link ServicioEstadoTareas}, no Bean Validation
 * (depende del {@code idEstado} de la misma solicitud).
 *
 * <p>El constructor compacto recorta {@code observacion} y la deja en
 * {@code null} si queda en blanco, antes de que corra Bean Validation (mismo
 * patrón que {@code planificacion.SolicitudGuardarDetalle}).
 */
public record SolicitudCambiarEstadoTarea(
        @NotNull(message = "El estado es obligatorio.")
        Long idEstado,
        @Size(max = 1000, message = "La observación no puede superar los 1000 caracteres.")
        String observacion) {

    public SolicitudCambiarEstadoTarea {
        observacion = recortarOpcional(observacion);
    }

    private static String recortarOpcional(String texto) {
        if (texto == null) {
            return null;
        }
        String recortado = texto.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
