package cl.adndigital.mejorvejez.tareas;

import java.util.List;

/** Detalle completo de una tarea: la tarea, su historial de estados y sus asignaciones, todo en orden cronológico. */
public record RespuestaTareaDetallada(RespuestaTarea tarea, List<RespuestaHistorialEstado> historial,
        List<RespuestaAsignacion> asignaciones) {
}
