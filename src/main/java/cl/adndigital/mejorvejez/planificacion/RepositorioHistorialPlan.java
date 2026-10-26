package cl.adndigital.mejorvejez.planificacion;

import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Decide si un {@link DetallePlan} o un {@link PlanMedicamentos} tienen
 * referencias o historial de uso, más allá de su propia fila, para impedir
 * su eliminación física (DELETE físico solo sin referencias ni historial,
 * Tarea 3) cuando sí los tienen (deben desactivarse en su lugar, no
 * borrarse, para no perder la trazabilidad). Regla: crear o actualizar datos
 * no cuentan como historial; los cambios de estado (desactivar/reactivar) sí.
 *
 * <p>No es un {@code JpaRepository}: no hay una entidad JPA que respalde
 * estas consultas (recorren varias tablas sin relación entre sí), así que
 * usa {@link NamedParameterJdbcTemplate} directo, igual que {@code
 * adultos.RepositorioHistorialAdulto}.
 *
 * <p>Los valores de {@code TIPO_ENTIDAD} ({@link TiposEntidadPlanificacion})
 * y de {@code CODIGO_ACCION} ({@link AccionesPlanificacion}) van como
 * parámetros enlazados, nunca como literales embebidos en el SQL: son las
 * mismas constantes que usan {@link ServicioPlanes} y {@link
 * ServicioDetallesPlan} al registrar esos eventos, así que ambos lados no
 * pueden desalinearse.
 */
@Repository
public class RepositorioHistorialPlan {

    private static final String SQL_DETALLE_TIENE_REFERENCIAS = """
            SELECT CASE WHEN
                EXISTS (SELECT 1 FROM TAREA_CUIDADO t WHERE t.ID_DETALLE_PLAN = :idDetalle)
                OR EXISTS (SELECT 1 FROM OCURRENCIA_PLANIFICADA op WHERE op.ID_DETALLE_PLAN = :idDetalle)
                OR EXISTS (SELECT 1 FROM ALERTA_CUIDADO al WHERE al.ID_DETALLE_PLAN = :idDetalle)
                OR EXISTS (SELECT 1 FROM EVENTO_AUDITORIA ea
                        WHERE ea.TIPO_ENTIDAD = :tipoEntidadDetalle AND ea.ID_ENTIDAD = TO_CHAR(:idDetalle)
                        AND ea.CODIGO_ACCION IN (:accionesEstadoDetalle))
            THEN 1 ELSE 0 END
            FROM DUAL
            """;

    // Un plan tiene referencias si alguno de sus detalles las tiene (misma
    // regla que SQL_DETALLE_TIENE_REFERENCIAS, repetida como EXISTS
    // correlacionado para no consultar detalle por detalle), o si el plan
    // mismo tuvo alguna vez un cambio de estado.
    private static final String SQL_PLAN_TIENE_REFERENCIAS = """
            SELECT CASE WHEN
                EXISTS (SELECT 1 FROM DETALLE_PLAN dp WHERE dp.ID_PLAN = :idPlan AND (
                    EXISTS (SELECT 1 FROM TAREA_CUIDADO t WHERE t.ID_DETALLE_PLAN = dp.ID_DETALLE_PLAN)
                    OR EXISTS (SELECT 1 FROM OCURRENCIA_PLANIFICADA op WHERE op.ID_DETALLE_PLAN = dp.ID_DETALLE_PLAN)
                    OR EXISTS (SELECT 1 FROM ALERTA_CUIDADO al WHERE al.ID_DETALLE_PLAN = dp.ID_DETALLE_PLAN)
                    OR EXISTS (SELECT 1 FROM EVENTO_AUDITORIA ea
                            WHERE ea.TIPO_ENTIDAD = :tipoEntidadDetalle AND ea.ID_ENTIDAD = TO_CHAR(dp.ID_DETALLE_PLAN)
                            AND ea.CODIGO_ACCION IN (:accionesEstadoDetalle))
                ))
                OR EXISTS (SELECT 1 FROM EVENTO_AUDITORIA ea2
                        WHERE ea2.TIPO_ENTIDAD = :tipoEntidadPlan AND ea2.ID_ENTIDAD = TO_CHAR(:idPlan)
                        AND ea2.CODIGO_ACCION IN (:accionesEstadoPlan))
            THEN 1 ELSE 0 END
            FROM DUAL
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RepositorioHistorialPlan(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Si el detalle tiene tareas, ocurrencias o alertas generadas, o tuvo alguna vez un cambio de estado. */
    public boolean detalleTieneReferencias(long idDetalle) {
        Integer resultado = jdbcTemplate.queryForObject(SQL_DETALLE_TIENE_REFERENCIAS,
                Map.of("idDetalle", idDetalle,
                        "tipoEntidadDetalle", TiposEntidadPlanificacion.DETALLE_PLAN,
                        "accionesEstadoDetalle", AccionesPlanificacion.CAMBIOS_ESTADO_DETALLE),
                Integer.class);
        return resultado != null && resultado == 1;
    }

    /** Si el plan tiene algún detalle con referencias, o el plan mismo tuvo alguna vez un cambio de estado. */
    public boolean planTieneReferencias(long idPlan) {
        Integer resultado = jdbcTemplate.queryForObject(SQL_PLAN_TIENE_REFERENCIAS,
                Map.of("idPlan", idPlan,
                        "tipoEntidadDetalle", TiposEntidadPlanificacion.DETALLE_PLAN,
                        "tipoEntidadPlan", TiposEntidadPlanificacion.PLAN_MEDICAMENTOS,
                        "accionesEstadoDetalle", AccionesPlanificacion.CAMBIOS_ESTADO_DETALLE,
                        "accionesEstadoPlan", AccionesPlanificacion.CAMBIOS_ESTADO_PLAN),
                Integer.class);
        return resultado != null && resultado == 1;
    }
}
