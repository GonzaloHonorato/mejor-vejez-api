package cl.adndigital.mejorvejez.planificacion;

import java.util.List;

/**
 * Valores exactos de {@code EVENTO_AUDITORIA.CODIGO_ACCION} del módulo
 * {@code planificacion} para planes y detalles. Igual que {@link
 * TiposEntidadPlanificacion}, es la única fuente de verdad compartida por
 * quien escribe el evento ({@link ServicioPlanes}, {@link
 * ServicioDetallesPlan}) y quien lo consulta ({@link
 * RepositorioHistorialPlan}, que los recibe como parámetros enlazados y
 * nunca como literales embebidos en el SQL).
 */
final class AccionesPlanificacion {

    static final String CREAR_PLAN = "CREAR_PLAN";
    static final String ACTUALIZAR_PLAN = "ACTUALIZAR_PLAN";
    static final String DESACTIVAR_PLAN = "DESACTIVAR_PLAN";
    static final String REACTIVAR_PLAN = "REACTIVAR_PLAN";
    static final String ELIMINAR_PLAN = "ELIMINAR_PLAN";

    static final String AGREGAR_MEDICAMENTO_PLAN = "AGREGAR_MEDICAMENTO_PLAN";
    static final String ACTUALIZAR_MEDICAMENTO_PLAN = "ACTUALIZAR_MEDICAMENTO_PLAN";
    static final String ACTUALIZAR_CANTIDAD_DISPONIBLE = "ACTUALIZAR_CANTIDAD_DISPONIBLE";
    static final String DESACTIVAR_MEDICAMENTO_PLAN = "DESACTIVAR_MEDICAMENTO_PLAN";
    static final String REACTIVAR_MEDICAMENTO_PLAN = "REACTIVAR_MEDICAMENTO_PLAN";
    static final String ELIMINAR_MEDICAMENTO_PLAN = "ELIMINAR_MEDICAMENTO_PLAN";

    /** Cambios de estado de un plan: cuentan como historial (impiden su borrado físico). */
    static final List<String> CAMBIOS_ESTADO_PLAN = List.of(DESACTIVAR_PLAN, REACTIVAR_PLAN);

    /** Cambios de estado de un detalle: cuentan como historial (impiden su borrado físico). */
    static final List<String> CAMBIOS_ESTADO_DETALLE =
            List.of(DESACTIVAR_MEDICAMENTO_PLAN, REACTIVAR_MEDICAMENTO_PLAN);

    private AccionesPlanificacion() {
    }
}
