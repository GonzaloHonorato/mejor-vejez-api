package cl.adndigital.mejorvejez.auditoria;

import java.time.OffsetDateTime;

/**
 * Una fila cruda de {@code EVENTO_AUDITORIA} leída por {@link
 * RepositorioConsultaAuditoria}, con los tipos ya resueltos por JDBC (no un
 * {@code Map<String,Object>}: evita depender de qué tipo exacto devuelve el
 * driver de Oracle para cada columna). {@code ServicioConsultaAuditoria} la
 * convierte en {@link RespuestaEventoAuditoria} (offset de Santiago,
 * {@code RespuestaPersona} del actor, JSON de {@code detallesJson}
 * interpretado y filtrado).
 *
 * @param idUsuarioActor {@code null} = proceso automático.
 * @param nombreActor    {@code null} cuando {@code idUsuarioActor} es
 *                       {@code null} ({@code LEFT JOIN USUARIO}).
 * @param detallesJson   el JSON crudo, sin interpretar ni filtrar todavía;
 *                       puede ser {@code null}.
 */
record FilaEventoAuditoria(
        Long idEvento,
        OffsetDateTime fechaEvento,
        String nombreServicio,
        String tipoEntidad,
        String idEntidad,
        Long idGrupo,
        Long idUsuarioActor,
        String nombreActor,
        String codigoAccion,
        Long idResultado,
        String codigoResultado,
        String idCorrelacion,
        String detallesJson) {
}
