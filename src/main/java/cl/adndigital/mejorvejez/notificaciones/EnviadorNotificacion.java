package cl.adndigital.mejorvejez.notificaciones;

/**
 * Enviador de notificaciones por un canal ({@code CORREO} o {@code
 * WHATSAPP}, {@code CANAL_NOTIFICACION_USUARIO.CANAL}). {@link #enviar}
 * nunca lanza por un fallo de envío (SMTP caído, destino inválido, etc.): lo
 * devuelve como {@link ResultadoEnvio#fallido}, para que el llamador siempre
 * pueda registrar el intento (regla 9 de la Fase 5).
 */
public interface EnviadorNotificacion {

    /** Canal que maneja este enviador ({@code CodigosNotificaciones.CANAL_*}). */
    String canal();

    /** Si el canal está operativo (configurado y, en el caso de WhatsApp, habilitado). */
    boolean disponible();

    /** Envía el mensaje. Nunca lanza por un fallo de envío: lo devuelve como resultado. */
    ResultadoEnvio enviar(MensajeNotificacion mensaje);
}
