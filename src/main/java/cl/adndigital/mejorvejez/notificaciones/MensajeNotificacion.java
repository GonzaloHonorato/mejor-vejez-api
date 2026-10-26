package cl.adndigital.mejorvejez.notificaciones;

/**
 * Mensaje ya redactado (asunto y cuerpo armados por el servidor, p. ej.
 * {@code alertas.RedactorCorreoAlertas}) listo para que un {@link
 * EnviadorNotificacion} lo entregue por su canal. {@code destino} es el
 * correo o el teléfono del destinatario; nunca sale en respuestas HTTP ni en
 * la auditoría (regla 14/17 de la Fase 5).
 */
public record MensajeNotificacion(String destino, String nombreDestinatario, String asunto, String cuerpo) {
}
