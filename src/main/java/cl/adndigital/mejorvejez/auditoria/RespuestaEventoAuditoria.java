package cl.adndigital.mejorvejez.auditoria;

import java.time.OffsetDateTime;
import java.util.Map;

import cl.adndigital.mejorvejez.comun.RespuestaPersona;

/**
 * Un evento de {@code EVENTO_AUDITORIA} tal como se expone por la API
 * (HU-12, TSA-52). {@code fechaEvento} va con el offset de Santiago
 * ({@code ZonaNegocio.enZona}). {@code detalles} ya pasó por {@link
 * FiltroDatosSensibles} (defensa en profundidad: vuelve a filtrar lo que ya
 * se filtró al escribir); si {@code DETALLES_JSON} no se pudo interpretar
 * como JSON, es {@code null}.
 *
 * @param actor {@code null} = proceso automático (sin usuario detrás).
 */
public record RespuestaEventoAuditoria(
        Long idEvento,
        OffsetDateTime fechaEvento,
        String nombreServicio,
        String tipoEntidad,
        String idEntidad,
        Long idGrupo,
        RespuestaPersona actor,
        String codigoAccion,
        Long idResultado,
        String codigoResultado,
        String idCorrelacion,
        Map<String, Object> detalles) {
}
