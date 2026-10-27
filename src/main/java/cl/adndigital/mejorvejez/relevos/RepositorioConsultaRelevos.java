package cl.adndigital.mejorvejez.relevos;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Consultas de solo lectura, con {@link NamedParameterJdbcTemplate}, para
 * armar la foto de un relevo (regla 4 de la Fase 7): ids de las tareas y las
 * alertas pendientes del adulto, y el nombre del responsable vigente de cada
 * tarea (resuelto con una sola consulta por lote, sin N+1), con el mismo
 * estilo que {@code panel.RepositorioConsultaPanel}: los estados llegan ya
 * resueltos como parámetros, nunca como literales en el SQL, y los ids
 * encontrados se usan después para traer las entidades completas con
 * {@code RepositorioTareaCuidado#findAllById} / {@code
 * RepositorioAlertaCuidado#findAllById} (el texto del resumen lo arma {@code
 * ServicioRelevos}, no esta clase). {@link #conteosDeDetallePorTraspaso}
 * (ronda de corrección 1, minor 5) sirve al listado paginado, con el mismo
 * estilo de agregación JDBC que {@code panel.RepositorioConsultaPanel}.
 *
 * <p><b>Aislamiento (foco de revisión 5 de la Fase 7).</b> {@link
 * #idsTareasParaResumen} y {@link #idsAlertasParaResumen} filtran siempre
 * por {@code ID_GRUPO} e {@code ID_ADULTO_MAYOR}: así queda validada la
 * referencia polimórfica de {@code DETALLE_TRASPASO} contra el grupo y el
 * adulto del relevo, sin que la foto pueda arrastrar tareas o alertas de
 * otro adulto o de otro grupo.
 */
@Repository
class RepositorioConsultaRelevos {

    private final NamedParameterJdbcTemplate jdbc;

    RepositorioConsultaRelevos(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ids de las tareas {@code PENDIENTE} o {@code EN_CURSO} del adulto en
     * {@code [desde, hasta)}, las {@code limite} primeras por {@code
     * FECHA_HORARIO} y luego {@code ID_TAREA}.
     */
    List<Long> idsTareasParaResumen(long idGrupo, long idAdulto, OffsetDateTime desde, OffsetDateTime hasta,
            long idPendiente, long idEnCurso, int limite) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idGrupo", idGrupo)
                .addValue("idAdulto", idAdulto)
                .addValue("desde", desde)
                .addValue("hasta", hasta)
                .addValue("pendiente", idPendiente)
                .addValue("enCurso", idEnCurso)
                .addValue("limite", limite);
        return jdbc.queryForList(
                "SELECT ID_TAREA FROM TAREA_CUIDADO WHERE ID_GRUPO = :idGrupo AND ID_ADULTO_MAYOR = :idAdulto "
                        + "AND ID_ESTADO IN (:pendiente, :enCurso) "
                        + "AND FECHA_HORARIO >= :desde AND FECHA_HORARIO < :hasta "
                        + "ORDER BY FECHA_HORARIO, ID_TAREA FETCH FIRST :limite ROWS ONLY",
                parametros, Long.class);
    }

    /**
     * Ids de las alertas {@code ABIERTA} del adulto, de cualquier tipo, las
     * {@code limite} primeras por {@code FECHA_DETECCION} y luego {@code
     * ID_ALERTA}.
     */
    List<Long> idsAlertasParaResumen(long idGrupo, long idAdulto, long idAbierta, int limite) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idGrupo", idGrupo)
                .addValue("idAdulto", idAdulto)
                .addValue("idAbierta", idAbierta)
                .addValue("limite", limite);
        return jdbc.queryForList(
                "SELECT ID_ALERTA FROM ALERTA_CUIDADO WHERE ID_GRUPO = :idGrupo AND ID_ADULTO_MAYOR = :idAdulto "
                        + "AND ID_ESTADO = :idAbierta ORDER BY FECHA_DETECCION, ID_ALERTA "
                        + "FETCH FIRST :limite ROWS ONLY",
                parametros, Long.class);
    }

    /**
     * Nombre del responsable de la asignación {@code ACTIVO} de cada una de
     * {@code idsTarea} (las que no tienen asignación activa, o ninguna,
     * simplemente no aparecen en el mapa). Devuelve {@code Map.of()} sin
     * consultar si {@code idsTarea} viene vacío.
     */
    Map<Long, String> nombresResponsablesPorTarea(Collection<Long> idsTarea, long idAsignacionActiva) {
        if (idsTarea.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idsTarea", idsTarea)
                .addValue("idAsignacionActiva", idAsignacionActiva);
        List<Map<String, Object>> filas = jdbc.queryForList(
                "SELECT a.ID_TAREA, u.NOMBRE FROM ASIGNACION_TAREA a "
                        + "JOIN USUARIO u ON u.ID_USUARIO = a.ID_USUARIO_ASIGNADO "
                        + "WHERE a.ID_TAREA IN (:idsTarea) AND a.ID_ESTADO = :idAsignacionActiva",
                parametros);
        return filas.stream().collect(Collectors.toMap(fila -> ((Number) fila.get("ID_TAREA")).longValue(),
                fila -> (String) fila.get("NOMBRE")));
    }

    /**
     * Cuenta, por tipo de elemento ({@code TAREA} | {@code ALERTA}), los
     * {@code DetalleTraspaso} de cada uno de {@code idsTraspaso} (regla 1 de
     * la Fase 7, ronda de corrección 1, minor 5): evita cargar las filas
     * completas de {@code DETALLE_TRASPASO} (con {@code RESUMEN} de hasta
     * 1000 bytes cada una) solo para contarlas en el listado paginado.
     * Devuelve {@code Map.of()} sin consultar si {@code idsTraspaso} viene
     * vacío; un relevo sin ninguna fila para un tipo simplemente no aparece
     * para ese tipo en el mapa interno.
     */
    Map<Long, Map<String, Long>> conteosDeDetallePorTraspaso(Collection<Long> idsTraspaso) {
        if (idsTraspaso.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource parametros = new MapSqlParameterSource().addValue("idsTraspaso", idsTraspaso);
        List<Map<String, Object>> filas = jdbc.queryForList(
                "SELECT ID_TRASPASO, TIPO_ELEMENTO, COUNT(*) AS CANTIDAD FROM DETALLE_TRASPASO "
                        + "WHERE ID_TRASPASO IN (:idsTraspaso) GROUP BY ID_TRASPASO, TIPO_ELEMENTO",
                parametros);
        Map<Long, Map<String, Long>> resultado = new HashMap<>();
        for (Map<String, Object> fila : filas) {
            long idTraspaso = ((Number) fila.get("ID_TRASPASO")).longValue();
            String tipoElemento = (String) fila.get("TIPO_ELEMENTO");
            long cantidad = ((Number) fila.get("CANTIDAD")).longValue();
            resultado.computeIfAbsent(idTraspaso, ignorado -> new HashMap<>()).put(tipoElemento, cantidad);
        }
        return resultado;
    }
}
