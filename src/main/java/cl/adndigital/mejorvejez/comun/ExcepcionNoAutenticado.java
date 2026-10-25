package cl.adndigital.mejorvejez.comun;

/**
 * Se lanza cuando la operación necesita un usuario autenticado y la petición
 * no trae una sesión válida (sin JWT, o JWT sin {@code sub}).
 * {@code ManejadorErrores} la traduce en una respuesta 401 con título
 * "No autenticado".
 */
public class ExcepcionNoAutenticado extends RuntimeException {

    public ExcepcionNoAutenticado(String mensaje) {
        super(mensaje);
    }
}
