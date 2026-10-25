package cl.adndigital.mejorvejez.comun;

/**
 * Se lanza cuando el usuario autenticado no tiene permisos para la
 * operación solicitada (autorización, no autenticación).
 * {@code ManejadorErrores} la traduce en una respuesta 403 con título
 * "Acceso denegado".
 *
 * <p>{@link #TITULO} es el mismo texto que usa el 403 que levanta Spring
 * Security antes de llegar a un controlador (autenticado sin permiso),
 * traducido por {@code
 * cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad}: se
 * comparte acá para no duplicar el literal.
 */
public class ExcepcionAccesoDenegado extends RuntimeException {

    public static final String TITULO = "Acceso denegado";

    public ExcepcionAccesoDenegado(String mensaje) {
        super(mensaje);
    }
}
