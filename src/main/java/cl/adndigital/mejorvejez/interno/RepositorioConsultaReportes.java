package cl.adndigital.mejorvejez.interno;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;

/**
 * Consultas de solo lectura del módulo {@code interno} para el contrato
 * "Core → lo consume Reportes" (Fase 9, TSA-61), con {@link
 * NamedParameterJdbcTemplate}, mismo estilo que {@code
 * alertas.RepositorioConsultaAlertas} / {@code
 * relevos.RepositorioConsultaRelevos}: los estados se resuelven con {@link
 * ServicioCatalogos} y van como parámetros enlazados, nunca como literales
 * en el SQL; cuando el estado ya viene como texto en la tabla (p. ej. {@code
 * ALERTA_CUIDADO.TIPO_ALERTA}) o se resuelve con un JOIN a {@code
 * CATALOGO_ESTADOS} (p. ej. el código de {@code HISTORIAL_ESTADO_TAREA}),
 * se deja así.
 *
 * <p>Nunca carga entidades JPA de otros módulos (tareas, relevos, alertas):
 * todo lo que necesita {@link ServicioInternoReportes} para reconstruir
 * {@link DatosSemana} sale de acá, en filas crudas que el servicio ensambla
 * con los nombres de persona (que sí resuelve con los repositorios JPA de
 * {@code usuarios}/{@code adultos}).
 */
@Repository
public class RepositorioConsultaReportes {

    private static final String ACTIVO = "ACTIVO";

    private static final RowMapper<FilaGrupo> MAPEADOR_GRUPO =
            (fila, numeroFila) -> new FilaGrupo(fila.getLong("ID_GRUPO"), fila.getString("NOMBRE_GRUPO"));

    private static final RowMapper<Destinatario> MAPEADOR_DESTINATARIO = (fila, numeroFila) ->
            new Destinatario(fila.getLong("ID_USUARIO"), fila.getString("NOMBRE"), fila.getString("EMAIL"));

    private static final RowMapper<FilaTarea> MAPEADOR_TAREA = (fila, numeroFila) -> new FilaTarea(
            fila.getLong("ID_TAREA"), fila.getLong("ID_ADULTO_MAYOR"), fila.getString("TIPO_TAREA"),
            fila.getString("TITULO_TAREA"), fila.getObject("FECHA_HORARIO", OffsetDateTime.class),
            fila.getInt("MINUTOS_TOLERANCIA"));

    private static final RowMapper<FilaAdulto> MAPEADOR_ADULTO =
            (fila, numeroFila) -> new FilaAdulto(fila.getLong("ID_ADULTO_MAYOR"), fila.getString("NOMBRE_COMPLETO"));

    private static final RowMapper<FilaHistorialAlCierre> MAPEADOR_HISTORIAL = (fila, numeroFila) ->
            new FilaHistorialAlCierre(fila.getLong("ID_TAREA"), fila.getString("CODIGO_ESTADO"),
                    fila.getLong("ID_USUARIO_CAMBIO"), fila.getObject("FECHA_CAMBIO", OffsetDateTime.class));

    private static final RowMapper<FilaAsignacion> MAPEADOR_ASIGNACION = (fila, numeroFila) -> new FilaAsignacion(
            fila.getLong("ID_TAREA"), fila.getLong("ID_USUARIO_ASIGNADO"),
            fila.getObject("FECHA_ASIGNACION", OffsetDateTime.class),
            fila.getObject("FECHA_TERMINO", OffsetDateTime.class));

    private static final RowMapper<FilaRelevo> MAPEADOR_RELEVO = (fila, numeroFila) -> new FilaRelevo(
            fila.getLong("ID_TRASPASO"), fila.getLong("ID_ADULTO_MAYOR"),
            fila.getObject("FECHA_TRASPASO", OffsetDateTime.class), fila.getLong("ID_USUARIO_SALIENTE"),
            fila.getLong("ID_USUARIO_ENTRANTE"), fila.getString("CODIGO_ESTADO"));

    private static final RowMapper<FilaAlerta> MAPEADOR_ALERTA = (fila, numeroFila) -> new FilaAlerta(
            fila.getLong("ID_ALERTA"), fila.getLong("ID_ADULTO_MAYOR"), fila.getString("TIPO_ALERTA"),
            fila.getObject("FECHA_DETECCION", OffsetDateTime.class),
            fila.getObject("FECHA_REVISION", OffsetDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;
    private final ServicioCatalogos servicioCatalogos;

    RepositorioConsultaReportes(NamedParameterJdbcTemplate jdbc, ServicioCatalogos servicioCatalogos) {
        this.jdbc = jdbc;
        this.servicioCatalogos = servicioCatalogos;
    }

    /** Los grupos {@code ACTIVO}, por id ascendente ({@code GET /api/interno/reportes/grupos}). */
    List<FilaGrupo> gruposActivos() {
        MapSqlParameterSource parametros = new MapSqlParameterSource("activo",
                servicioCatalogos.idEstado("GRUPO_FAMILIAR", "ESTADO", ACTIVO));
        return jdbc.query("SELECT ID_GRUPO, NOMBRE_GRUPO FROM GRUPO_FAMILIAR WHERE ID_ESTADO = :activo "
                + "ORDER BY ID_GRUPO", parametros, MAPEADOR_GRUPO);
    }

    /**
     * Destinatarios vigentes del grupo (integrante {@code ACTIVO} con cuenta
     * {@code ACTIVO}; el correo es siempre el de la cuenta, {@code
     * USUARIO.EMAIL}), por id de usuario ascendente. La usan tanto {@code
     * GET /api/interno/reportes/grupos} como la foto que crea {@code POST
     * /api/interno/reportes/envios}.
     */
    List<Destinatario> destinatariosActivos(long idGrupo) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idGrupo", idGrupo)
                .addValue("integranteActivo", servicioCatalogos.idEstado("INTEGRANTE_GRUPO", "ESTADO", ACTIVO))
                .addValue("usuarioActivo", servicioCatalogos.idEstado("USUARIO", "ESTADO", ACTIVO));
        return jdbc.query(
                "SELECT u.ID_USUARIO, u.NOMBRE, u.EMAIL FROM INTEGRANTE_GRUPO i "
                        + "JOIN USUARIO u ON u.ID_USUARIO = i.ID_USUARIO "
                        + "WHERE i.ID_GRUPO = :idGrupo AND i.ID_ESTADO = :integranteActivo "
                        + "AND u.ID_ESTADO = :usuarioActivo ORDER BY u.ID_USUARIO",
                parametros, MAPEADOR_DESTINATARIO);
    }

    /**
     * Las tareas del grupo con {@code FECHA_HORARIO} en {@code [inicio,
     * cierre)}, de todos los adultos (sin filtrar por estado), por horario y
     * luego por id.
     */
    List<FilaTarea> tareasDelPeriodo(long idGrupo, OffsetDateTime inicio, OffsetDateTime cierre) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idGrupo", idGrupo)
                .addValue("inicio", inicio)
                .addValue("cierre", cierre);
        return jdbc.query(
                "SELECT ID_TAREA, ID_ADULTO_MAYOR, TIPO_TAREA, TITULO_TAREA, FECHA_HORARIO, MINUTOS_TOLERANCIA "
                        + "FROM TAREA_CUIDADO WHERE ID_GRUPO = :idGrupo "
                        + "AND FECHA_HORARIO >= :inicio AND FECHA_HORARIO < :cierre ORDER BY FECHA_HORARIO, ID_TAREA",
                parametros, MAPEADOR_TAREA);
    }

    /** El nombre de cada uno de {@code idsAdulto}. Devuelve {@code Map.of()} sin consultar si viene vacío. */
    Map<Long, String> nombresAdultos(Collection<Long> idsAdulto) {
        if (idsAdulto.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource parametros = new MapSqlParameterSource("idsAdulto", idsAdulto);
        List<FilaAdulto> filas = jdbc.query(
                "SELECT ID_ADULTO_MAYOR, NOMBRE_COMPLETO FROM ADULTO_MAYOR WHERE ID_ADULTO_MAYOR IN (:idsAdulto)",
                parametros, MAPEADOR_ADULTO);
        Map<Long, String> resultado = new HashMap<>();
        filas.forEach(f -> resultado.put(f.idAdulto(), f.nombre()));
        return resultado;
    }

    /**
     * La última entrada de {@code HISTORIAL_ESTADO_TAREA} de cada una de
     * {@code idsTarea} con {@code FECHA_CAMBIO < cierre} (empate por {@code
     * ID_HISTORIAL} descendente), con el código de su {@code
     * ID_ESTADO_NUEVO} ya resuelto. Una tarea sin ninguna entrada antes del
     * cierre simplemente no aparece (PENDIENTE, regla del contrato).
     * Devuelve lista vacía sin consultar si {@code idsTarea} viene vacío.
     */
    List<FilaHistorialAlCierre> ultimoHistorialAntesDe(Collection<Long> idsTarea, OffsetDateTime cierre) {
        if (idsTarea.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idsTarea", idsTarea)
                .addValue("cierre", cierre);
        return jdbc.query(
                "SELECT ID_TAREA, CODIGO_ESTADO, ID_USUARIO_CAMBIO, FECHA_CAMBIO FROM ( "
                        + "  SELECT h.ID_TAREA AS ID_TAREA, ce.CODIGO_ESTADO AS CODIGO_ESTADO, "
                        + "         h.ID_USUARIO_CAMBIO AS ID_USUARIO_CAMBIO, h.FECHA_CAMBIO AS FECHA_CAMBIO, "
                        + "         ROW_NUMBER() OVER (PARTITION BY h.ID_TAREA "
                        + "                             ORDER BY h.FECHA_CAMBIO DESC, h.ID_HISTORIAL DESC) AS RN "
                        + "  FROM HISTORIAL_ESTADO_TAREA h "
                        + "  JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = h.ID_ESTADO_NUEVO "
                        + "  WHERE h.ID_TAREA IN (:idsTarea) AND h.FECHA_CAMBIO < :cierre"
                        + ") WHERE RN = 1",
                parametros, MAPEADOR_HISTORIAL);
    }

    /**
     * Las asignaciones de cada una de {@code idsTarea} con {@code
     * FECHA_ASIGNACION < cierre}, por tarea y luego por {@code
     * FECHA_ASIGNACION}/{@code ID_ASIGNACION} ascendente. Devuelve lista
     * vacía sin consultar si {@code idsTarea} viene vacío.
     */
    List<FilaAsignacion> asignacionesAntesDe(Collection<Long> idsTarea, OffsetDateTime cierre) {
        if (idsTarea.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idsTarea", idsTarea)
                .addValue("cierre", cierre);
        return jdbc.query(
                "SELECT ID_TAREA, ID_USUARIO_ASIGNADO, FECHA_ASIGNACION, FECHA_TERMINO FROM ASIGNACION_TAREA "
                        + "WHERE ID_TAREA IN (:idsTarea) AND FECHA_ASIGNACION < :cierre "
                        + "ORDER BY ID_TAREA, FECHA_ASIGNACION, ID_ASIGNACION",
                parametros, MAPEADOR_ASIGNACION);
    }

    /**
     * Los relevos ({@code TRASPASO_FAMILIAR}) del grupo con {@code
     * FECHA_TRASPASO} en {@code [inicio, cierre)}, con su código de estado
     * ya resuelto, por fecha y luego por id.
     */
    List<FilaRelevo> relevosDelPeriodo(long idGrupo, OffsetDateTime inicio, OffsetDateTime cierre) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idGrupo", idGrupo)
                .addValue("inicio", inicio)
                .addValue("cierre", cierre);
        return jdbc.query(
                "SELECT t.ID_TRASPASO, t.ID_ADULTO_MAYOR, t.FECHA_TRASPASO, t.ID_USUARIO_SALIENTE, "
                        + "t.ID_USUARIO_ENTRANTE, ce.CODIGO_ESTADO FROM TRASPASO_FAMILIAR t "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = t.ID_ESTADO "
                        + "WHERE t.ID_GRUPO = :idGrupo AND t.FECHA_TRASPASO >= :inicio AND t.FECHA_TRASPASO < :cierre "
                        + "ORDER BY t.FECHA_TRASPASO, t.ID_TRASPASO",
                parametros, MAPEADOR_RELEVO);
    }

    /**
     * Cuenta, por tipo de elemento ({@code TAREA} | {@code ALERTA}), los
     * {@code DETALLE_TRASPASO} de cada uno de {@code idsTraspaso} (mismo
     * estilo que {@code relevos.RepositorioConsultaRelevos
     * #conteosDeDetallePorTraspaso}). Devuelve {@code Map.of()} sin
     * consultar si {@code idsTraspaso} viene vacío.
     */
    Map<Long, Map<String, Long>> conteosDetalleTraspaso(Collection<Long> idsTraspaso) {
        if (idsTraspaso.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource parametros = new MapSqlParameterSource("idsTraspaso", idsTraspaso);
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

    /**
     * Las alertas ({@code ALERTA_CUIDADO}) del grupo con {@code
     * FECHA_DETECCION} en {@code [inicio, cierre)}, por fecha de detección y
     * luego por id.
     */
    List<FilaAlerta> alertasDelPeriodo(long idGrupo, OffsetDateTime inicio, OffsetDateTime cierre) {
        MapSqlParameterSource parametros = new MapSqlParameterSource()
                .addValue("idGrupo", idGrupo)
                .addValue("inicio", inicio)
                .addValue("cierre", cierre);
        return jdbc.query(
                "SELECT ID_ALERTA, ID_ADULTO_MAYOR, TIPO_ALERTA, FECHA_DETECCION, FECHA_REVISION "
                        + "FROM ALERTA_CUIDADO WHERE ID_GRUPO = :idGrupo "
                        + "AND FECHA_DETECCION >= :inicio AND FECHA_DETECCION < :cierre "
                        + "ORDER BY FECHA_DETECCION, ID_ALERTA",
                parametros, MAPEADOR_ALERTA);
    }

    // ------------------------------------------------------------------
    // Filas crudas (package-private: solo las ensambla ServicioInternoReportes)
    // ------------------------------------------------------------------

    record FilaGrupo(long idGrupo, String nombreGrupo) {
    }

    record FilaTarea(long idTarea, long idAdulto, String tipoTarea, String titulo, OffsetDateTime fechaHorario,
            int minutosTolerancia) {
    }

    record FilaAdulto(long idAdulto, String nombre) {
    }

    record FilaHistorialAlCierre(long idTarea, String codigoEstado, long idUsuarioCambio, OffsetDateTime fechaCambio) {
    }

    record FilaAsignacion(long idTarea, long idUsuarioAsignado, OffsetDateTime fechaAsignacion,
            OffsetDateTime fechaTermino) {
    }

    record FilaRelevo(long idTraspaso, long idAdulto, OffsetDateTime fechaTraspaso, long idUsuarioSaliente,
            long idUsuarioEntrante, String codigoEstado) {
    }

    record FilaAlerta(long idAlerta, long idAdulto, String tipoAlerta, OffsetDateTime fechaDeteccion,
            OffsetDateTime fechaRevision) {
    }
}
