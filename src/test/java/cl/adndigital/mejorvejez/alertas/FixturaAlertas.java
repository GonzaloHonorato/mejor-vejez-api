package cl.adndigital.mejorvejez.alertas;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Crea alertas, intentos de notificación y canales con {@link JdbcTemplate}
 * para pruebas de integración contra el Oracle compartido, con el mismo
 * estilo que {@code tareas.FixturaTareas}: cada fila queda confirmada en la
 * base compartida, así que las pruebas deben filtrar siempre por sus propios
 * IDs y nunca contar tablas completas.
 */
public class FixturaAlertas {

    private final JdbcTemplate jdbcTemplate;

    public FixturaAlertas(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Crea una ALERTA_CUIDADO nacida de una tarea, en estado ABIERTA, con la clave y el tipo indicados. */
    public long crearAlertaDeTarea(long idGrupo, long idAdulto, long idTarea, String tipo, String clave) {
        return insertarYObtenerId(
                "INSERT INTO ALERTA_CUIDADO (CLAVE_ALERTA, TIPO_ALERTA, ID_TAREA, ID_GRUPO, ID_ADULTO_MAYOR, "
                        + "MENSAJE_ALERTA, ID_ESTADO) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "ID_ALERTA",
                clave, tipo, idTarea, idGrupo, idAdulto, "Mensaje de alerta fixtura",
                idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO, CodigosAlertas.CAMPO_ESTADO,
                        CodigosAlertas.ESTADO_ALERTA_ABIERTA));
    }

    /** Crea una ALERTA_CUIDADO de REPOSICION_MEDICAMENTO nacida de un detalle del plan, en estado ABIERTA. */
    public long crearAlertaDeDetalle(long idGrupo, long idAdulto, long idDetallePlan, String clave) {
        return insertarYObtenerId(
                "INSERT INTO ALERTA_CUIDADO (CLAVE_ALERTA, TIPO_ALERTA, ID_DETALLE_PLAN, ID_GRUPO, ID_ADULTO_MAYOR, "
                        + "MENSAJE_ALERTA, ID_ESTADO) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "ID_ALERTA",
                clave, CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO, idDetallePlan, idGrupo, idAdulto,
                "Mensaje de alerta fixtura",
                idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO, CodigosAlertas.CAMPO_ESTADO,
                        CodigosAlertas.ESTADO_ALERTA_ABIERTA));
    }

    /** Cambia el estado de la alerta indicada ({@code ABIERTA} | {@code CERRADA}), sin pasar por el servicio. */
    public void cambiarEstadoAlerta(long idAlerta, String codigoEstado) {
        jdbcTemplate.update("UPDATE ALERTA_CUIDADO SET ID_ESTADO = ? WHERE ID_ALERTA = ?",
                idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO, CodigosAlertas.CAMPO_ESTADO, codigoEstado), idAlerta);
    }

    /** Inserta un INTENTO_NOTIFICACION de la alerta/usuario/canal/número indicados, con el estado de envío dado. */
    public long crearIntento(long idAlerta, long idUsuario, String canal, int numero, String codigoEstadoEnvio) {
        return insertarYObtenerId(
                "INSERT INTO INTENTO_NOTIFICACION (ID_ALERTA, ID_USUARIO_DESTINATARIO, CANAL, NUMERO_INTENTO, "
                        + "ID_ESTADO_ENVIO) VALUES (?, ?, ?, ?, ?)",
                "ID_INTENTO",
                idAlerta, idUsuario, canal, numero,
                idEstado(CodigosAlertas.TABLA_INTENTO_NOTIFICACION, CodigosAlertas.CAMPO_ESTADO_ENVIO,
                        codigoEstadoEnvio));
    }

    /** Inserta un CANAL_NOTIFICACION_USUARIO del usuario y canal indicados. */
    public void crearCanal(long idUsuario, String canal, String destino, boolean habilitado) {
        jdbcTemplate.update(
                "INSERT INTO CANAL_NOTIFICACION_USUARIO (ID_USUARIO, CANAL, DESTINO, HABILITADO) "
                        + "VALUES (?, ?, ?, ?)",
                idUsuario, canal, destino, habilitado ? "S" : "N");
    }

    /** Código de estado actual de la alerta (resuelto contra CATALOGO_ESTADOS). */
    public String estadoAlerta(long idAlerta) {
        return jdbcTemplate.queryForObject(
                "SELECT ce.CODIGO_ESTADO FROM ALERTA_CUIDADO a "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = a.ID_ESTADO WHERE a.ID_ALERTA = ?",
                String.class, idAlerta);
    }

    /** Todos los intentos de la alerta indicada, para pruebas que inspeccionan varios campos a la vez. */
    public List<Map<String, Object>> intentos(long idAlerta) {
        return jdbcTemplate.queryForList("SELECT * FROM INTENTO_NOTIFICACION WHERE ID_ALERTA = ?", idAlerta);
    }

    /** Todas las alertas nacidas de la tarea indicada, para pruebas que inspeccionan varios campos a la vez. */
    public List<Map<String, Object>> alertasDeTarea(long idTarea) {
        return jdbcTemplate.queryForList("SELECT * FROM ALERTA_CUIDADO WHERE ID_TAREA = ?", idTarea);
    }

    /** Todas las alertas nacidas del detalle indicado (reposición), por id de inserción. */
    public List<Map<String, Object>> alertasDelDetalle(long idDetallePlan) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM ALERTA_CUIDADO WHERE ID_DETALLE_PLAN = ? ORDER BY ID_ALERTA", idDetallePlan);
    }

    /** Clave de alerta única para una prueba (evita colisionar con UQ_ALERTA_CLAVE). */
    public static String claveUnica() {
        return "fixtura-alerta-" + UUID.randomUUID();
    }

    private long idEstado(String tabla, String campo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = ? AND CODIGO_ESTADO = ?",
                Long.class, tabla, campo, codigo);
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
