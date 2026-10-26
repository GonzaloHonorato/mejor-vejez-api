package cl.adndigital.mejorvejez.planificacion;

/**
 * Valores exactos de {@code EVENTO_AUDITORIA.TIPO_ENTIDAD} del módulo {@code
 * planificacion}, compartidos por {@link RepositorioHistorialPlan} (que los
 * usa para detectar si un plan o un detalle tuvieron alguna vez un cambio de
 * estado) y los servicios que auditan esas entidades ({@code ServicioPlanes},
 * {@code ServicioDetallesPlan}): una sola fuente de verdad para
 * que nunca se desalineen entre quien escribe el evento y quien lo consulta.
 */
final class TiposEntidadPlanificacion {

    static final String PLAN_MEDICAMENTOS = "PLAN_MEDICAMENTOS";
    static final String DETALLE_PLAN = "DETALLE_PLAN";

    private TiposEntidadPlanificacion() {
    }
}
