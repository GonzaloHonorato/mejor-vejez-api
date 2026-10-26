package cl.adndigital.mejorvejez.grupos;

import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Decide si un integrante tiene historial de uso en el grupo (más allá de la
 * membresía misma), para impedir su eliminación física ({@code DELETE
 * .../integrantes/{id}}) cuando sí lo tiene (debe desactivarse en su lugar,
 * no borrarse, para no perder la trazabilidad de esos datos).
 *
 * <p>No es un {@code JpaRepository}: no hay una entidad JPA que respalde esta
 * consulta (recorre varias tablas sin relación entre sí), así que usa
 * {@link NamedParameterJdbcTemplate} directo, igual que los fixtures de
 * prueba.
 */
@Repository
public class RepositorioHistorialIntegrante {

    private static final String SQL_TIENE_HISTORIAL = """
            SELECT CASE WHEN
                EXISTS (SELECT 1 FROM ASIGNACION_TAREA a
                        WHERE a.ID_GRUPO = :idGrupo
                        AND (a.ID_USUARIO_ASIGNADO = :idUsuario OR a.ID_USUARIO_ASIGNADOR = :idUsuario))
                OR EXISTS (SELECT 1 FROM TAREA_CUIDADO t
                        WHERE t.ID_GRUPO = :idGrupo AND t.ID_USUARIO_CONFIRMADOR = :idUsuario)
                OR EXISTS (SELECT 1 FROM ALERTA_CUIDADO al
                        WHERE al.ID_GRUPO = :idGrupo AND al.ID_USUARIO_REVISOR = :idUsuario)
                OR EXISTS (SELECT 1 FROM INTENTO_NOTIFICACION i
                        JOIN ALERTA_CUIDADO ali ON ali.ID_ALERTA = i.ID_ALERTA
                        WHERE ali.ID_GRUPO = :idGrupo AND i.ID_USUARIO_DESTINATARIO = :idUsuario)
                OR EXISTS (SELECT 1 FROM ADULTO_MAYOR am
                        WHERE am.ID_GRUPO = :idGrupo
                        AND (am.ID_USUARIO_CREADOR = :idUsuario OR am.ID_USUARIO_ACTUALIZACION = :idUsuario))
                OR EXISTS (SELECT 1 FROM PLAN_MEDICAMENTOS pm
                        WHERE pm.ID_GRUPO = :idGrupo
                        AND (pm.ID_USUARIO_CREADOR = :idUsuario OR pm.ID_USUARIO_ACTUALIZACION = :idUsuario))
                OR EXISTS (SELECT 1 FROM CONTACTO_EMERGENCIA ce
                        JOIN ADULTO_MAYOR amc ON amc.ID_ADULTO_MAYOR = ce.ID_ADULTO_MAYOR
                        WHERE amc.ID_GRUPO = :idGrupo AND ce.ID_USUARIO_CREADOR = :idUsuario)
                OR EXISTS (SELECT 1 FROM DETALLE_PLAN dp
                        JOIN PLAN_MEDICAMENTOS pmd ON pmd.ID_PLAN = dp.ID_PLAN
                        WHERE pmd.ID_GRUPO = :idGrupo AND dp.ID_RESPONSABLE_PREDETERMINADO = :idUsuario)
                OR EXISTS (SELECT 1 FROM TRASPASO_FAMILIAR tf
                        WHERE tf.ID_GRUPO = :idGrupo
                        AND (tf.ID_USUARIO_SALIENTE = :idUsuario OR tf.ID_USUARIO_ENTRANTE = :idUsuario
                             OR tf.ID_USUARIO_CREADOR = :idUsuario))
                OR EXISTS (SELECT 1 FROM PROPUESTA_SUCESION ps
                        WHERE ps.ID_GRUPO = :idGrupo
                        AND (ps.ID_USUARIO_SALIENTE = :idUsuario OR ps.ID_USUARIO_CANDIDATO = :idUsuario))
                OR EXISTS (SELECT 1 FROM EVENTO_AUDITORIA ea
                        WHERE ea.ID_GRUPO = :idGrupo AND ea.ID_USUARIO_ACTOR = :idUsuario)
                OR EXISTS (SELECT 1 FROM HISTORIAL_ESTADO_TAREA h
                        JOIN TAREA_CUIDADO th ON th.ID_TAREA = h.ID_TAREA
                        WHERE th.ID_GRUPO = :idGrupo AND h.ID_USUARIO_CAMBIO = :idUsuario)
            THEN 1 ELSE 0 END
            FROM DUAL
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RepositorioHistorialIntegrante(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Si {@code idUsuario} dejó algún rastro de uso en {@code idGrupo}, más allá de su membresía. */
    public boolean tieneHistorial(long idGrupo, long idUsuario) {
        Integer resultado = jdbcTemplate.queryForObject(SQL_TIENE_HISTORIAL,
                Map.of("idGrupo", idGrupo, "idUsuario", idUsuario), Integer.class);
        return resultado != null && resultado == 1;
    }
}
