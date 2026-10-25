package cl.adndigital.mejorvejez.comun;

/**
 * Se lanza cuando el recurso solicitado no existe. {@code ManejadorErrores}
 * la traduce en una respuesta 404 con título "Recurso no encontrado".
 */
public class ExcepcionNoEncontrado extends RuntimeException {

    public ExcepcionNoEncontrado(String mensaje) {
        super(mensaje);
    }
}
