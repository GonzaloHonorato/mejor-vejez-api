package cl.adndigital.mejorvejez.alertas;

/**
 * Valores exactos (nombres de tabla/dominio, códigos de estado, tipos de
 * alerta y nombres de proceso) del módulo {@code alertas} (HU-08, TSA-43 a
 * TSA-47). Públicas: el detector de atrasos, el detector de alertas
 * preventivas y los servicios siguientes de la Fase 5 las comparten como
 * única fuente de verdad, igual que {@code tareas.CodigosTareas}.
 */
public final class CodigosAlertas {

    // ------------------------------------------------------------------
    // Nombres de tabla (dominio de CATALOGO_ESTADOS.NOMBRE_TABLA)
    // ------------------------------------------------------------------

    public static final String TABLA_ALERTA_CUIDADO = "ALERTA_CUIDADO";
    public static final String TABLA_INTENTO_NOTIFICACION = "INTENTO_NOTIFICACION";

    /** Nombre de campo de estado de {@code ALERTA_CUIDADO}. */
    public static final String CAMPO_ESTADO = "ESTADO";

    /** Nombre de campo de estado de {@code INTENTO_NOTIFICACION}. */
    public static final String CAMPO_ESTADO_ENVIO = "ESTADO_ENVIO";

    // ------------------------------------------------------------------
    // Estados de ALERTA_CUIDADO.ESTADO
    // ------------------------------------------------------------------

    public static final String ESTADO_ALERTA_ABIERTA = "ABIERTA";
    public static final String ESTADO_ALERTA_CERRADA = "CERRADA";

    // ------------------------------------------------------------------
    // Estados de INTENTO_NOTIFICACION.ESTADO_ENVIO
    // ------------------------------------------------------------------

    public static final String ESTADO_ENVIO_PENDIENTE = "PENDIENTE";
    public static final String ESTADO_ENVIO_ENVIADO = "ENVIADO";
    public static final String ESTADO_ENVIO_FALLIDO = "FALLIDO";

    // ------------------------------------------------------------------
    // Tipos de alerta (ALERTA_CUIDADO.TIPO_ALERTA, CK_ALERTA_TIPO)
    // ------------------------------------------------------------------

    public static final String TIPO_ATRASO_TAREA = "ATRASO_TAREA";
    public static final String TIPO_REPOSICION_MEDICAMENTO = "REPOSICION_MEDICAMENTO";
    public static final String TIPO_CITA_PROXIMA = "CITA_PROXIMA";

    // ------------------------------------------------------------------
    // Procesos programados (EJECUCION_PROCESO.TIPO_EJECUCION)
    // ------------------------------------------------------------------

    public static final String TIPO_EJECUCION_DETECCION_ATRASOS = "DETECCION_ATRASOS";
    public static final String TIPO_EJECUCION_ALERTAS_PREVENTIVAS = "ALERTAS_PREVENTIVAS";

    // ------------------------------------------------------------------
    // Auditoría (EVENTO_AUDITORIA)
    // ------------------------------------------------------------------

    /** {@code EVENTO_AUDITORIA.NOMBRE_SERVICIO} del módulo. */
    public static final String MODULO = "alertas";

    private CodigosAlertas() {
    }
}
