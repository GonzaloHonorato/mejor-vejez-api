package cl.adndigital.mejorvejez.notificaciones;

/**
 * Canal de notificación del usuario autenticado, tal como lo devuelve
 * {@code GET}/{@code PUT /api/yo/canales} (regla 17 de la Fase 5).
 * {@code predeterminado = true} cuando {@code CORREO} sale del {@code
 * USUARIO.EMAIL} de la cuenta porque el usuario no tiene fila propia en
 * {@code CANAL_NOTIFICACION_USUARIO}.
 */
public record RespuestaCanal(String canal, String destino, boolean habilitado, boolean predeterminado) {
}
