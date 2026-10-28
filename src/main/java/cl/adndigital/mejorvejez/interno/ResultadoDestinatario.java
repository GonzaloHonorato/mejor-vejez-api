package cl.adndigital.mejorvejez.interno;

/**
 * El resultado del envío a un destinatario, parte de {@link
 * SolicitudResultadoEnvio} (Fase 9, TSA-61). {@code detalleError} se recorta
 * con {@code TextoAcotado} a 500 bytes antes de guardarse; nunca incluye
 * direcciones de correo.
 */
public record ResultadoDestinatario(long idUsuario, boolean enviado, String detalleError) {
}
