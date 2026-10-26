package cl.adndigital.mejorvejez.planificacion;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Crea planes de medicamentos, medicamentos y sus detalles con {@link
 * JdbcTemplate} para pruebas de integración contra el Oracle compartido, con
 * el mismo estilo que {@code adultos.FixturaAdultos} / {@code
 * grupos.FixturaGrupos}: cada fila queda confirmada en la base compartida,
 * así que las pruebas deben filtrar siempre por sus propios IDs y nunca
 * contar tablas completas.
 */
public class FixturaPlanes {

    private final JdbcTemplate jdbcTemplate;

    public FixturaPlanes(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Crea un PLAN_MEDICAMENTOS del grupo/adulto/creador indicados, en el estado dado ({@code ACTIVO} | {@code INACTIVO}). */
    public long crearPlan(long idGrupo, long idAdulto, long idCreador, String estado) {
        return insertarYObtenerId(
                "INSERT INTO PLAN_MEDICAMENTOS (ID_GRUPO, ID_ADULTO_MAYOR, NOMBRE_PLAN, ID_ESTADO, "
                        + "VIGENTE_DESDE, ID_USUARIO_CREADOR) VALUES (?, ?, ?, ?, TRUNC(SYSDATE), ?)",
                "ID_PLAN",
                idGrupo, idAdulto, "Plan fixtura", idEstado("PLAN_MEDICAMENTOS", estado), idCreador);
    }

    /**
     * Crea un PLAN_MEDICAMENTOS {@code ACTIVO} con la vigencia explícita
     * indicada ({@code vigenteHasta} puede ser {@code null}): para pruebas
     * que fijan el reloj y no pueden depender de {@code TRUNC(SYSDATE)}.
     */
    public long crearPlanConVigencia(long idGrupo, long idAdulto, long idCreador, LocalDate vigenteDesde,
            LocalDate vigenteHasta) {
        long idEstadoActivo = idEstado("PLAN_MEDICAMENTOS", "ACTIVO");
        if (vigenteHasta == null) {
            return insertarYObtenerId(
                    "INSERT INTO PLAN_MEDICAMENTOS (ID_GRUPO, ID_ADULTO_MAYOR, NOMBRE_PLAN, ID_ESTADO, "
                            + "VIGENTE_DESDE, ID_USUARIO_CREADOR) VALUES (?, ?, ?, ?, ?, ?)",
                    "ID_PLAN",
                    idGrupo, idAdulto, "Plan fixtura", idEstadoActivo, vigenteDesde, idCreador);
        }
        return insertarYObtenerId(
                "INSERT INTO PLAN_MEDICAMENTOS (ID_GRUPO, ID_ADULTO_MAYOR, NOMBRE_PLAN, ID_ESTADO, "
                        + "VIGENTE_DESDE, VIGENTE_HASTA, ID_USUARIO_CREADOR) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "ID_PLAN",
                idGrupo, idAdulto, "Plan fixtura", idEstadoActivo, vigenteDesde, vigenteHasta, idCreador);
    }

    /**
     * Crea un MEDICAMENTO del catálogo global con el nombre indicado más un
     * {@link UUID}, para no colisionar con {@code UX_MEDICAMENTO_NORMALIZADO}
     * (V3) entre pruebas. Presentación COMPRIMIDO.
     */
    public long crearMedicamento(String nombre) {
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        String nombreUnico = nombre + " " + UUID.randomUUID();
        return insertarYObtenerId(
                "INSERT INTO MEDICAMENTO (NOMBRE, ID_PRESENTACION) VALUES (?, ?)",
                "ID_MEDICAMENTO",
                nombreUnico, idPresentacion);
    }

    /**
     * Crea un DETALLE_PLAN del plan/medicamento indicados, en el estado dado
     * ({@code ACTIVO} | {@code INACTIVO}): presentación COMPRIMIDO, unidad
     * COMPRIMIDO, dosis 1, un día (LUNES) y una hora (08:00).
     */
    public long crearDetalle(long idPlan, long idMedicamento, String estado) {
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        long idUnidadDosis = idOpcion("UNIDAD_DOSIS", "COMPRIMIDO");
        long idDetalle = insertarYObtenerId(
                "INSERT INTO DETALLE_PLAN (ID_PLAN, ID_MEDICAMENTO, NOMBRE_MEDICAMENTO, ID_PRESENTACION, "
                        + "DOSIS_CANTIDAD, ID_UNIDAD_DOSIS, FECHA_INICIO, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, 1, ?, TRUNC(SYSDATE), ?)",
                "ID_DETALLE_PLAN",
                idPlan, idMedicamento, "Medicamento fixtura", idPresentacion, idUnidadDosis,
                idEstado("DETALLE_PLAN", estado));

        long idLunes = idOpcion("DIA_SEMANA", "LUNES");
        jdbcTemplate.update(
                "INSERT INTO DETALLE_PLAN_DIA (ID_DETALLE_PLAN, ID_DIA_SEMANA) VALUES (?, ?)",
                idDetalle, idLunes);
        jdbcTemplate.update(
                "INSERT INTO DETALLE_PLAN_HORARIO (ID_DETALLE_PLAN, HORA_ADMINISTRACION) "
                        + "VALUES (?, TIMESTAMP '1970-01-01 08:00:00')",
                idDetalle);

        return idDetalle;
    }

    /**
     * Crea una TAREA_CUIDADO de tipo MEDICAMENTOS para el detalle indicado
     * (según V1: {@code CLAVE_OCURRENCIA} e {@code ID_DETALLE_PLAN} son
     * obligatorios en ese tipo de tarea).
     */
    public long crearTareaDeMedicamento(long idGrupo, long idAdulto, long idDetalle, OffsetDateTime horario) {
        long idEstadoPendiente = idEstado("TAREA_CUIDADO", "PENDIENTE");
        String claveOcurrencia = "fixtura-tarea-" + UUID.randomUUID();
        return insertarYObtenerId(
                "INSERT INTO TAREA_CUIDADO (TIPO_TAREA, CLAVE_OCURRENCIA, ID_GRUPO, ID_ADULTO_MAYOR, "
                        + "ID_DETALLE_PLAN, TITULO_TAREA, FECHA_HORARIO, ID_ESTADO) "
                        + "VALUES ('MEDICAMENTOS', ?, ?, ?, ?, ?, ?, ?)",
                "ID_TAREA",
                claveOcurrencia, idGrupo, idAdulto, idDetalle, "Tarea fixtura de medicamento", horario,
                idEstadoPendiente);
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
    }

    private long idOpcion(String tipo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = ? AND CODIGO = ?",
                Long.class, tipo, codigo);
    }

    private long insertarYObtenerId(String sql, String columnaId, Object... parametros) {
        KeyHolder generadorDeClave = new GeneratedKeyHolder();
        jdbcTemplate.update(conexion -> {
            PreparedStatement sentencia = conexion.prepareStatement(sql, new String[] { columnaId });
            for (int i = 0; i < parametros.length; i++) {
                sentencia.setObject(i + 1, parametros[i]);
            }
            return sentencia;
        }, generadorDeClave);
        return generadorDeClave.getKey().longValue();
    }
}
