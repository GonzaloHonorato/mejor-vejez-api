package cl.adndigital.mejorvejez.tareas;

/**
 * Valores exactos de {@code EVENTO_AUDITORIA.CODIGO_ACCION} del módulo
 * {@code tareas}, igual que {@code planificacion.AccionesPlanificacion}: la
 * única fuente de verdad compartida por quien escribe el evento (los
 * servicios de las tareas siguientes de la Fase 4) y quien lo consulta,
 * recibidos siempre como parámetros enlazados y nunca como literales
 * embebidos en el SQL.
 */
final class AccionesTareas {

    /** Cancelación de una tarea de MEDICAMENTOS por el recálculo tras un cambio de plan (regla 8). */
    static final String CANCELAR_TAREA_POR_CAMBIO_PLAN = "CANCELAR_TAREA_POR_CAMBIO_PLAN";

    /** Transición de estado de una tarea (HU-07). */
    static final String CAMBIAR_ESTADO_TAREA = "CAMBIAR_ESTADO_TAREA";

    /** Primera asignación de responsable de una tarea (HU-06). */
    static final String ASIGNAR_TAREA = "ASIGNAR_TAREA";

    /** Reemplazo del responsable activo de una tarea (HU-06). */
    static final String REASIGNAR_TAREA = "REASIGNAR_TAREA";

    /** Alta manual de una tarea de compra/retiro o de cita médica (HU-05). */
    static final String CREAR_TAREA_MANUAL = "CREAR_TAREA_MANUAL";

    /** Corrida manual del proceso de generación de tareas ({@code exigirAdministrador}). */
    static final String EJECUTAR_GENERACION_TAREAS = "EJECUTAR_GENERACION_TAREAS";

    private AccionesTareas() {
    }
}
