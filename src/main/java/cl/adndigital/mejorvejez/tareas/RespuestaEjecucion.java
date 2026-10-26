package cl.adndigital.mejorvejez.tareas;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Una corrida de un proceso programado ({@code EJECUCION_PROCESO}), tal como
 * la ve el administrador ({@code /api/administracion/procesos}).
 * {@code fechaFin} es {@code null} mientras la corrida sigue {@code
 * INICIADA}.
 */
public record RespuestaEjecucion(long idEjecucion, String tipoEjecucion, OffsetDateTime inicioVentana,
        OffsetDateTime finVentana, long idEstado, OffsetDateTime fechaInicio, OffsetDateTime fechaFin,
        int cantidadCreada, int cantidadErrores, String resumenErrores) {

    /** Ruling final de F4: los instantes salen siempre con el offset de America/Santiago. */
    public RespuestaEjecucion {
        inicioVentana = ZonaNegocio.enZona(inicioVentana);
        finVentana = ZonaNegocio.enZona(finVentana);
        fechaInicio = ZonaNegocio.enZona(fechaInicio);
        fechaFin = ZonaNegocio.enZona(fechaFin);
    }

    static RespuestaEjecucion desde(EjecucionProceso e) {
        return new RespuestaEjecucion(e.getIdEjecucion(), e.getTipoEjecucion(), e.getInicioVentana(),
                e.getFinVentana(), e.getIdEstado(), e.getFechaInicio(), e.getFechaFin(), e.getCantidadCreada(),
                e.getCantidadErrores(), e.getResumenErrores());
    }
}
