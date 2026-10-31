package cl.adndigital.mejorvejez.interno;

/**
 * Cuerpo que el core envía a Alertas en {@code POST
 * /api/interno/correos/cuenta-nueva} (HU-01): {@code urlIngreso} la arma
 * {@link ClienteServicios} con {@code mejorvejez.app-url}, no la pasa quien
 * llama.
 */
public record SolicitudCorreoCuentaNueva(String nombre, String email, String claveTemporal, String urlIngreso) {

    /** Nunca muestra {@code claveTemporal}. */
    @Override
    public String toString() {
        return "SolicitudCorreoCuentaNueva[nombre=" + nombre + ", email=" + email
                + ", claveTemporal=" + (claveTemporal == null ? "null" : "***") + ", urlIngreso=" + urlIngreso + "]";
    }
}
