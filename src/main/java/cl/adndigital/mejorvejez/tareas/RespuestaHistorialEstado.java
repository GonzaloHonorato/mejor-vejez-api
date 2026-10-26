package cl.adndigital.mejorvejez.tareas;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Fila de {@link HistorialEstadoTarea} lista para el detalle de una tarea
 * (HU-07). {@code idEstadoAnterior} es {@code null} en la fila de creación
 * (sin estado previo).
 */
public record RespuestaHistorialEstado(Long idEstadoAnterior, long idEstadoNuevo, String nombreUsuario,
        OffsetDateTime fechaCambio, String observacion) {

    /** Ruling final de F4: los instantes salen siempre con el offset de America/Santiago. */
    public RespuestaHistorialEstado {
        fechaCambio = ZonaNegocio.enZona(fechaCambio);
    }
}
