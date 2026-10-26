package cl.adndigital.mejorvejez.tareas;

/**
 * Valores exactos (nombres de tabla/dominio, códigos de estado, tipos de
 * tarea y nombres de proceso) del módulo {@code tareas} (HU-05/06/07).
 * Públicas: el generador, el recálculo y los servicios de las tareas
 * siguientes de la Fase 4 las comparten como única fuente de verdad, igual
 * que {@code planificacion.TiposEntidadPlanificacion} las comparte con
 * {@code ServicioPlanes}/{@code ServicioDetallesPlan}.
 */
public final class CodigosTareas {

    // ------------------------------------------------------------------
    // Nombres de tabla (dominio de CATALOGO_ESTADOS.NOMBRE_TABLA)
    // ------------------------------------------------------------------

    public static final String TABLA_TAREA_CUIDADO = "TAREA_CUIDADO";
    public static final String TABLA_OCURRENCIA_PLANIFICADA = "OCURRENCIA_PLANIFICADA";
    public static final String TABLA_ASIGNACION_TAREA = "ASIGNACION_TAREA";
    public static final String TABLA_EJECUCION_PROCESO = "EJECUCION_PROCESO";

    /** Nombre de campo único usado por los cuatro dominios de estado anteriores. */
    public static final String CAMPO_ESTADO = "ESTADO";

    // ------------------------------------------------------------------
    // Estados de TAREA_CUIDADO.ESTADO (regla 10 de la Fase 4)
    // ------------------------------------------------------------------

    public static final String ESTADO_TAREA_PENDIENTE = "PENDIENTE";
    public static final String ESTADO_TAREA_EN_CURSO = "EN_CURSO";
    public static final String ESTADO_TAREA_COMPLETADA = "COMPLETADA";
    public static final String ESTADO_TAREA_NO_REALIZADA = "NO_REALIZADA";
    public static final String ESTADO_TAREA_CANCELADA = "CANCELADA";

    // ------------------------------------------------------------------
    // Estados de OCURRENCIA_PLANIFICADA.ESTADO (regla 4 de la Fase 4)
    // ------------------------------------------------------------------

    public static final String ESTADO_OCURRENCIA_PLANIFICADA = "PLANIFICADA";
    public static final String ESTADO_OCURRENCIA_TAREA_CREADA = "TAREA_CREADA";
    public static final String ESTADO_OCURRENCIA_CANCELADA = "CANCELADA";
    public static final String ESTADO_OCURRENCIA_ERROR = "ERROR";

    // ------------------------------------------------------------------
    // Estados de ASIGNACION_TAREA.ESTADO
    // ------------------------------------------------------------------

    public static final String ESTADO_ASIGNACION_ACTIVO = "ACTIVO";
    public static final String ESTADO_ASIGNACION_REEMPLAZADA = "REEMPLAZADA";
    public static final String ESTADO_ASIGNACION_CANCELADA = "CANCELADA";

    // ------------------------------------------------------------------
    // Estados de EJECUCION_PROCESO.ESTADO
    // ------------------------------------------------------------------

    public static final String ESTADO_EJECUCION_INICIADA = "INICIADA";
    public static final String ESTADO_EJECUCION_EXITOSA = "EXITOSA";
    public static final String ESTADO_EJECUCION_PARCIAL = "PARCIAL";
    public static final String ESTADO_EJECUCION_FALLIDA = "FALLIDA";

    // ------------------------------------------------------------------
    // Tipos de tarea (TAREA_CUIDADO.TIPO_TAREA, CK_TAREA_TIPO)
    // ------------------------------------------------------------------

    public static final String TIPO_TAREA_MEDICAMENTOS = "MEDICAMENTOS";
    public static final String TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS = "COMPRA_RETIRO_MEDICAMENTOS";
    public static final String TIPO_TAREA_CITAS_CONTROLES_MEDICOS = "CITAS_CONTROLES_MEDICOS";

    // ------------------------------------------------------------------
    // Proceso programado de generación (EJECUCION_PROCESO.TIPO_EJECUCION)
    // ------------------------------------------------------------------

    public static final String TIPO_EJECUCION_GENERACION = "GENERACION_TAREAS";

    // ------------------------------------------------------------------
    // Auditoría (EVENTO_AUDITORIA)
    // ------------------------------------------------------------------

    /** {@code EVENTO_AUDITORIA.TIPO_ENTIDAD} de las tareas de cuidado. */
    public static final String TIPO_ENTIDAD_TAREA = "TAREA_CUIDADO";

    /** {@code EVENTO_AUDITORIA.NOMBRE_SERVICIO} del módulo. */
    public static final String MODULO = "tareas";

    private CodigosTareas() {
    }
}
