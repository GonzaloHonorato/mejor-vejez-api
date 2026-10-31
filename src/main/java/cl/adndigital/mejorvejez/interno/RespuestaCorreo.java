package cl.adndigital.mejorvejez.interno;

/**
 * Respuesta de Alertas a {@code POST /api/interno/correos/cuenta-nueva} y
 * {@code POST /api/interno/correos/recuperar-clave} (HU-01).
 */
public record RespuestaCorreo(boolean enviado) {
}
