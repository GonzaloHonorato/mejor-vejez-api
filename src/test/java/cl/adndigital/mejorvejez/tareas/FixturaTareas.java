package cl.adndigital.mejorvejez.tareas;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Crea tareas de cuidado, ocurrencias planificadas y asignaciones con {@link
 * JdbcTemplate} para pruebas de integración contra el Oracle compartido, con
 * el mismo estilo que {@code planificacion.FixturaPlanes}: cada fila queda
 * confirmada en la base compartida, así que las pruebas deben filtrar
 * siempre por sus propios IDs y nunca contar tablas completas.
 */
public class FixturaTareas {

    private final JdbcTemplate jdbcTemplate;

    public FixturaTareas(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Crea una TAREA_CUIDADO de tipo MEDICAMENTOS para el detalle indicado,
     * con la clave de ocurrencia y el estado ({@code PENDIENTE} | {@code
     * EN_CURSO} | {@code COMPLETADA} | {@code NO_REALIZADA} | {@code
     * CANCELADA}) dados. {@code CLAVE_OCURRENCIA} e {@code ID_DETALLE_PLAN}
     * son obligatorios en este tipo de tarea ({@code CK_TAREA_MEDICAMENTO}).
     */
    public long crearTareaMedicamento(long idGrupo, long idAdulto, long idDetalle, OffsetDateTime horario,
            String codigoEstado, String clave) {
        return insertarYObtenerId(
                "INSERT INTO TAREA_CUIDADO (TIPO_TAREA, CLAVE_OCURRENCIA, ID_GRUPO, ID_ADULTO_MAYOR, "
                        + "ID_DETALLE_PLAN, TITULO_TAREA, FECHA_HORARIO, ID_ESTADO) "
                        + "VALUES ('MEDICAMENTOS', ?, ?, ?, ?, ?, ?, ?)",
                "ID_TAREA",
                clave, idGrupo, idAdulto, idDetalle, "Tarea fixtura de medicamento", horario,
                idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, codigoEstado));
    }

    /**
     * Crea una TAREA_CUIDADO manual (sin clave de ocurrencia ni detalle de
     * plan) del tipo indicado ({@code COMPRA_RETIRO_MEDICAMENTOS} | {@code
     * CITAS_CONTROLES_MEDICOS}), en estado PENDIENTE.
     */
    public long crearTareaManual(long idGrupo, long idAdulto, String tipo, OffsetDateTime horario) {
        return insertarYObtenerId(
                "INSERT INTO TAREA_CUIDADO (TIPO_TAREA, ID_GRUPO, ID_ADULTO_MAYOR, TITULO_TAREA, FECHA_HORARIO, "
                        + "ID_ESTADO) VALUES (?, ?, ?, ?, ?, ?)",
                "ID_TAREA",
                tipo, idGrupo, idAdulto, "Tarea fixtura manual", horario,
                idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, CodigosTareas.ESTADO_TAREA_PENDIENTE));
    }

    /**
     * Crea una OCURRENCIA_PLANIFICADA del detalle/instante indicados, con el
     * estado ({@code PLANIFICADA} | {@code TAREA_CREADA} | {@code CANCELADA}
     * | {@code ERROR}) y la tarea vinculada dados ({@code idTarea} puede ser
     * {@code null}).
     */
    public long crearOcurrencia(long idDetalle, long idAdulto, long idGrupo, OffsetDateTime horario, String clave,
            String codigoEstado, Long idTarea) {
        long idEstadoOcurrencia = idEstado(CodigosTareas.TABLA_OCURRENCIA_PLANIFICADA, codigoEstado);
        if (idTarea == null) {
            return insertarYObtenerId(
                    "INSERT INTO OCURRENCIA_PLANIFICADA (ID_DETALLE_PLAN, ID_ADULTO_MAYOR, ID_GRUPO, "
                            + "FECHA_HORARIO, CLAVE_OCURRENCIA, ID_ESTADO) VALUES (?, ?, ?, ?, ?, ?)",
                    "ID_OCURRENCIA",
                    idDetalle, idAdulto, idGrupo, horario, clave, idEstadoOcurrencia);
        }
        return insertarYObtenerId(
                "INSERT INTO OCURRENCIA_PLANIFICADA (ID_DETALLE_PLAN, ID_ADULTO_MAYOR, ID_GRUPO, FECHA_HORARIO, "
                        + "CLAVE_OCURRENCIA, ID_ESTADO, ID_TAREA) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "ID_OCURRENCIA",
                idDetalle, idAdulto, idGrupo, horario, clave, idEstadoOcurrencia, idTarea);
    }

    /** Crea una ASIGNACION_TAREA ACTIVO de la tarea indicada, con asignado y asignador. */
    public long crearAsignacion(long idTarea, long idGrupo, long idAsignado, long idAsignador) {
        return insertarYObtenerId(
                "INSERT INTO ASIGNACION_TAREA (ID_TAREA, ID_GRUPO, ID_USUARIO_ASIGNADO, ID_USUARIO_ASIGNADOR, "
                        + "ID_ESTADO) VALUES (?, ?, ?, ?, ?)",
                "ID_ASIGNACION",
                idTarea, idGrupo, idAsignado, idAsignador,
                idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA, CodigosTareas.ESTADO_ASIGNACION_ACTIVO));
    }

    /**
     * Inserta una ASIGNACION_TAREA con el estado, la fecha de asignación y la
     * fecha de término indicados (controla el orden en pruebas de historial
     * de asignación; {@code fechaTermino} puede ser {@code null}).
     */
    public long crearAsignacionConEstado(long idTarea, long idGrupo, long idAsignado, long idAsignador,
            String codigoEstado, OffsetDateTime fechaAsignacion, OffsetDateTime fechaTermino, String motivo) {
        return insertarYObtenerId(
                "INSERT INTO ASIGNACION_TAREA (ID_TAREA, ID_GRUPO, ID_USUARIO_ASIGNADO, ID_USUARIO_ASIGNADOR, "
                        + "FECHA_ASIGNACION, FECHA_TERMINO, ID_ESTADO, MOTIVO) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "ID_ASIGNACION",
                idTarea, idGrupo, idAsignado, idAsignador, fechaAsignacion, fechaTermino,
                idEstado(CodigosTareas.TABLA_ASIGNACION_TAREA, codigoEstado), motivo);
    }

    /** Inserta una fila de HISTORIAL_ESTADO_TAREA con la fecha de cambio indicada (controla el orden en pruebas). */
    public long crearHistorial(long idTarea, Long idEstadoAnterior, long idEstadoNuevo, long idUsuario,
            String observacion, OffsetDateTime fechaCambio) {
        return insertarYObtenerId(
                "INSERT INTO HISTORIAL_ESTADO_TAREA (ID_TAREA, ID_ESTADO_ANTERIOR, ID_ESTADO_NUEVO, "
                        + "ID_USUARIO_CAMBIO, FECHA_CAMBIO, OBSERVACION) VALUES (?, ?, ?, ?, ?, ?)",
                "ID_HISTORIAL",
                idTarea, idEstadoAnterior, idEstadoNuevo, idUsuario, fechaCambio, observacion);
    }

    /** Cambia el estado de la tarea indicada (ayuda a las pruebas que necesitan un estado distinto de PENDIENTE). */
    public void cambiarEstadoTarea(long idTarea, String codigoEstado) {
        jdbcTemplate.update("UPDATE TAREA_CUIDADO SET ID_ESTADO = ? WHERE ID_TAREA = ?",
                idEstado(CodigosTareas.TABLA_TAREA_CUIDADO, codigoEstado), idTarea);
    }

    /** Inserta una línea de compra de la tarea indicada ({@code COMPRA_RETIRO_MEDICAMENTOS}). */
    public long crearLineaCompra(long idTarea, long idMedicamento, long idPresentacion, String dosisIndicada,
            BigDecimal cantidadSolicitada, long idUnidadCantidad, String observaciones) {
        return insertarYObtenerId(
                "INSERT INTO DETALLE_COMPRA_MEDICAMENTOS (ID_TAREA, ID_MEDICAMENTO, ID_PRESENTACION, "
                        + "DOSIS_INDICADA, CANTIDAD_SOLICITADA, ID_UNIDAD_CANTIDAD, OBSERVACIONES) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                "ID_DETALLE_COMPRA",
                idTarea, idMedicamento, idPresentacion, dosisIndicada, cantidadSolicitada, idUnidadCantidad,
                observaciones);
    }

    /** Inserta el detalle de cita médica de la tarea indicada ({@code CITAS_CONTROLES_MEDICOS}). */
    public void crearCita(long idTarea, String nombreMedico, String lugarAtencion, String observaciones) {
        jdbcTemplate.update(
                "INSERT INTO DETALLE_CITA_MEDICA (ID_TAREA, NOMBRE_MEDICO, LUGAR_ATENCION, OBSERVACIONES) "
                        + "VALUES (?, ?, ?, ?)",
                idTarea, nombreMedico, lugarAtencion, observaciones);
    }

    /** Código de estado actual de la tarea (resuelto contra CATALOGO_ESTADOS). */
    public String estadoTarea(long idTarea) {
        return jdbcTemplate.queryForObject(
                "SELECT ce.CODIGO_ESTADO FROM TAREA_CUIDADO t "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = t.ID_ESTADO WHERE t.ID_TAREA = ?",
                String.class, idTarea);
    }

    /** Todas las columnas de las tareas del detalle indicado, para pruebas que inspeccionan varios campos a la vez. */
    public List<Map<String, Object>> tareasDelDetalle(long idDetalle) {
        return jdbcTemplate.queryForList("SELECT * FROM TAREA_CUIDADO WHERE ID_DETALLE_PLAN = ?", idDetalle);
    }

    /** Clave de ocurrencia única para una prueba (evita colisionar con UQ_OCURRENCIA_CLAVE / UQ_TAREA_CLAVE_OCURRENCIA). */
    public static String claveUnica() {
        return "fixtura-clave-" + UUID.randomUUID();
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
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
