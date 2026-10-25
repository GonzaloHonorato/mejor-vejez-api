package cl.adndigital.mejorvejez.comun;

/**
 * Se lanza cuando la operación entra en conflicto con el estado actual del
 * recurso (p. ej. una creación duplicada). {@code ManejadorErrores} la
 * traduce en una respuesta 409 con título "Conflicto".
 */
public class ExcepcionConflicto extends RuntimeException {

    public ExcepcionConflicto(String mensaje) {
        super(mensaje);
    }
}
