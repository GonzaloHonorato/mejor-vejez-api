package cl.adndigital.mejorvejez.modelo;

import java.sql.PreparedStatement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Crea filas válidas mínimas del modelo de datos para pruebas de integración
 * contra Oracle real. Pensada para reutilizarse en las pruebas de fases
 * posteriores a la Fase 0: cada método inserta solo lo necesario para que la
 * fila cumpla las restricciones del esquema (NOT NULL, CHECK, FK), dejando el
 * resto en sus valores por defecto.
 */
public class FixturaModelo {

    private final JdbcTemplate jdbcTemplate;

    public FixturaModelo(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Resuelve el ID de un estado del catálogo CATALOGO_ESTADOS. */
    public long idEstado(String tabla, String campo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = ? AND CODIGO_ESTADO = ?",
                Long.class, tabla, campo, codigo);
    }

    /** Resuelve el ID de una opción del catálogo CATALOGO_NEGOCIO. */
    public long idOpcion(String tipo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = ? AND CODIGO = ?",
                Long.class, tipo, codigo);
    }

    /** Crea un USUARIO activo. El email se deriva del uid de Firebase. */
    public long crearUsuario(String uidFirebase) {
        long idEstadoActivo = idEstado("USUARIO", "ESTADO", "ACTIVO");
        String email = uidFirebase + "@fixtura.mejorvejez.test";
        return insertarYObtenerId(
                "INSERT INTO USUARIO (UID_FIREBASE, EMAIL, NOMBRE, ID_ESTADO) VALUES (?, ?, ?, ?)",
                "ID_USUARIO",
                uidFirebase, email, "Usuario fixtura " + uidFirebase, idEstadoActivo);
    }

    /** Crea un GRUPO_FAMILIAR activo con el creador indicado. */
    public long crearGrupo(long idCreador) {
        long idEstadoActivo = idEstado("GRUPO_FAMILIAR", "ESTADO", "ACTIVO");
        return insertarYObtenerId(
                "INSERT INTO GRUPO_FAMILIAR (NOMBRE_GRUPO, ID_USUARIO_CREADOR, ID_ESTADO) VALUES (?, ?, ?)",
                "ID_GRUPO",
                "Grupo fixtura", idCreador, idEstadoActivo);
    }

    /** Crea un ADULTO_MAYOR activo dentro del grupo indicado. */
    public long crearAdulto(long idGrupo, long idCreador) {
        long idEstadoActivo = idEstado("ADULTO_MAYOR", "ESTADO", "ACTIVO");
        return insertarYObtenerId(
                "INSERT INTO ADULTO_MAYOR (ID_GRUPO, NOMBRE_COMPLETO, ID_ESTADO, ID_USUARIO_CREADOR) "
                        + "VALUES (?, ?, ?, ?)",
                "ID_ADULTO_MAYOR",
                idGrupo, "Adulto fixtura", idEstadoActivo, idCreador);
    }

    /**
     * Crea un MEDICAMENTO con presentación COMPRIMIDO (fija a propósito, para
     * que dos llamadas con el mismo nombre/concentración normalizados caigan
     * en la misma fila del índice único UX_MEDICAMENTO_NORMALIZADO).
     */
    public long crearMedicamento(String nombre, String concentracion) {
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        return insertarYObtenerId(
                "INSERT INTO MEDICAMENTO (NOMBRE, ID_PRESENTACION, CONCENTRACION) VALUES (?, ?, ?)",
                "ID_MEDICAMENTO",
                nombre, idPresentacion, concentracion);
    }

    /** Crea una TAREA_CUIDADO manual (tipo CITAS_CONTROLES_MEDICOS), pendiente. */
    public long crearTareaManual(long idGrupo, long idAdulto) {
        long idEstadoPendiente = idEstado("TAREA_CUIDADO", "ESTADO", "PENDIENTE");
        return insertarYObtenerId(
                "INSERT INTO TAREA_CUIDADO (TIPO_TAREA, ID_GRUPO, ID_ADULTO_MAYOR, TITULO_TAREA, "
                        + "FECHA_HORARIO, ID_ESTADO) VALUES ('CITAS_CONTROLES_MEDICOS', ?, ?, ?, SYSTIMESTAMP, ?)",
                "ID_TAREA",
                idGrupo, idAdulto, "Tarea fixtura", idEstadoPendiente);
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
