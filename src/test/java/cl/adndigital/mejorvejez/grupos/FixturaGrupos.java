package cl.adndigital.mejorvejez.grupos;

import java.sql.PreparedStatement;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Crea usuarios, grupos familiares e integrantes con {@link JdbcTemplate}
 * para pruebas de integración contra el Oracle compartido. Reutilizable por
 * las pruebas de grupos, invitaciones y salida (Fase 1 en adelante).
 *
 * <p>Los datos quedan confirmados en la base compartida: el correo de cada
 * usuario lleva un {@link UUID} propio y el uid lo elige el llamador (usa
 * {@link #uidUnico()}), así que las pruebas deben filtrar siempre por sus
 * propios IDs y nunca contar tablas completas.
 */
public class FixturaGrupos {

    private final JdbcTemplate jdbcTemplate;

    public FixturaGrupos(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Uid de Firebase único para una prueba. */
    public static String uidUnico() {
        return "uid-" + UUID.randomUUID();
    }

    /** Crea un USUARIO activo con el uid indicado; {@code admin} marca {@code ES_ADMINISTRADOR}. */
    public long crearUsuario(String uid, boolean admin) {
        String correo = "fixtura-" + UUID.randomUUID() + "@grupos.mejorvejez.test";
        return insertarYObtenerId(
                "INSERT INTO USUARIO (UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "ID_USUARIO",
                uid, correo, "Usuario fixtura", admin ? "S" : "N", idEstado("USUARIO", "ACTIVO"));
    }

    /** Crea un GRUPO_FAMILIAR activo con el creador indicado (no lo agrega como integrante). */
    public long crearGrupo(long idCreador) {
        return insertarYObtenerId(
                "INSERT INTO GRUPO_FAMILIAR (NOMBRE_GRUPO, ID_USUARIO_CREADOR, ID_ESTADO) VALUES (?, ?, ?)",
                "ID_GRUPO",
                "Grupo fixtura " + UUID.randomUUID().toString().substring(0, 8), idCreador,
                idEstado("GRUPO_FAMILIAR", "ACTIVO"));
    }

    /**
     * Agrega un INTEGRANTE_GRUPO con {@code rol} ({@code COORDINADOR} |
     * {@code COLABORADOR}) y {@code estado} ({@code ACTIVO} | {@code INACTIVO}
     * | {@code SALIDA_PENDIENTE}). Un estado distinto de ACTIVO deja también
     * {@code FECHA_DESACTIVACION}.
     */
    public long agregarIntegrante(long idGrupo, long idUsuario, String rol, String estado) {
        long idRol = jdbcTemplate.queryForObject(
                "SELECT ID_ROL FROM ROL_GRUPO WHERE NOMBRE_ROL = ?", Long.class, rol);
        String sql = "ACTIVO".equals(estado)
                ? "INSERT INTO INTEGRANTE_GRUPO (ID_GRUPO, ID_USUARIO, ID_ROL, ID_ESTADO) VALUES (?, ?, ?, ?)"
                : "INSERT INTO INTEGRANTE_GRUPO (ID_GRUPO, ID_USUARIO, ID_ROL, ID_ESTADO, FECHA_DESACTIVACION) "
                        + "VALUES (?, ?, ?, ?, SYSTIMESTAMP)";
        return insertarYObtenerId(sql, "ID_INTEGRANTE",
                idGrupo, idUsuario, idRol, idEstado("INTEGRANTE_GRUPO", estado));
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
