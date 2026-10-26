package cl.adndigital.mejorvejez.notificaciones;

/**
 * Resultado de un intento de envío ({@link EnviadorNotificacion#enviar}),
 * nunca una excepción: {@code exito} indica si se pudo entregar, {@code
 * referenciaProveedor} es el identificador que da el proveedor (p. ej. el
 * Message-ID del correo, ≤200 caracteres) y {@code detalleError} es el
 * detalle de un fallo (≤500 bytes UTF-8, lo acota quien persiste el intento).
 */
public record ResultadoEnvio(boolean exito, String referenciaProveedor, String detalleError) {

    /** Envío exitoso, con la referencia que da el proveedor. */
    public static ResultadoEnvio exitoso(String referenciaProveedor) {
        return new ResultadoEnvio(true, referenciaProveedor, null);
    }

    /** Envío fallido, con el detalle del error. */
    public static ResultadoEnvio fallido(String detalleError) {
        return new ResultadoEnvio(false, null, detalleError);
    }
}
