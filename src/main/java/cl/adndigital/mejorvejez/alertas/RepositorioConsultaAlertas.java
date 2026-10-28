package cl.adndigital.mejorvejez.alertas;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
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
 * ACTIVO del grupo de la alerta) y las candidatas que consume el
 * microservicio de Alertas (Fase 9).
 *
 * <p><b>Fase 9 (TSA-61).</b> Pública: {@code
 * cl.adndigital.mejorvejez.interno.ServicioInternoAlertas} la reutiliza para
 * las rutas {@code /api/interno/alertas/candidatos-*} que consume el
 * microservicio de Alertas. {@link #candidatosAtraso}, {@link
 * #candidatosCitaProxima} y {@link #candidatosReposicion} aplican el estado
 * PENDIENTE/EN_CURSO, {@code GENERA_ALERTA = 'S'} y adulto/grupo/plan/detalle
 * ACTIVO. Lo único que {@link #candidatosAtraso} NO aplica es la tolerancia
 * ({@code ahora > FECHA_HORARIO + MINUTOS_TOLERANCIA}): esa es la única
 * decisión que queda para el servicio de Alertas. Los tres también excluyen
 * el origen que ya tiene su alerta (misma clave de idempotencia que {@link
 * CreadorAlertas}, regla 1 de la Fase 5), para no proteger la unicidad dos
 * veces.
 */
@Repository
public class RepositorioConsultaAlertas {

    private static final String ACTIVO = "ACTIVO";

    private static final String INTEGRANTES_ELEGIBLES = "FROM INTEGRANTE_GRUPO i "
            + "JOIN USUARIO u ON u.ID_USUARIO = i.ID_USUARIO "
            + "WHERE i.ID_GRUPO = :idGrupo AND i.ID_ESTADO = :integranteActivo AND u.ID_ESTADO = :usuarioActivo";

    /**
     * Fase 9 (TSA-61): tarea PENDIENTE o EN_CURSO, con {@code GENERA_ALERTA
     * = 'S'}, con {@code FECHA_HORARIO} en la ventana dada, de adulto, grupo
     * y (si es de MEDICAMENTOS) detalle y plan ACTIVO, sin una alerta {@code
     * ATR-} vigente. NO filtra por tolerancia ({@code ahora > FECHA_HORARIO
     * + MINUTOS_TOLERANCIA}): esa es la única decisión que le queda al
     * servicio de Alertas (que de todos modos recibe {@code codigoEstado} y
     * {@code generaAlerta} en la respuesta, crudos, por contrato).
     */
    private static final String CANDIDATOS_ATRASO = "SELECT t.ID_TAREA, t.ID_GRUPO, t.ID_ADULTO_MAYOR, "
            + "t.FECHA_HORARIO, t.MINUTOS_TOLERANCIA, ce.CODIGO_ESTADO, t.GENERA_ALERTA FROM TAREA_CUIDADO t "
            + "JOIN ADULTO_MAYOR a ON a.ID_ADULTO_MAYOR = t.ID_ADULTO_MAYOR "
            + "JOIN GRUPO_FAMILIAR g ON g.ID_GRUPO = t.ID_GRUPO "
            + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = t.ID_ESTADO "
            + "LEFT JOIN DETALLE_PLAN d ON d.ID_DETALLE_PLAN = t.ID_DETALLE_PLAN "
            + "LEFT JOIN PLAN_MEDICAMENTOS p ON p.ID_PLAN = d.ID_PLAN "
            + "WHERE t.ID_ESTADO IN (:tareaPendiente, :tareaEnCurso) "
            + "AND t.GENERA_ALERTA = 'S' "
            + "AND t.FECHA_HORARIO BETWEEN :desde AND :hasta "
            + "AND a.ID_ESTADO = :adultoActivo AND g.ID_ESTADO = :grupoActivo "
            + "AND (t.TIPO_TAREA <> :tipoMedicamentos "
            + "     OR (d.ID_ESTADO = :detalleActivo AND p.ID_ESTADO = :planActivo)) "
            + "AND NOT EXISTS (SELECT 1 FROM ALERTA_CUIDADO al "
            + "                WHERE al.CLAVE_ALERTA = 'ATR-' || TO_CHAR(t.ID_TAREA))";

    /**
     * Fase 9 (TSA-61): tarea {@code CITAS_CONTROLES_MEDICOS}, PENDIENTE o
     * EN_CURSO, con su adulto y su grupo ACTIVO, con {@code FECHA_HORARIO}
     * en la ventana dada (parametrizable, en vez de 24 h fijas) y
     * excluyendo las que ya tienen una alerta {@code CIT-} vigente (el
     * servicio de Alertas ya no vuelve a chequearlo).
     */
    private static final String CANDIDATOS_CITA_PROXIMA = "SELECT t.ID_TAREA, t.FECHA_HORARIO "
            + "FROM TAREA_CUIDADO t "
            + "JOIN ADULTO_MAYOR a ON a.ID_ADULTO_MAYOR = t.ID_ADULTO_MAYOR "
            + "JOIN GRUPO_FAMILIAR g ON g.ID_GRUPO = t.ID_GRUPO "
            + "WHERE t.TIPO_TAREA = :tipoCitas "
            + "AND t.ID_ESTADO IN (:tareaPendiente, :tareaEnCurso) "
            + "AND t.FECHA_HORARIO BETWEEN :ahora AND :hasta "
            + "AND a.ID_ESTADO = :adultoActivo AND g.ID_ESTADO = :grupoActivo "
            + "AND NOT EXISTS (SELECT 1 FROM ALERTA_CUIDADO al "
            + "                WHERE al.CLAVE_ALERTA = 'CIT-' || TO_CHAR(t.ID_TAREA))";

    /**
     * Fase 9 (TSA-61): detalle, plan, adulto y grupo ACTIVO, con {@code
     * FECHA_ESTIMADA_TERMINO} no nulo y en la ventana dada (parametrizable,
     * en vez de "hoy + 3 días" fijo), excluyendo los que ya tienen una
     * alerta {@code REP-} vigente para su término actual (misma clave que
     * {@link CreadorAlertas#claveReposicion}).
     */
    private static final String CANDIDATOS_REPOSICION = "SELECT d.ID_DETALLE_PLAN, d.FECHA_ESTIMADA_TERMINO "
            + "FROM DETALLE_PLAN d "
            + "JOIN PLAN_MEDICAMENTOS p ON p.ID_PLAN = d.ID_PLAN "
            + "JOIN ADULTO_MAYOR a ON a.ID_ADULTO_MAYOR = p.ID_ADULTO_MAYOR "
            + "JOIN GRUPO_FAMILIAR g ON g.ID_GRUPO = p.ID_GRUPO "
            + "WHERE d.ID_ESTADO = :detalleActivo AND p.ID_ESTADO = :planActivo "
            + "AND a.ID_ESTADO = :adultoActivo AND g.ID_ESTADO = :grupoActivo "
            + "AND d.FECHA_ESTIMADA_TERMINO IS NOT NULL "
            + "AND d.FECHA_ESTIMADA_TERMINO BETWEEN :hoy AND :hasta "
            + "AND NOT EXISTS (SELECT 1 FROM ALERTA_CUIDADO al "
            + "                WHERE al.CLAVE_ALERTA = 'REP-' || TO_CHAR(d.ID_DETALLE_PLAN) || '-' "
            + "                      || TO_CHAR(d.FECHA_ESTIMADA_TERMINO, 'YYYYMMDD'))";

    private static final RowMapper<FilaCandidatoAtraso> MAPEADOR_CANDIDATO_ATRASO = (fila, numeroFila) ->
            new FilaCandidatoAtraso(fila.getLong("ID_TAREA"), fila.getLong("ID_GRUPO"),
                    fila.getLong("ID_ADULTO_MAYOR"), fila.getObject("FECHA_HORARIO", OffsetDateTime.class),
                    fila.getInt("MINUTOS_TOLERANCIA"), fila.getString("CODIGO_ESTADO"),
                    "S".equals(fila.getString("GENERA_ALERTA")));

    private static final RowMapper<FilaCandidatoCita> MAPEADOR_CANDIDATO_CITA = (fila, numeroFila) ->
            new FilaCandidatoCita(fila.getLong("ID_TAREA"), fila.getObject("FECHA_HORARIO", OffsetDateTime.class));

    private static final RowMapper<FilaCandidatoReposicion> MAPEADOR_CANDIDATO_REPOSICION = (fila, numeroFila) ->
            new FilaCandidatoReposicion(fila.getLong("ID_DETALLE_PLAN"),
                    fila.getObject("FECHA_ESTIMADA_TERMINO", LocalDate.class));

    private final NamedParameterJdbcTemplate jdbc;
    private final ServicioCatalogos servicioCatalogos;

    RepositorioConsultaAlertas(NamedParameterJdbcTemplate jdbc, ServicioCatalogos servicioCatalogos) {
        this.jdbc = jdbc;
        this.servicioCatalogos = servicioCatalogos;
    }

    /**
     * Id del usuario con la asignación ACTIVO de la tarea (a lo más uno,
     * {@code UX_ASIGNACION_ACTIVA}), sin verificar si es elegible: eso lo
     * decide {@link #esIntegranteElegible}. Pública: {@code
     * interno.ServicioReservaIntentos} (Fase 9, TSA-61) la reutiliza para
     * calcular los destinatarios al reservar los intentos de una alerta.
     */
    public List<Long> responsableActivo(long idTarea) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idTarea", idTarea)
                .addValue("asignacionActiva", servicioCatalogos.idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA,
                        CodigosTareas.CAMPO_ESTADO, CodigosTareas.ESTADO_ASIGNACION_ACTIVO));
        return jdbc.queryForList(
                "SELECT ID_USUARIO_ASIGNADO FROM ASIGNACION_TAREA "
                        + "WHERE ID_TAREA = :idTarea AND ID_ESTADO = :asignacionActiva ORDER BY ID_USUARIO_ASIGNADO",
                parametros, Long.class);
    }

    /**
     * Ids de los coordinadores elegibles del grupo (usuario e integrante
     * ACTIVO), por id ascendente. Pública por el mismo motivo que {@link
     * #responsableActivo}.
     */
    public List<Long> coordinadoresActivos(long idGrupo) {
        MapSqlParameterSource parametros = parametrosElegibles(idGrupo).addValue("rol", RolGrupo.COORDINADOR);
        return jdbc.queryForList(
                "SELECT i.ID_USUARIO " + INTEGRANTES_ELEGIBLES
                        + " AND i.ID_ROL IN (SELECT r.ID_ROL FROM ROL_GRUPO r WHERE r.NOMBRE_ROL = :rol)"
                        + " ORDER BY i.ID_USUARIO",
                parametros, Long.class);
    }

    /**
     * Si {@code idUsuario} es un destinatario elegible del grupo (usuario e
     * integrante ACTIVO). Pública por el mismo motivo que {@link
     * #responsableActivo}.
     */
    public boolean esIntegranteElegible(long idGrupo, long idUsuario) {
        MapSqlParameterSource parametros = parametrosElegibles(idGrupo).addValue("idUsuario", idUsuario);
        Long cantidad = jdbc.queryForObject(
                "SELECT COUNT(*) " + INTEGRANTES_ELEGIBLES + " AND i.ID_USUARIO = :idUsuario", parametros, Long.class);
        return cantidad != null && cantidad > 0;
    }

    /**
     * Si el grupo pide notificar a sus coordinadores ({@code
     * GRUPO_FAMILIAR.NOTIFICA_COORDINADOR = 'S'}). Pública por el mismo
     * motivo que {@link #responsableActivo}.
     */
    public boolean notificaCoordinador(long idGrupo) {
        List<String> valores = jdbc.queryForList(
                "SELECT NOTIFICA_COORDINADOR FROM GRUPO_FAMILIAR WHERE ID_GRUPO = :idGrupo",
                new MapSqlParameterSource("idGrupo", idGrupo), String.class);
        return !valores.isEmpty() && "S".equals(valores.get(0));
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

    /**
     * Fase 9 (TSA-61): candidatas a una alerta de atraso en {@code [desde,
     * hasta]}, SIN aplicar la tolerancia (ver {@link #CANDIDATOS_ATRASO}),
     * por id ascendente. La reutiliza {@code
     * cl.adndigital.mejorvejez.interno.ServicioInternoAlertas} para {@code
     * GET /api/interno/alertas/candidatos-atraso}.
     */
    public List<FilaCandidatoAtraso> candidatosAtraso(OffsetDateTime desde, OffsetDateTime hasta) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("tareaPendiente", estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE))
                .addValue("tareaEnCurso", estadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO))
                .addValue("adultoActivo", servicioCatalogos.idEstado("ADULTO_MAYOR", "ESTADO", ACTIVO))
                .addValue("grupoActivo", servicioCatalogos.idEstado("GRUPO_FAMILIAR", "ESTADO", ACTIVO))
                .addValue("detalleActivo", servicioCatalogos.idEstado("DETALLE_PLAN", "ESTADO", ACTIVO))
                .addValue("planActivo", servicioCatalogos.idEstado("PLAN_MEDICAMENTOS", "ESTADO", ACTIVO))
                .addValue("tipoMedicamentos", CodigosTareas.TIPO_TAREA_MEDICAMENTOS)
                .addValue("desde", desde)
                .addValue("hasta", hasta);
        return jdbc.query(CANDIDATOS_ATRASO + " ORDER BY t.ID_TAREA", parametros, MAPEADOR_CANDIDATO_ATRASO);
    }

    /**
     * Fase 9 (TSA-61): candidatas a una alerta de cita próxima en {@code
     * [ahora, hasta]}, sin alerta {@code CIT-} vigente (ver {@link
     * #CANDIDATOS_CITA_PROXIMA}), por id ascendente. La reutiliza {@code
     * cl.adndigital.mejorvejez.interno.ServicioInternoAlertas} para {@code
     * GET /api/interno/alertas/candidatos-preventivos}.
     */
    public List<FilaCandidatoCita> candidatosCitaProxima(OffsetDateTime ahora, OffsetDateTime hasta) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("tipoCitas", CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS)
                .addValue("tareaPendiente", estadoTarea(CodigosTareas.ESTADO_TAREA_PENDIENTE))
                .addValue("tareaEnCurso", estadoTarea(CodigosTareas.ESTADO_TAREA_EN_CURSO))
                .addValue("adultoActivo", servicioCatalogos.idEstado("ADULTO_MAYOR", "ESTADO", ACTIVO))
                .addValue("grupoActivo", servicioCatalogos.idEstado("GRUPO_FAMILIAR", "ESTADO", ACTIVO))
                .addValue("ahora", ahora)
                .addValue("hasta", hasta);
        return jdbc.query(CANDIDATOS_CITA_PROXIMA + " ORDER BY t.ID_TAREA", parametros, MAPEADOR_CANDIDATO_CITA);
    }

    /**
     * Fase 9 (TSA-61): detalles candidatos a una alerta de reposición con
     * término en {@code [hoy, hasta]}, sin alerta {@code REP-} vigente para
     * ese término (ver {@link #CANDIDATOS_REPOSICION}), por id ascendente.
     * La reutiliza {@code
     * cl.adndigital.mejorvejez.interno.ServicioInternoAlertas} para {@code
     * GET /api/interno/alertas/candidatos-preventivos}.
     */
    public List<FilaCandidatoReposicion> candidatosReposicion(LocalDate hoy, LocalDate hasta) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("detalleActivo", servicioCatalogos.idEstado("DETALLE_PLAN", "ESTADO", ACTIVO))
                .addValue("planActivo", servicioCatalogos.idEstado("PLAN_MEDICAMENTOS", "ESTADO", ACTIVO))
                .addValue("adultoActivo", servicioCatalogos.idEstado("ADULTO_MAYOR", "ESTADO", ACTIVO))
                .addValue("grupoActivo", servicioCatalogos.idEstado("GRUPO_FAMILIAR", "ESTADO", ACTIVO))
                .addValue("hoy", hoy)
                .addValue("hasta", hasta);
        return jdbc.query(CANDIDATOS_REPOSICION + " ORDER BY d.ID_DETALLE_PLAN", parametros,
                MAPEADOR_CANDIDATO_REPOSICION);
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
