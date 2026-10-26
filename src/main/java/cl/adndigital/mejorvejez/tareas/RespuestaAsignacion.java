package cl.adndigital.mejorvejez.tareas;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/** Fila de {@link AsignacionTarea} lista para el detalle de una tarea (HU-06). */
public record RespuestaAsignacion(long idAsignacion, long idUsuarioAsignado, String nombreAsignado,
        String nombreAsignador, OffsetDateTime fechaAsignacion, OffsetDateTime fechaTermino, long idEstado,
        String motivo) {

    /** Ruling final de F4: los instantes salen siempre con el offset de America/Santiago. */
    public RespuestaAsignacion {
        fechaAsignacion = ZonaNegocio.enZona(fechaAsignacion);
        fechaTermino = ZonaNegocio.enZona(fechaTermino);
    }
}
