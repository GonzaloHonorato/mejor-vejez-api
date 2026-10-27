package cl.adndigital.mejorvejez.auditoria;

import java.time.LocalDate;

/**
 * Filtros opcionales de la consulta de auditoría (HU-12, TSA-52). Todos los
 * campos son opcionales ({@code null} = sin filtrar por ese campo).
 *
 * @param idGrupo         solo lo usa la consulta global ({@code
 *                        ServicioConsultaAuditoria#consultarGlobal}); la
 *                        consulta por grupo fija el grupo por la ruta y
 *                        este campo se ignora.
 * @param tipoEntidad     {@code EVENTO_AUDITORIA.TIPO_ENTIDAD} exacto.
 * @param idEntidad       {@code EVENTO_AUDITORIA.ID_ENTIDAD} exacto.
 * @param codigoAccion    {@code EVENTO_AUDITORIA.CODIGO_ACCION} exacto.
 * @param codigoResultado uno de {@code EXITO} | {@code FALLO} | {@code
 *                        RECHAZADA}; otro valor es 422.
 * @param desde           fecha calendario (America/Santiago), inclusive.
 * @param hasta           fecha calendario (America/Santiago), inclusive;
 *                        {@code desde} posterior a {@code hasta} es 422.
 */
public record FiltroConsultaAuditoria(
        Long idGrupo,
        String tipoEntidad,
        String idEntidad,
        String codigoAccion,
        String codigoResultado,
        LocalDate desde,
        LocalDate hasta) {
}
