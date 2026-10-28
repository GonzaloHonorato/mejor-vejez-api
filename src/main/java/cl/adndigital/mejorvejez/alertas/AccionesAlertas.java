package cl.adndigital.mejorvejez.alertas;

/**
 * Valores exactos de {@code EVENTO_AUDITORIA.CODIGO_ACCION} del módulo
 * {@code alertas}, igual que {@code tareas.AccionesTareas}: la única fuente
 * de verdad compartida por quien escribe el evento (los servicios de las
 * tareas siguientes de la Fase 5) y quien lo consulta, recibidos siempre
 * como parámetros enlazados y nunca como literales embebidos en el SQL.
 */
final class AccionesAlertas {

    /** Cierre de una alerta por el coordinador o el administrador (TSA-47, TSA-9). */
    static final String CERRAR_ALERTA = "CERRAR_ALERTA";

    /** Corrida manual del proceso de detección de atrasos ({@code exigirAdministrador}). */
    static final String EJECUTAR_DETECCION_ATRASOS = "EJECUTAR_DETECCION_ATRASOS";

    /** Corrida manual del proceso de alertas preventivas ({@code exigirAdministrador}). */
    static final String EJECUTAR_ALERTAS_PREVENTIVAS = "EJECUTAR_ALERTAS_PREVENTIVAS";

    /** Corrida manual del reporte semanal, reenviada al servicio de Reportes ({@code exigirAdministrador}). */
    static final String EJECUTAR_REPORTE_SEMANAL = "EJECUTAR_REPORTE_SEMANAL";

    private AccionesAlertas() {
    }
}
