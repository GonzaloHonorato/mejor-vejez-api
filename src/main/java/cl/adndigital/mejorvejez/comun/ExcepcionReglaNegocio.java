package cl.adndigital.mejorvejez.comun;

/**
 * Se lanza cuando una operación viola una regla de negocio (el dato es
 * válido en su forma, pero no en su contenido). {@code ManejadorErrores} la
 * traduce en una respuesta 422 con título "Regla de negocio".
 */
public class ExcepcionReglaNegocio extends RuntimeException {

    public ExcepcionReglaNegocio(String mensaje) {
        super(mensaje);
    }
}
