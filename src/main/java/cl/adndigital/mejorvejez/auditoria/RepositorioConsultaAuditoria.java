package cl.adndigital.mejorvejez.auditoria;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Consulta paginada, de solo lectura, de {@code EVENTO_AUDITORIA} (HU-12,
 * TSA-52), con el mismo estilo que {@code panel.RepositorioConsultaPanel} /
 * {@code tareas.RepositorioConsultaGeneracion}: el {@code WHERE} se arma con
 * fragmentos constantes y parámetros nombrados, nunca concatenando texto que
 * venga de afuera. Hace {@code COUNT(*)} + {@code OFFSET ... FETCH NEXT ...
 * ROWS ONLY}, con un {@code LEFT JOIN USUARIO} para el nombre del actor
 * ({@code null} = proceso automático) y un {@code JOIN CATALOGO_ESTADOS}
 * para el código del resultado.
 *
 * <p>{@code codigoResultado} de {@link FiltroConsultaAuditoria} ya viene
 * validado por {@code ServicioConsultaAuditoria} (una de {@code EXITO} |
 * {@code FALLO} | {@code RECHAZADA}) antes de llegar aquí; acá solo se
 * resuelve a su id con {@link ServicioCatalogos}, nunca como literal en el
 * SQL.
 */
@Repository
class RepositorioConsultaAuditoria {

    private static final String DESDE_Y_JOINS = "FROM EVENTO_AUDITORIA e "
            + "LEFT JOIN USUARIO u ON u.ID_USUARIO = e.ID_USUARIO_ACTOR "
            + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = e.ID_RESULTADO";

    private static final String COLUMNAS = "e.ID_EVENTO_AUDITORIA, e.FECHA_EVENTO, e.NOMBRE_SERVICIO, "
            + "e.TIPO_ENTIDAD, e.ID_ENTIDAD, e.ID_GRUPO, e.ID_USUARIO_ACTOR, u.NOMBRE AS NOMBRE_ACTOR, "
            + "e.CODIGO_ACCION, e.ID_RESULTADO, ce.CODIGO_ESTADO AS CODIGO_RESULTADO, e.ID_CORRELACION, "
            + "e.DETALLES_JSON";

    private static final RowMapper<FilaEventoAuditoria> MAPEADOR_FILA = (fila, numeroFila) -> new FilaEventoAuditoria(
            fila.getLong("ID_EVENTO_AUDITORIA"),
            fila.getObject("FECHA_EVENTO", OffsetDateTime.class),
            fila.getString("NOMBRE_SERVICIO"),
            fila.getString("TIPO_ENTIDAD"),
            fila.getString("ID_ENTIDAD"),
            fila.getObject("ID_GRUPO", Long.class),
            fila.getObject("ID_USUARIO_ACTOR", Long.class),
            fila.getString("NOMBRE_ACTOR"),
            fila.getString("CODIGO_ACCION"),
            fila.getLong("ID_RESULTADO"),
            fila.getString("CODIGO_RESULTADO"),
            fila.getString("ID_CORRELACION"),
            fila.getString("DETALLES_JSON"));

    private final NamedParameterJdbcTemplate jdbc;
    private final ServicioCatalogos servicioCatalogos;

    RepositorioConsultaAuditoria(NamedParameterJdbcTemplate jdbc, ServicioCatalogos servicioCatalogos) {
        this.jdbc = jdbc;
        this.servicioCatalogos = servicioCatalogos;
    }

    /** Cantidad total de eventos que cumplen el filtro, sin paginar. */
    long contar(Long idGrupoFijo, FiltroConsultaAuditoria filtro) {
        MapSqlParameterSource parametros = new MapSqlParameterSource();
        String condicion = condiciones(parametros, idGrupoFijo, filtro);
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + DESDE_Y_JOINS + condicion, parametros, Long.class);
        return total == null ? 0 : total;
    }

    /**
     * Página de eventos que cumplen el filtro, ordenados por {@code
     * FECHA_EVENTO} y luego {@code ID_EVENTO_AUDITORIA}, descendentes.
     */
    List<FilaEventoAuditoria> buscar(Long idGrupoFijo, FiltroConsultaAuditoria filtro, int pagina, int tamano) {
        MapSqlParameterSource parametros = new MapSqlParameterSource();
        String condicion = condiciones(parametros, idGrupoFijo, filtro);
        // (long) evita que pagina * tamano desborde int con una página grande (el resultado quedaría
        // negativo y Oracle lo trataría como 0, devolviendo la página 0 rotulada con otro número).
        parametros.addValue("desplazamiento", (long) pagina * tamano).addValue("tamano", tamano);
        String sql = "SELECT " + COLUMNAS + " " + DESDE_Y_JOINS + condicion
                + " ORDER BY e.FECHA_EVENTO DESC, e.ID_EVENTO_AUDITORIA DESC "
                + "OFFSET :desplazamiento ROWS FETCH NEXT :tamano ROWS ONLY";
        return jdbc.query(sql, parametros, MAPEADOR_FILA);
    }

    /**
     * Condición {@code WHERE} común a {@link #contar} y {@link #buscar}.
     * {@code idGrupoFijo} (no nulo) fija {@code e.ID_GRUPO} (consulta por
     * grupo); si es {@code null}, usa el {@code idGrupo} opcional de {@code
     * filtro} (solo tiene sentido en la consulta global: {@code null} ahí
     * recorre todos los grupos, incluidos los eventos sin grupo). Los demás
     * filtros son siempre opcionales. Nunca enlaza un parámetro {@code
     * null} ni concatena un valor que venga de afuera.
     */
    private String condiciones(MapSqlParameterSource parametros, Long idGrupoFijo, FiltroConsultaAuditoria filtro) {
        List<String> condiciones = new ArrayList<>();

        Long idGrupo = idGrupoFijo != null ? idGrupoFijo : filtro.idGrupo();
        if (idGrupo != null) {
            parametros.addValue("idGrupo", idGrupo);
            condiciones.add("e.ID_GRUPO = :idGrupo");
        }
        if (filtro.tipoEntidad() != null) {
            parametros.addValue("tipoEntidad", filtro.tipoEntidad());
            condiciones.add("e.TIPO_ENTIDAD = :tipoEntidad");
        }
        if (filtro.idEntidad() != null) {
            parametros.addValue("idEntidad", filtro.idEntidad());
            condiciones.add("e.ID_ENTIDAD = :idEntidad");
        }
        if (filtro.codigoAccion() != null) {
            parametros.addValue("codigoAccion", filtro.codigoAccion());
            condiciones.add("e.CODIGO_ACCION = :codigoAccion");
        }
        if (filtro.codigoResultado() != null) {
            parametros.addValue("idResultado",
                    servicioCatalogos.idEstado("EVENTO_AUDITORIA", "RESULTADO", filtro.codigoResultado()));
            condiciones.add("e.ID_RESULTADO = :idResultado");
        }
        if (filtro.desde() != null) {
            parametros.addValue("desdeInicio", filtro.desde().atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime());
            condiciones.add("e.FECHA_EVENTO >= :desdeInicio");
        }
        if (filtro.hasta() != null) {
            parametros.addValue("hastaExclusivo",
                    filtro.hasta().plusDays(1).atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime());
            condiciones.add("e.FECHA_EVENTO < :hastaExclusivo");
        }

        return condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones);
    }
}
