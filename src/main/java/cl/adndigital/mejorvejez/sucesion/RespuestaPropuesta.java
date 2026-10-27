package cl.adndigital.mejorvejez.sucesion;

import java.time.OffsetDateTime;
import java.util.List;

import cl.adndigital.mejorvejez.comun.RespuestaPersona;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.tareas.RespuestaTarea;

/**
 * Propuesta de sucesión de la coordinación (HU-13, regla 24 de la Fase 7).
 * {@code condicionesPendientes} son las tareas abiertas del saliente (regla
 * 19), calculadas en vivo solo si la propuesta está {@code PENDIENTE} o
 * {@code ACEPTADA}; en cualquier otro estado, y en el historial, va vacía.
 * Las fechas van con el offset de Santiago ({@code ZonaNegocio#enZona}).
 */
public record RespuestaPropuesta(
        Long idPropuesta,
        Long idGrupo,
        RespuestaPersona saliente,
        RespuestaPersona candidato,
        Long idEstado,
        String codigoEstado,
        OffsetDateTime fechaCreacion,
        OffsetDateTime fechaRespuesta,
        OffsetDateTime fechaCancelacion,
        OffsetDateTime fechaCompletado,
        String motivoCancelacion,
        List<RespuestaTarea> condicionesPendientes) {

    public RespuestaPropuesta {
        fechaCreacion = ZonaNegocio.enZona(fechaCreacion);
        fechaRespuesta = ZonaNegocio.enZona(fechaRespuesta);
        fechaCancelacion = ZonaNegocio.enZona(fechaCancelacion);
        fechaCompletado = ZonaNegocio.enZona(fechaCompletado);
        condicionesPendientes = condicionesPendientes == null ? List.of() : List.copyOf(condicionesPendientes);
    }
}
