package cl.adndigital.mejorvejez.grupos;

/**
 * Respuesta de {@code POST .../integrantes/nuevo}: el integrante ya
 * incorporado al grupo y la contraseña temporal de su cuenta nueva, que se
 * devuelve una sola vez, en esta respuesta.
 */
public record RespuestaIntegranteCreado(RespuestaIntegrante integrante, String claveTemporal, boolean correoEnviado) {

    /** Nunca muestra {@code claveTemporal}. */
    @Override
    public String toString() {
        return "RespuestaIntegranteCreado[integrante=" + integrante + ", claveTemporal="
                + (claveTemporal == null ? "null" : "***") + ", correoEnviado=" + correoEnviado + "]";
    }
}
