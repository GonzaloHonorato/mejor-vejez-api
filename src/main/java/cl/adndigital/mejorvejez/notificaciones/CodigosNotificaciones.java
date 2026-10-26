package cl.adndigital.mejorvejez.notificaciones;

/**
 * Valores exactos (canales y nombre de módulo) del módulo {@code
 * notificaciones} (TSA-45/TSA-46). Públicas: {@link EnviadorCorreo}, {@link
 * EnviadorWhatsApp} y los servicios siguientes de la Fase 5 (Tarea 3 en
 * adelante) las comparten como única fuente de verdad, igual que {@code
 * alertas.CodigosAlertas}.
 */
public final class CodigosNotificaciones {

    // ------------------------------------------------------------------
    // Canales (CANAL_NOTIFICACION_USUARIO.CANAL / INTENTO_NOTIFICACION.CANAL)
    // ------------------------------------------------------------------

    public static final String CANAL_CORREO = "CORREO";
    public static final String CANAL_WHATSAPP = "WHATSAPP";

    // ------------------------------------------------------------------
    // Auditoría (EVENTO_AUDITORIA)
    // ------------------------------------------------------------------

    /** {@code EVENTO_AUDITORIA.NOMBRE_SERVICIO} del módulo. */
    public static final String MODULO = "notificaciones";

    /** Acción auditada en {@code PUT /api/yo/canales} (regla 17 de la Fase 5). */
    public static final String ACCION_ACTUALIZAR_CANALES_NOTIFICACION = "ACTUALIZAR_CANALES_NOTIFICACION";

    private CodigosNotificaciones() {
    }
}
