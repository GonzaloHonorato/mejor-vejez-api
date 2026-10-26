package cl.adndigital.mejorvejez.alertas;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.grupos.RolGrupo;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;

/**
 * Consultas de solo lectura del módulo {@code alertas} que cruzan tablas de
 * otros módulos (tareas, grupos, usuarios) y devuelven solo ids o banderas,
 * sin cargar entidades, con el mismo estilo que {@code
 * tareas.RepositorioConsultaGeneracion}: los estados se resuelven con {@link
 * ServicioCatalogos} y van como parámetros enlazados, nunca como literales
 * en el SQL. Dentro de una transacción JPA usa la misma conexión.
 *
 * <p>Tiene las consultas de destinatarios (regla 7 de la Fase 5: un
 * destinatario es elegible si su {@code USUARIO} está ACTIVO y es integrante
 * ACTIVO del grupo de la alerta) y la de tareas candidatas a atraso (regla
 * 2, {@link #tareasAtrasadasCandidatas}).
 */
@Repository
class RepositorioConsultaAlertas {

    private static final String ACTIVO = "ACTIVO";

    private static final String INTEGRANTES_ELEGIBLES = "FROM INTEGRANTE_GRUPO i "
            + "JOIN USUARIO u ON u.ID_USUARIO = i.ID_USUARIO "
            + "WHERE i.ID_GRUPO = :idGrupo AND i.ID_ESTADO = :integranteActivo AND u.ID_ESTADO = :usuarioActivo";

    /**
     * Regla 2 de la Fase 5 (TSA-43): tarea PENDIENTE o EN_CURSO, con {@code
     * GENERA_ALERTA = 'S'}, de la retrospectiva ({@code FECHA_HORARIO ≥
     * :desde}), ya pasado su límite ({@code ahora > FECHA_HORARIO +
     * MINUTOS_TOLERANCIA}, la misma regla {@code atrasada} de F4), con su
     * adulto y su grupo ACTIVO y, si es de MEDICAMENTOS, también su detalle y
     * su plan ACTIVO.
     */
    private static final String TAREAS_ATRASADAS_CANDIDATAS = "SELECT t.ID_TAREA FROM TAREA_CUIDADO t "
            + "JOIN ADULTO_MAYOR a ON a.ID_ADULTO_MAYOR = t.ID_ADULTO_MAYOR "
            + "JOIN GRUPO_FAMILIAR g ON g.ID_GRUPO = t.ID_GRUPO "
            + "LEFT JOIN DETALLE_PLAN d ON d.ID_DETALLE_PLAN = t.ID_DETALLE_PLAN "
            + "LEFT JOIN PLAN_MEDICAMENTOS p ON p.ID_PLAN = d.ID_PLAN "
            + "WHERE t.ID_ESTADO IN (:tareaPendiente, :tareaEnCurso) "
            + "AND t.GENERA_ALERTA = 'S' "
            + "AND t.FECHA_HORARIO >= :desde "
            + "AND t.FECHA_HORARIO + NUMTODSINTERVAL(t.MINUTOS_TOLERANCIA, 'MINUTE') < :ahora "
            + "AND a.ID_ESTADO = :adultoActivo AND g.ID_ESTADO = :grupoActivo "
            + "AND (t.TIPO_TAREA <> :tipoMedicamentos "
            + "     OR (d.ID_ESTADO = :detalleActivo AND p.ID_ESTADO = :planActivo))";

    /**
     * Regla 3 de la Fase 5 (TSA-44): tarea {@code CITAS_CONTROLES_MEDICOS},
     * PENDIENTE o EN_CURSO, con {@code GENERA_ALERTA = 'S'}, con su adulto y
     * su grupo ACTIVO, y {@code ahora < FECHA_HORARIO ≤ :hasta} ({@code
     * hasta = ahora + 24 h}).
     */
    private static final String CITAS_PROXIMAS_CANDIDATAS = "SELECT t.ID_TAREA FROM TAREA_CUIDADO t "
            + "JOIN ADULTO_MAYOR a ON a.ID_ADULTO_MAYOR = t.ID_ADULTO_MAYOR "
            + "JOIN GRUPO_FAMILIAR g ON g.ID_GRUPO = t.ID_GRUPO "
            + "WHERE t.TIPO_TAREA = :tipoCitas "
            + "AND t.ID_ESTADO IN (:tareaPendiente, :tareaEnCurso) "
            + "AND t.GENERA_ALERTA = 'S' "
            + "AND t.FECHA_HORARIO > :ahora AND t.FECHA_HORARIO <= :hasta "
            + "AND a.ID_ESTADO = :adultoActivo AND g.ID_ESTADO = :grupoActivo";

    /**
     * Regla 4 de la Fase 5 (TSA-44): detalle, plan, adulto y grupo ACTIVO,
     * con {@code FECHA_ESTIMADA_TERMINO} no nulo y {@code hoy ∈ [término − 3
     * días, término]}, es decir {@code FECHA_ESTIMADA_TERMINO ∈ [:hoy,
     * :hoyMas3]}.
     */
    private static final String DETALLES_POR_REPONER = "SELECT d.ID_DETALLE_PLAN FROM DETALLE_PLAN d "
            + "JOIN PLAN_MEDICAMENTOS p ON p.ID_PLAN = d.ID_PLAN "
            + "JOIN ADULTO_MAYOR a ON a.ID_ADULTO_MAYOR = p.ID_ADULTO_MAYOR "
            + "JOIN GRUPO_FAMILIAR g ON g.ID_GRUPO = p.ID_GRUPO "
            + "WHERE d.ID_ESTADO = :detalleActivo AND p.ID_ESTADO = :planActivo "
            + "AND a.ID_ESTADO = :adultoActivo AND g.ID_ESTADO = :grupoActivo "
            + "AND d.FECHA_ESTIMADA_TERMINO IS NOT NULL "
            + "AND d.FECHA_ESTIMADA_TERMINO BETWEEN :hoy AND :hoyMas3";

    private final NamedParameterJdbcTemplate jdbc;
    private final ServicioCatalogos servicioCatalogos;

    RepositorioConsultaAlertas(NamedParameterJdbcTemplate jdbc, ServicioCatalogos servicioCatalogos) {
        this.jdbc = jdbc;
        this.servicioCatalogos = servicioCatalogos;
    }

    /**
     * Id del usuario con la asignación ACTIVO de la tarea (a lo más uno,
     * {@code UX_ASIGNACION_ACTIVA}), sin verificar si es elegible: eso lo
     * decide {@link #esIntegranteElegible}.
     */
    List<Long> responsableActivo(long idTarea) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idTarea", idTarea)
                .addValue("asignacionActiva", servicioCatalogos.idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA,
                        CodigosTareas.CAMPO_ESTADO, CodigosTareas.ESTADO_ASIGNACION_ACTIVO));
        return jdbc.queryForList(
                "SELECT ID_USUARIO_ASIGNADO FROM ASIGNACION_TAREA "
                        + "WHERE ID_TAREA = :idTarea AND ID_ESTADO = :asignacionActiva ORDER BY ID_USUARIO_ASIGNADO",
                parametros, Long.class);
    }

    /** Ids de los coordinadores elegibles del grupo (usuario e integrante ACTIVO), por id ascendente. */
    List<Long> coordinadoresActivos(long idGrupo) {
        MapSqlParameterSource parametros = parametrosElegibles(idGrupo).addValue("rol", RolGrupo.COORDINADOR);
        return jdbc.queryForList(
                "SELECT i.ID_USUARIO " + INTEGRANTES_ELEGIBLES
                        + " AND i.ID_ROL IN (SELECT r.ID_ROL FROM ROL_GRUPO r WHERE r.NOMBRE_ROL = :rol)"
                        + " ORDER BY i.ID_USUARIO",
                parametros, Long.class);
    }

    /** Si {@code idUsuario} es un destinatario elegible del grupo (usuario e integrante ACTIVO). */
    boolean esIntegranteElegible(long idGrupo, long idUsuario) {
        MapSqlParameterSource parametros = parametrosElegibles(idGrupo).addValue("idUsuario", idUsuario);
        Long cantidad = jdbc.queryForObject(
                "SELECT COUNT(*) " + INTEGRANTES_ELEGIBLES + " AND i.ID_USUARIO = :idUsuario", parametros, Long.class);
        return cantidad != null && cantidad > 0;
    }

    /** Si el grupo pide notificar a sus coordinadores ({@code GRUPO_FAMILIAR.NOTIFICA_COORDINADOR = 'S'}). */
    boolean notificaCoordinador(long idGrupo) {
        List<String> valores = jdbc.queryForList(
                "SELECT NOTIFICA_COORDINADOR FROM GRUPO_FAMILIAR WHERE ID_GRUPO = :idGrupo",
                new MapSqlParameterSource("idGrupo", idGrupo), String.class);
        return !valores.isEmpty() && "S".equals(valores.get(0));
    }

    /**
     * Ids de las tareas candidatas a una alerta de atraso (regla 2), por id
     * ascendente. {@code idGrupo} es opcional: {@code null} recorre todos los
     * grupos (la corrida real); las pruebas siempre lo pasan (BD compartida).
     * Es solo la preselección: {@code CreadorAlertas#crearAtraso} vuelve a
     * verificar todo con la tarea bloqueada (regla 5).
     */
    List<Long> tareasAtrasadasCandidatas(OffsetDateTime desde, OffsetDateTime ahora, Long idGrupo) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("tareaPendiente", estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE))
                .addValue("tareaEnCurso", estadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO))
                .addValue("adultoActivo", servicioCatalogos.idEstado("ADULTO_MAYOR", "ESTADO", ACTIVO))
                .addValue("grupoActivo", servicioCatalogos.idEstado("GRUPO_FAMILIAR", "ESTADO", ACTIVO))
                .addValue("detalleActivo", servicioCatalogos.idEstado("DETALLE_PLAN", "ESTADO", ACTIVO))
                .addValue("planActivo", servicioCatalogos.idEstado("PLAN_MEDICAMENTOS", "ESTADO", ACTIVO))
                .addValue("tipoMedicamentos", CodigosTareas.TIPO_TAREA_MEDICAMENTOS)
                .addValue("desde", desde)
                .addValue("ahora", ahora);
        String sql = TAREAS_ATRASADAS_CANDIDATAS;
        if (idGrupo != null) {
            sql += " AND t.ID_GRUPO = :idGrupo";
            parametros.addValue("idGrupo", idGrupo);
        }
        return jdbc.queryForList(sql + " ORDER BY t.ID_TAREA", parametros, Long.class);
    }

    /**
     * Ids de las tareas candidatas a una alerta de cita próxima (regla 3),
     * por id ascendente. {@code idGrupo} es opcional, igual que {@link
     * #tareasAtrasadasCandidatas}. Es solo la preselección: {@code
     * CreadorAlertas#crearCitaProxima} vuelve a verificar todo con la tarea
     * bloqueada (regla 5).
     */
    List<Long> citasProximasCandidatas(OffsetDateTime ahora, OffsetDateTime hasta, Long idGrupo) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("tipoCitas", CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS)
                .addValue("tareaPendiente", estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE))
                .addValue("tareaEnCurso", estadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO))
                .addValue("adultoActivo", servicioCatalogos.idEstado("ADULTO_MAYOR", "ESTADO", ACTIVO))
                .addValue("grupoActivo", servicioCatalogos.idEstado("GRUPO_FAMILIAR", "ESTADO", ACTIVO))
                .addValue("ahora", ahora)
                .addValue("hasta", hasta);
        String sql = CITAS_PROXIMAS_CANDIDATAS;
        if (idGrupo != null) {
            sql += " AND t.ID_GRUPO = :idGrupo";
            parametros.addValue("idGrupo", idGrupo);
        }
        return jdbc.queryForList(sql + " ORDER BY t.ID_TAREA", parametros, Long.class);
    }

    /**
     * Ids de los detalles de plan candidatos a una alerta de reposición
     * (regla 4), por id ascendente. {@code idGrupo} es opcional, igual que
     * {@link #tareasAtrasadasCandidatas}. Es solo la preselección: {@code
     * CreadorAlertas#crearReposicion} vuelve a verificar todo con el plan
     * bloqueado (regla 5).
     */
    List<Long> detallesPorReponer(LocalDate hoy, Long idGrupo) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("detalleActivo", servicioCatalogos.idEstado("DETALLE_PLAN", "ESTADO", ACTIVO))
                .addValue("planActivo", servicioCatalogos.idEstado("PLAN_MEDICAMENTOS", "ESTADO", ACTIVO))
                .addValue("adultoActivo", servicioCatalogos.idEstado("ADULTO_MAYOR", "ESTADO", ACTIVO))
                .addValue("grupoActivo", servicioCatalogos.idEstado("GRUPO_FAMILIAR", "ESTADO", ACTIVO))
                .addValue("hoy", hoy)
                .addValue("hoyMas3", hoy.plusDays(3));
        String sql = DETALLES_POR_REPONER;
        if (idGrupo != null) {
            sql += " AND p.ID_GRUPO = :idGrupo";
            parametros.addValue("idGrupo", idGrupo);
        }
        return jdbc.queryForList(sql + " ORDER BY d.ID_DETALLE_PLAN", parametros, Long.class);
    }

    /**
     * Id del plan del detalle indicado, por JDBC (sin cargar la entidad
     * JPA): se usa para bloquear el plan ANTES de la primera carga JPA del
     * detalle (regla 5), igual que {@code
     * tareas.RepositorioConsultaGeneracion#planDeOcurrencia} +
     * {@code tareas.RegistroEjecuciones#marcarError}. Cargar el detalle con
     * {@code RepositorioDetallePlan#findById} antes de bloquear el plan
     * dejaría la entidad cacheada en el contexto de persistencia: una
     * segunda lectura por id, ya con el plan bloqueado, no volvería a
     * consultar la BD y vería la foto de antes del bloqueo.
     */
    Optional<Long> planDeDetalle(long idDetalle) {
        List<Long> ids = jdbc.queryForList("SELECT ID_PLAN FROM DETALLE_PLAN WHERE ID_DETALLE_PLAN = :idDetalle",
                new MapSqlParameterSource("idDetalle", idDetalle), Long.class);
        return ids.stream().findFirst();
    }

    private long estadoTarea(String codigo) {
        return servicioCatalogos.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.CAMPO_ESTADO, codigo);
    }

    private MapSqlParameterSource parametrosElegibles(long idGrupo) {
        return new MapSqlParameterSource()
                .addValue("idGrupo", idGrupo)
                .addValue("integranteActivo", servicioCatalogos.idEstado("INTEGRANTE_GRUPO", "ESTADO", ACTIVO))
                .addValue("usuarioActivo", servicioCatalogos.idEstado("USUARIO", "ESTADO", ACTIVO));
    }
}
