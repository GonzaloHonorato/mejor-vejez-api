package cl.adndigital.mejorvejez.cuentas;

import java.util.function.Consumer;

import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Datos para dar de alta una cuenta nueva (Firebase + {@code USUARIO}),
 * comunes a las distintas rutas que crean cuentas (administrador,
 * incorporación a un grupo, registro propio).
 *
 * @param nombre        nombre de la cuenta.
 * @param email         correo de la cuenta; se normaliza (trim + minúsculas)
 *                      antes de validarlo y guardarlo.
 * @param telefono      teléfono de la cuenta; puede ser nulo o vacío.
 * @param origen        quién origina el alta, para la auditoría {@code
 *                      CREAR_CUENTA}.
 * @param idActor       usuario que ejecuta el alta (administrador o
 *                      coordinador autenticado), para {@code
 *                      idUsuarioActor} de la auditoría; solo puede ser
 *                      {@code null} cuando {@code origen} es {@link
 *                      OrigenCuenta#REGISTRO} (ahí el actor es el propio
 *                      usuario recién creado); en cualquier otro caso, un
 *                      {@code idActor} nulo lanza {@link
 *                      IllegalArgumentException}.
 * @param claveElegida  contraseña a usar; {@code null} genera una temporal
 *                      con {@link GeneradorClaveTemporal}.
 * @param enviarCorreo  si debe pedirse a Alertas el correo de cuenta nueva.
 * @param enTransaccion acción adicional sobre el usuario ya guardado,
 *                      ejecutada dentro de la misma transacción (p. ej.
 *                      incorporarlo a un grupo); puede no hacer nada.
 */
public record AltaCuenta(
        String nombre,
        String email,
        String telefono,
        OrigenCuenta origen,
        Long idActor,
        String claveElegida,
        boolean enviarCorreo,
        Consumer<Usuario> enTransaccion) {

    /** @throws IllegalArgumentException {@code idActor} es {@code null} y {@code origen} no es {@code REGISTRO}. */
    public AltaCuenta {
        if (idActor == null && origen != OrigenCuenta.REGISTRO) {
            throw new IllegalArgumentException("idActor es obligatorio salvo en el registro propio");
        }
    }

    /** Nunca muestra {@code claveElegida}: solo si hay una o no. */
    @Override
    public String toString() {
        return "AltaCuenta[nombre=" + nombre + ", email=" + email + ", telefono=" + telefono
                + ", origen=" + origen + ", idActor=" + idActor + ", claveElegida=" + enmascarar(claveElegida)
                + ", enviarCorreo=" + enviarCorreo + ", enTransaccion=" + enTransaccion + "]";
    }

    private static String enmascarar(String clave) {
        return clave == null ? "null" : "***";
    }
}
