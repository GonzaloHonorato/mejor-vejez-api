package cl.adndigital.mejorvejez.comun;

/**
 * Se lanza cuando la operación necesita un usuario autenticado y la petición
 * no trae una sesión válida (sin JWT, o JWT sin {@code sub}).
 * {@code ManejadorErrores} la traduce en una respuesta 401 con título
 * "No autenticado".
 *
 * <p>{@link #TITULO} y {@link #MENSAJE_SESION_INVALIDA} son los mismos
 * textos que usa el 401 que levanta Spring Security antes de llegar a un
 * controlador (sin token, token inválido), traducido por {@code
 * cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad}: se
 * comparten acá para no duplicar el literal.
 */
public class ExcepcionNoAutenticado extends RuntimeException {

    public static final String TITULO = "No autenticado";
    public static final String MENSAJE_SESION_INVALIDA = "Se requiere una sesión válida.";

    public ExcepcionNoAutenticado(String mensaje) {
        super(mensaje);
    }
}
