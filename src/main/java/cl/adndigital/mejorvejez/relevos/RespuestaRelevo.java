package cl.adndigital.mejorvejez.relevos;

import java.time.OffsetDateTime;
import java.util.List;

import cl.adndigital.mejorvejez.comun.RespuestaPersona;

/**
 * Relevo completo, con su foto de tareas y alertas pendientes (HU-11). El
 * grupo y el adulto se identifican con su id y su nombre; las fechas van con
 * el offset de Santiago ({@code ZonaNegocio#enZona}).
 */
public record RespuestaRelevo(
        Long idRelevo,
        Long idGrupo,
        String nombreGrupo,
        Long idAdulto,
        String nombreAdulto,
        RespuestaPersona saliente,
        RespuestaPersona entrante,
        RespuestaPersona creador,
        OffsetDateTime fechaTraspaso,
        String notas,
        Long idEstado,
        String codigoEstado,
        int totalTareas,
        int totalAlertas,
        List<RespuestaDetalleRelevo> detalles) {
}
