package cl.adndigital.mejorvejez.panel;

/**
 * Valores exactos (nombre de módulo, acción de auditoría, límites de listas
 * y ventanas de tiempo) del panel familiar de solo lectura (HU-10, Fase 6).
 * Públicas: {@code ServicioPanel} y {@code RepositorioConsultaPanel} las
 * comparten como única fuente de verdad, igual que {@code
 * tareas.CodigosTareas} / {@code alertas.CodigosAlertas}.
 */
public final class CodigosPanel {

    // ------------------------------------------------------------------
    // Auditoría (EVENTO_AUDITORIA)
    // ------------------------------------------------------------------

    /** {@code EVENTO_AUDITORIA.NOMBRE_SERVICIO} del módulo. */
    public static final String MODULO = "panel";

    /**
     * {@code EVENTO_AUDITORIA.CODIGO_ACCION} de una carga del panel. Regla 8
     * de la Fase 6 (TSA-10, TSA-49): solo se registra cuando el actor es
     * administrador sin membresía en el grupo, y solo para la sección de
     * tareas (esta tarea); las otras secciones no auditan.
     */
    public static final String CONSULTAR_PANEL = "CONSULTAR_PANEL";

    // ------------------------------------------------------------------
    // Límites de las listas de cada sección
    // ------------------------------------------------------------------

    /**
     * Máximo de elementos en las listas de tareas atrasadas, próximos
     * horarios, citas y alertas preventivas (reglas 2, 4, 5 y 6 de la Fase
     * 6).
     */
    public static final int LIMITE_LISTAS = 10;

    /** Máximo de alertas recientes (regla 3 de la Fase 6; Tarea 2). */
    public static final int LIMITE_RECIENTES = 5;

    // ------------------------------------------------------------------
    // Ventanas de tiempo
    // ------------------------------------------------------------------

    /**
     * Retrospectiva de tareas atrasadas: no más viejas que {@code hoy −}
     * este valor (regla 2 de la Fase 6).
     */
    public static final int DIAS_RETROSPECTIVA_ATRASADAS = 7;

    /** Ventana de próximos horarios: {@code [ahora, ahora +} este valor {@code )} (regla 4 de la Fase 6). */
    public static final int HORAS_PROXIMOS_HORARIOS = 24;

    /** Ventana de citas próximas: {@code [ahora, ahora +} este valor {@code )} (regla 5 de la Fase 6). */
    public static final int DIAS_CITAS = 14;

    /** Ventana de alertas cerradas recientes (regla 3 de la Fase 6; Tarea 2). */
    public static final int DIAS_ALERTAS_CERRADAS = 7;

    private CodigosPanel() {
    }
}
