package cl.adndigital.mejorvejez.adultos;

import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Decide si un adulto mayor o un contacto de emergencia tienen referencias o
 * historial de uso, más allá de su propia fila, para impedir su eliminación
 * física ({@code DELETE .../adultos/{idAdulto}} / {@code DELETE
 * .../contactos/{idContacto}}) cuando sí los tienen (deben desactivarse en
 * su lugar, no borrarse, para no perder la trazabilidad). Regla: crear,
 * actualizar o designar principal no cuentan como historial; los cambios de
 * estado (desactivar/reactivar) sí.
 *
 * <p>No es un {@code JpaRepository}: no hay una entidad JPA que respalde
 * estas consultas (recorren varias tablas sin relación entre sí), así que
 * usa {@link NamedParameterJdbcTemplate} directo, igual que {@code
 * grupos.RepositorioHistorialIntegrante}.
 */
@Repository
public class RepositorioHistorialAdulto {

    private static final String SQL_ADULTO_TIENE_REFERENCIAS = """
            SELECT CASE WHEN
                EXISTS (SELECT 1 FROM CONTACTO_EMERGENCIA ce WHERE ce.ID_ADULTO_MAYOR = :idAdulto)
                OR EXISTS (SELECT 1 FROM PLAN_MEDICAMENTOS pm WHERE pm.ID_ADULTO_MAYOR = :idAdulto)
                OR EXISTS (SELECT 1 FROM TAREA_CUIDADO t WHERE t.ID_ADULTO_MAYOR = :idAdulto)
                OR EXISTS (SELECT 1 FROM OCURRENCIA_PLANIFICADA op WHERE op.ID_ADULTO_MAYOR = :idAdulto)
                OR EXISTS (SELECT 1 FROM ALERTA_CUIDADO al WHERE al.ID_ADULTO_MAYOR = :idAdulto)
                OR EXISTS (SELECT 1 FROM TRASPASO_FAMILIAR tf WHERE tf.ID_ADULTO_MAYOR = :idAdulto)
                OR EXISTS (SELECT 1 FROM EVENTO_AUDITORIA ea
                        WHERE ea.TIPO_ENTIDAD = 'ADULTO_MAYOR' AND ea.ID_ENTIDAD = TO_CHAR(:idAdulto)
                        AND ea.CODIGO_ACCION IN ('DESACTIVAR_ADULTO_MAYOR', 'REACTIVAR_ADULTO_MAYOR'))
            THEN 1 ELSE 0 END
            FROM DUAL
            """;

    // Hoy ninguna tabla referencia a CONTACTO_EMERGENCIA: el historial del
    // contacto es exclusivamente sus propios eventos de cambio de estado.
    private static final String SQL_CONTACTO_TIENE_HISTORIAL = """
            SELECT CASE WHEN
                EXISTS (SELECT 1 FROM EVENTO_AUDITORIA ea
                        WHERE ea.TIPO_ENTIDAD = 'CONTACTO_EMERGENCIA' AND ea.ID_ENTIDAD = TO_CHAR(:idContacto)
                        AND ea.CODIGO_ACCION IN ('DESACTIVAR_CONTACTO', 'REACTIVAR_CONTACTO'))
            THEN 1 ELSE 0 END
            FROM DUAL
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RepositorioHistorialAdulto(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Si el adulto mayor tiene datos relacionados o eventos de cambio de estado, más allá de su propia fila. */
    public boolean adultoTieneReferencias(long idAdulto) {
        Integer resultado = jdbcTemplate.queryForObject(SQL_ADULTO_TIENE_REFERENCIAS,
                Map.of("idAdulto", idAdulto), Integer.class);
        return resultado != null && resultado == 1;
    }

    /** Si el contacto de emergencia tuvo alguna vez un cambio de estado (desactivación o reactivación). */
    public boolean contactoTieneHistorial(long idContacto) {
        Integer resultado = jdbcTemplate.queryForObject(SQL_CONTACTO_TIENE_HISTORIAL,
                Map.of("idContacto", idContacto), Integer.class);
        return resultado != null && resultado == 1;
    }
}
