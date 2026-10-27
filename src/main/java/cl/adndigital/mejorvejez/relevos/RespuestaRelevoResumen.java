package cl.adndigital.mejorvejez.relevos;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.RespuestaPersona;

/** Fila del listado paginado de relevos de un grupo (HU-11): igual a {@link RespuestaRelevo}, sin notas ni detalles. */
public record RespuestaRelevoResumen(
        Long idRelevo,
        Long idGrupo,
        String nombreGrupo,
        Long idAdulto,
        String nombreAdulto,
        RespuestaPersona saliente,
        RespuestaPersona entrante,
        RespuestaPersona creador,
        OffsetDateTime fechaTraspaso,
        Long idEstado,
        String codigoEstado,
        int totalTareas,
        int totalAlertas) {
}
