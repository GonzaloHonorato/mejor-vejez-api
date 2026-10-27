package cl.adndigital.mejorvejez.sucesion;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;

/**
 * Consulta de solo lectura, con {@link NamedParameterJdbcTemplate}, de las
 * condiciones pendientes de una sucesión (regla 19 de la Fase 7), con el
 * mismo estilo que {@code relevos.RepositorioConsultaRelevos}: los estados se
 * resuelven con {@link ServicioCatalogos} y llegan como parámetros, nunca
 * como literales en el SQL; los ids encontrados se usan después para traer
 * las tareas con {@code RepositorioTareaCuidado#findAllById} y ensamblarlas
 * con {@code EnsambladorRespuestasTarea} (sin N+1).
 */
@Repository
class RepositorioConsultaSucesion {

    private final NamedParameterJdbcTemplate jdbc;
    private final ServicioCatalogos servicioCatalogos;

    RepositorioConsultaSucesion(NamedParameterJdbcTemplate jdbc, ServicioCatalogos servicioCatalogos) {
        this.jdbc = jdbc;
        this.servicioCatalogos = servicioCatalogos;
    }

    /**
     * Ids de las tareas del grupo en estado {@code PENDIENTE} o {@code
     * EN_CURSO} cuya asignación {@code ACTIVO} es de {@code
     * idUsuarioSaliente}, ordenadas por {@code FECHA_HORARIO} y luego {@code
     * ID_TAREA}. Las resueltas (completadas, no realizadas o canceladas) y las
     * asignadas a otra persona no cuentan; {@code UX_ASIGNACION_ACTIVA}
     * garantiza una sola asignación activa por tarea, así que no hay filas
     * repetidas.
     */
    List<Long> idsCondicionesPendientes(long idGrupo, long idUsuarioSaliente) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idGrupo", idGrupo)
                .addValue("idSaliente", idUsuarioSaliente)
                .addValue("pendiente", idEstadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE))
                .addValue("enCurso", idEstadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO))
                .addValue("asignacionActiva", servicioCatalogos.idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA,
                        CodigosTareas.CAMPO_ESTADO, CodigosTareas.ESTADO_ASIGNACION_ACTIVO));
        return jdbc.queryForList(
                "SELECT t.ID_TAREA FROM TAREA_CUIDADO t "
                        + "JOIN ASIGNACION_TAREA a ON a.ID_TAREA = t.ID_TAREA AND a.ID_ESTADO = :asignacionActiva "
                        + "WHERE t.ID_GRUPO = :idGrupo AND a.ID_GRUPO = :idGrupo "
                        + "AND a.ID_USUARIO_ASIGNADO = :idSaliente AND t.ID_ESTADO IN (:pendiente, :enCurso) "
                        + "ORDER BY t.FECHA_HORARIO, t.ID_TAREA",
                parametros, Long.class);
    }

    private long idEstadoTarea(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO, codigo);
    }
}
