package cl.adndigital.mejorvejez.comun;

/**
 * Se lanza cuando no hay una credencial de Firebase configurada ({@code
 * mejorvejez.firebase.credenciales-base64} en blanco) y se intenta una
 * operación de cuentas. {@code ManejadorErrores} la traduce en una
 * respuesta 503 con el mismo mensaje.
 */
public class ExcepcionCuentasNoConfiguradas extends RuntimeException {

    public ExcepcionCuentasNoConfiguradas() {
        super("La creación de cuentas no está configurada");
    }
}
