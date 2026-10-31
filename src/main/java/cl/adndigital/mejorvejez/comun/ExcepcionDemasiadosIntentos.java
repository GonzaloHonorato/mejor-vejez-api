package cl.adndigital.mejorvejez.comun;

/**
 * Se lanza cuando una ruta limitada por intentos (p. ej. recuperar
 * contraseña) recibe más pedidos de los permitidos dentro de su ventana de
 * tiempo. {@code ManejadorErrores} la traduce en una respuesta 429 con el
 * mismo mensaje.
 */
public class ExcepcionDemasiadosIntentos extends RuntimeException {

    public ExcepcionDemasiadosIntentos() {
        super("Demasiados intentos. Prueba más tarde.");
    }
}
