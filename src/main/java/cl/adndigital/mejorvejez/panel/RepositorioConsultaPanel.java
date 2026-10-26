package cl.adndigital.mejorvejez.panel;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;

/**
 * Consultas de solo lectura del panel familiar (HU-10, Fase 6) sobre {@code
 * TAREA_CUIDADO} y {@code ALERTA_CUIDADO} (Tarea 2), con el mismo estilo que
 * {@code tareas.RepositorioConsultaGeneracion} / {@code
 * alertas.RepositorioConsultaAlertas}: los estados se resuelven con {@link
 * ServicioCatalogos} y van como parámetros enlazados, nunca como literales
 * en el SQL; devuelve solo ids o conteos, nunca entidades (el ensamblado de
 * la respuesta queda en {@code ServicioPanel} + {@code
 * tareas.EnsambladorRespuestasTarea} / {@code
 * alertas.EnsambladorRespuestasAlerta}, sin N+1).
 *
 * <p>{@code idAdulto} es opcional en todas las consultas, igual que {@code
 * idGrupo} en {@code RepositorioConsultaGeneracion#planesCandidatos}: cuando
 * es {@code null}, la condición correspondiente simplemente no se agrega al
 * SQL (nunca se enlaza un parámetro {@code null}).
 *
 * <p>{@link #idsAlertas} recibe un {@code orden} que debe ser una de {@link
 * #ORDEN_DETECCION_DESCENDENTE} / {@link #ORDEN_PROGRAMADA_ASCENDENTE}: son
 * las dos únicas ordenaciones posibles (regla 3 "recientes" y regla 6
 * "preventivas" de la Fase 6), resueltas en {@link #clausulaOrden} con un
 * {@code switch} fijo; nunca se concatena en el SQL un texto que venga de
 * afuera.
 */
@Repository
class RepositorioConsultaPanel {

    /** Orden de {@link #idsAlertas} para las alertas de atraso recientes (regla 3 de la Fase 6). */
    static final String ORDEN_DETECCION_DESCENDENTE = "DETECCION_DESCENDENTE";

    /** Orden de {@link #idsAlertas} para las alertas preventivas (regla 6 de la Fase 6). */
    static final String ORDEN_PROGRAMADA_ASCENDENTE = "PROGRAMADA_ASCENDENTE";

    private final NamedParameterJdbcTemplate jdbc;
    private final ServicioCatalogos servicioCatalogos;

    RepositorioConsultaPanel(NamedParameterJdbcTemplate jdbc, ServicioCatalogos servicioCatalogos) {
        this.jdbc = jdbc;
        this.servicioCatalogos = servicioCatalogos;
    }

    /**
     * Conteo de tareas del rango {@code [desde, hasta)} por estado (regla 2
     * de la Fase 6), agrupadas por {@code ID_ESTADO}. Los estados sin
     * ninguna tarea en el rango no aparecen en el mapa.
     */
    Map<Long, Integer> conteoPorEstado(long idGrupo, Long idAdulto, OffsetDateTime desde, OffsetDateTime hasta) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("desde", desde)
                .addValue("hasta", hasta);
        String condicion = condicionGrupoYAdulto(parametros, idGrupo, idAdulto);
        List<Map<String, Object>> filas = jdbc.queryForList(
                "SELECT ID_ESTADO, COUNT(*) AS CANTIDAD FROM TAREA_CUIDADO WHERE " + condicion
                        + " AND FECHA_HORARIO >= :desde AND FECHA_HORARIO < :hasta GROUP BY ID_ESTADO",
                parametros);
        return filas.stream().collect(Collectors.toMap(fila -> ((Number) fila.get("ID_ESTADO")).longValue(),
                fila -> ((Number) fila.get("CANTIDAD")).intValue()));
    }

    /**
     * Ids de las tareas atrasadas (regla 2: PENDIENTE o EN_CURSO, {@code
     * ahora > FECHA_HORARIO + MINUTOS_TOLERANCIA}, {@code FECHA_HORARIO ≥
     * desde}), las más antiguas primero (por {@code FECHA_HORARIO} y luego
     * {@code ID_TAREA}), hasta {@code limite}.
     */
    List<Long> idsAtrasadas(long idGrupo, Long idAdulto, OffsetDateTime desde, OffsetDateTime ahora, int limite) {
        MapSqlParameterSource parametros = new MapSqlParameterSource();
        String condicion = condicionAtrasadas(parametros, idGrupo, idAdulto, desde, ahora);
        parametros.addValue("limite", limite);
        return jdbc.queryForList(
                "SELECT ID_TAREA FROM TAREA_CUIDADO WHERE " + condicion
                        + " ORDER BY FECHA_HORARIO, ID_TAREA FETCH FIRST :limite ROWS ONLY",
                parametros, Long.class);
    }

    /** Cantidad total de tareas atrasadas (regla 2), sin límite de lista. */
    int contarAtrasadas(long idGrupo, Long idAdulto, OffsetDateTime desde, OffsetDateTime ahora) {
        MapSqlParameterSource parametros = new MapSqlParameterSource();
        String condicion = condicionAtrasadas(parametros, idGrupo, idAdulto, desde, ahora);
        Integer cantidad = jdbc.queryForObject("SELECT COUNT(*) FROM TAREA_CUIDADO WHERE " + condicion, parametros,
                Integer.class);
        return cantidad == null ? 0 : cantidad;
    }

    /**
     * Ids de tareas PENDIENTE o EN_CURSO, de uno de los {@code tipos}
     * indicados, con {@code FECHA_HORARIO + MINUTOS_TOLERANCIA ≥ ahora} (no
     * atrasada todavía: decisión del controlador I-2 de la revisión final de
     * la Fase 6) y {@code FECHA_HORARIO < hasta}, las más próximas primero
     * (por {@code FECHA_HORARIO} y luego {@code ID_TAREA}), hasta {@code
     * limite}. Lo usan tanto los próximos horarios (regla 4) como las citas
     * (regla 5): ambas comparten el mismo filtro de estado y solo cambian
     * los tipos y la ventana de tiempo. Así una tarea dentro de su ventana
     * de tolerancia sigue en "próximos"/"citas" hasta que vence, y pasa
     * directo de ahí a "atrasadas" ({@link #condicionAtrasadas}), sin un
     * hueco en el medio donde no aparece en ninguna lista.
     */
    List<Long> idsTareas(long idGrupo, Long idAdulto, List<String> tipos, OffsetDateTime ahora, OffsetDateTime hasta,
            int limite) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("tipos", tipos)
                .addValue("ahora", ahora)
                .addValue("hasta", hasta)
                .addValue("limite", limite)
                .addValue("pendiente", estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE))
                .addValue("enCurso", estadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO));
        String condicion = condicionGrupoYAdulto(parametros, idGrupo, idAdulto);
        return jdbc.queryForList(
                "SELECT ID_TAREA FROM TAREA_CUIDADO WHERE " + condicion
                        + " AND TIPO_TAREA IN (:tipos) AND ID_ESTADO IN (:pendiente, :enCurso) "
                        + "AND FECHA_HORARIO + NUMTODSINTERVAL(MINUTOS_TOLERANCIA, 'MINUTE') >= :ahora "
                        + "AND FECHA_HORARIO < :hasta "
                        + "ORDER BY FECHA_HORARIO, ID_TAREA FETCH FIRST :limite ROWS ONLY",
                parametros, Long.class);
    }

    /**
     * Cantidad de {@code ALERTA_CUIDADO} de uno de los {@code tipos}
     * indicados, en el {@code idEstado} dado, con {@code FECHA_REVISION ≥
     * revisadasDesde} cuando este no es {@code null} (regla 3 de la Fase 6:
     * "abiertas" lo llama con {@code null}, "cerradasUltimos7Dias" con la
     * fecha de corte).
     */
    int contarAlertas(long idGrupo, Long idAdulto, List<String> tipos, long idEstado, OffsetDateTime revisadasDesde) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("tipos", tipos)
                .addValue("idEstado", idEstado);
        String condicion = condicionGrupoYAdulto(parametros, idGrupo, idAdulto)
                + " AND TIPO_ALERTA IN (:tipos) AND ID_ESTADO = :idEstado"
                + condicionRevisadasDesde(parametros, revisadasDesde);
        Integer cantidad = jdbc.queryForObject("SELECT COUNT(*) FROM ALERTA_CUIDADO WHERE " + condicion, parametros,
                Integer.class);
        return cantidad == null ? 0 : cantidad;
    }

    /**
     * Ids de las {@code ALERTA_CUIDADO} de uno de los {@code tipos}
     * indicados, en el {@code idEstado} dado, en el {@code orden} pedido
     * ({@link #ORDEN_DETECCION_DESCENDENTE} | {@link
     * #ORDEN_PROGRAMADA_ASCENDENTE}), hasta {@code limite}.
     */
    List<Long> idsAlertas(long idGrupo, Long idAdulto, List<String> tipos, long idEstado, String orden, int limite) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("tipos", tipos)
                .addValue("idEstado", idEstado)
                .addValue("limite", limite);
        String condicion = condicionGrupoYAdulto(parametros, idGrupo, idAdulto);
        return jdbc.queryForList(
                "SELECT ID_ALERTA FROM ALERTA_CUIDADO WHERE " + condicion
                        + " AND TIPO_ALERTA IN (:tipos) AND ID_ESTADO = :idEstado "
                        + "ORDER BY " + clausulaOrden(orden) + " FETCH FIRST :limite ROWS ONLY",
                parametros, Long.class);
    }

    /**
     * Condición {@code FECHA_REVISION >= :revisadasDesde} (solo si {@code
     * revisadasDesde != null}), agregando su parámetro a {@code
     * parametros}. Nunca enlaza un parámetro {@code null}.
     */
    private String condicionRevisadasDesde(MapSqlParameterSource parametros, OffsetDateTime revisadasDesde) {
        if (revisadasDesde == null) {
            return "";
        }
        parametros.addValue("revisadasDesde", revisadasDesde);
        return " AND FECHA_REVISION >= :revisadasDesde";
    }

    /**
     * Traduce {@code orden} a su cláusula SQL fija. {@code orden} es
     * siempre una de las constantes de esta clase (nunca un valor que venga
     * de afuera), así que esto nunca concatena texto externo al SQL.
     */
    private String clausulaOrden(String orden) {
        return switch (orden) {
            case ORDEN_DETECCION_DESCENDENTE -> "FECHA_DETECCION DESC, ID_ALERTA DESC";
            case ORDEN_PROGRAMADA_ASCENDENTE -> "FECHA_PROGRAMADA ASC, ID_ALERTA ASC";
            default -> throw new IllegalArgumentException("Orden de alertas desconocido: " + orden);
        };
    }

    /**
     * Condición común de atraso (regla 2), agregando sus parámetros a
     * {@code parametros}. {@code FECHA_HORARIO < :ahora} (M-2 de la revisión
     * final de la Fase 6) es redundante con la condición de tolerancia (el
     * CHECK {@code CK_TAREA_TOLERANCIA} obliga {@code MINUTOS_TOLERANCIA ≥
     * 0}, así que {@code FECHA_HORARIO + tolerancia < ahora} ya implica
     * {@code FECHA_HORARIO < ahora}); se agrega solo para acotar el recorrido
     * de {@code IX_TAREA_GRUPO_HORARIO} por {@code FECHA_HORARIO}, sin
     * cambiar el resultado.
     */
    private String condicionAtrasadas(MapSqlParameterSource parametros, long idGrupo, Long idAdulto,
            OffsetDateTime desde, OffsetDateTime ahora) {
        String condicionBase = condicionGrupoYAdulto(parametros, idGrupo, idAdulto);
        parametros.addValue("pendiente", estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE))
                .addValue("enCurso", estadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO))
                .addValue("desde", desde)
                .addValue("ahora", ahora);
        return condicionBase + " AND ID_ESTADO IN (:pendiente, :enCurso) AND FECHA_HORARIO >= :desde "
                + "AND FECHA_HORARIO < :ahora "
                + "AND FECHA_HORARIO + NUMTODSINTERVAL(MINUTOS_TOLERANCIA, 'MINUTE') < :ahora";
    }

    /**
     * Condición {@code ID_GRUPO = :idGrupo} (siempre) y {@code
     * ID_ADULTO_MAYOR = :idAdulto} (solo si {@code idAdulto != null}),
     * agregando sus parámetros a {@code parametros}. Nunca enlaza un
     * parámetro {@code null}.
     */
    private String condicionGrupoYAdulto(MapSqlParameterSource parametros, long idGrupo, Long idAdulto) {
        parametros.addValue("idGrupo", idGrupo);
        if (idAdulto == null) {
            return "ID_GRUPO = :idGrupo";
        }
        parametros.addValue("idAdulto", idAdulto);
        return "ID_GRUPO = :idGrupo AND ID_ADULTO_MAYOR = :idAdulto";
    }

    private long estadoTarea(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO, codigo);
    }
}
