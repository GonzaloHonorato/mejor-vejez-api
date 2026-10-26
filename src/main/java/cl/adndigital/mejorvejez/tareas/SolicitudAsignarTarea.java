package cl.adndigital.mejorvejez.tareas;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Datos para asignar o reasignar el responsable de una tarea de cuidado
 * (HU-06, TSA-39, {@code PUT .../tareas/{idTarea}/asignacion}). {@code
 * motivo} es siempre opcional; lo exige {@link ServicioAsignacionTareas}
 * solo como texto libre de auditoría (nunca se valida su contenido).
 *
 * <p>El constructor compacto recorta {@code motivo} y lo deja en {@code
 * null} si queda en blanco, antes de que corra Bean Validation (mismo
 * patrón que {@code SolicitudCambiarEstadoTarea}).
 */
public record SolicitudAsignarTarea(
        @NotNull(message = "El responsable es obligatorio.")
        Long idUsuario,
        @Size(max = 500, message = "El motivo no puede superar los 500 caracteres.")
        String motivo) {

    public SolicitudAsignarTarea {
        motivo = recortarOpcional(motivo);
    }

    private static String recortarOpcional(String texto) {
        if (texto == null) {
            return null;
        }
        String recortado = texto.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
