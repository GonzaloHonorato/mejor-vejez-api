package cl.adndigital.mejorvejez.relevos;

/**
 * Un elemento de la foto de un relevo: una tarea o una alerta pendiente al
 * momento de registrarlo (HU-11). {@code tipoElemento} es {@code "TAREA"} o
 * {@code "ALERTA"}; {@code idReferencia} es el id de la tarea o la alerta
 * original (su estado actual puede haber cambiado desde entonces: este
 * {@code resumen} es una foto, no se vuelve a calcular).
 */
public record RespuestaDetalleRelevo(Long idDetalle, String tipoElemento, Long idReferencia, String resumen) {
}
