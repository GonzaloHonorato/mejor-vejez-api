package cl.adndigital.mejorvejez.comun;

/**
 * Se lanza cuando el usuario autenticado no tiene permisos para la
 * operación solicitada (autorización, no autenticación).
 * {@code ManejadorErrores} la traduce en una respuesta 403 con título
 * "Acceso denegado".
 */
public class ExcepcionAccesoDenegado extends RuntimeException {

    public ExcepcionAccesoDenegado(String mensaje) {
        super(mensaje);
    }
}
