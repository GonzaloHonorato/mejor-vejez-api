package cl.adndigital.mejorvejez.interno;

/**
 * Cuerpo que el core envía a Alertas en {@code POST
 * /api/interno/correos/recuperar-clave} (HU-01).
 */
public record SolicitudCorreoRecuperarClave(String nombre, String email, String enlace) {

    /** Nunca muestra {@code enlace} (trae el {@code oobCode}): solo si hay uno o no. */
    @Override
    public String toString() {
        return "SolicitudCorreoRecuperarClave[nombre=" + nombre + ", email=" + email
                + ", enlace=" + enmascarar(enlace) + "]";
    }

    private static String enmascarar(String enlace) {
        return enlace == null ? "null" : "***";
    }
}
