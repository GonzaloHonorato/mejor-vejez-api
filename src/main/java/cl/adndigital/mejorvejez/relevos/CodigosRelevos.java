package cl.adndigital.mejorvejez.relevos;

/**
 * Valores exactos (nombres de tabla/dominio, códigos de estado, límites de
 * la foto y de auditoría) del módulo {@code relevos} (HU-11, TSA-51).
 * Pública: {@code ServicioRelevos} y sus pruebas la comparten como única
 * fuente de verdad, igual que {@code tareas.CodigosTareas} / {@code
 * alertas.CodigosAlertas}.
 */
public final class CodigosRelevos {

    // ------------------------------------------------------------------
    // Nombres de tabla/dominio (CATALOGO_ESTADOS.NOMBRE_TABLA/NOMBRE_CAMPO)
    // ------------------------------------------------------------------

    public static final String TABLA_TRASPASO_FAMILIAR = "TRASPASO_FAMILIAR";
    public static final String CAMPO_ESTADO = "ESTADO";

    // ------------------------------------------------------------------
    // Estados de TRASPASO_FAMILIAR.ESTADO (regla 3 de la Fase 7)
    // ------------------------------------------------------------------

    /** No se usa en el MVP: todo relevo se crea directamente {@code COMPLETADA}. */
    public static final String ESTADO_TRASPASO_BORRADOR = "BORRADOR";
    public static final String ESTADO_TRASPASO_COMPLETADA = "COMPLETADA";
    public static final String ESTADO_TRASPASO_CANCELADA = "CANCELADA";

    // ------------------------------------------------------------------
    // Tipos de elemento de DETALLE_TRASPASO.TIPO_ELEMENTO (CK_DETALLE_TRASPASO_TIPO)
    // ------------------------------------------------------------------

    public static final String TIPO_ELEMENTO_TAREA = "TAREA";
    public static final String TIPO_ELEMENTO_ALERTA = "ALERTA";

    // ------------------------------------------------------------------
    // Ventana y límites de la foto (regla 4 de la Fase 7)
    // ------------------------------------------------------------------

    /** Ventana hacia atrás de las tareas de la foto: la misma del panel (HU-10). */
    public static final int DIAS_RETROSPECTIVA_RESUMEN = 7;

    /** Ventana hacia adelante de las tareas de la foto: la misma del panel (HU-10). */
    public static final int HORAS_PROSPECTIVA_RESUMEN = 24;

    /** Máximo de tareas (y, por separado, de alertas) incluidas en la foto. */
    public static final int LIMITE_ELEMENTOS_RESUMEN = 50;

    /** Máximo de bytes UTF-8 del texto {@code RESUMEN} de cada elemento de la foto. */
    public static final int MAXIMO_BYTES_RESUMEN = 1000;

    // ------------------------------------------------------------------
    // Auditoría (EVENTO_AUDITORIA)
    // ------------------------------------------------------------------

    /** {@code EVENTO_AUDITORIA.NOMBRE_SERVICIO} del módulo. */
    public static final String MODULO = "relevos";

    /** {@code EVENTO_AUDITORIA.TIPO_ENTIDAD} de los relevos. */
    public static final String TIPO_ENTIDAD_RELEVO = "TRASPASO_FAMILIAR";

    public static final String ACCION_REGISTRAR_RELEVO = "REGISTRAR_RELEVO";
    public static final String ACCION_CANCELAR_RELEVO = "CANCELAR_RELEVO";

    /** Consulta del detalle de un relevo por un administrador sin membresía (foco de revisión 3, ronda 1). */
    public static final String ACCION_CONSULTAR_RELEVO = "CONSULTAR_RELEVO";

    private CodigosRelevos() {
    }
}
