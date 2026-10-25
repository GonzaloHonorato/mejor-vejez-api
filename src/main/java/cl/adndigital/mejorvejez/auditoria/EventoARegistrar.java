package cl.adndigital.mejorvejez.auditoria;

import java.util.Map;

/**
 * Datos de negocio de un evento de auditoría, listos para que
 * {@link ServicioAuditoria} los persista. No incluye el ID de correlación:
 * {@code ServicioAuditoria} lo toma del MDC (lo deja ahí
 * {@code FiltroCorrelacion}), ni la fecha del evento, que se calcula con el
 * reloj de negocio en el momento de registrar.
 *
 * @param modulo          servicio o módulo que origina el evento (columna
 *                        {@code NOMBRE_SERVICIO}).
 * @param tipoEntidad     tipo de la entidad afectada (p. ej. {@code "USUARIO"}).
 * @param idEntidad       identificador de la entidad afectada, como texto.
 * @param idGrupo         grupo familiar relacionado, si corresponde.
 * @param idUsuarioActor  usuario que ejecuta la acción; {@code null} cuando el
 *                        actor es un proceso automático.
 * @param codigoAccion    código de la acción ejecutada.
 * @param resultado       resultado de la acción.
 * @param detalles        datos adicionales del evento; se serializan como
 *                        JSON. Las claves que contengan (sin distinguir
 *                        mayúsculas) {@code token}, {@code clave},
 *                        {@code password}, {@code secret} o
 *                        {@code authorization} se omiten (HU-12).
 */
public record EventoARegistrar(
        String modulo,
        String tipoEntidad,
        String idEntidad,
        Long idGrupo,
        Long idUsuarioActor,
        String codigoAccion,
        ResultadoAuditoria resultado,
        Map<String, Object> detalles) {
}
