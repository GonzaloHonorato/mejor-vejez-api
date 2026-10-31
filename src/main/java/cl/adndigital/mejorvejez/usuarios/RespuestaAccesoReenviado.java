package cl.adndigital.mejorvejez.usuarios;

import cl.adndigital.mejorvejez.cuentas.CuentaCreada;

/**
 * Respuesta de {@code POST /api/usuarios/{idUsuario}/reenviar-acceso}: la
 * contraseña temporal nueva se devuelve una sola vez, en esta respuesta.
 */
public record RespuestaAccesoReenviado(String claveTemporal, boolean correoEnviado) {

    public static RespuestaAccesoReenviado desde(CuentaCreada cuentaCreada) {
        return new RespuestaAccesoReenviado(cuentaCreada.claveTemporal(), cuentaCreada.correoEnviado());
    }

    /** Nunca muestra {@code claveTemporal}. */
    @Override
    public String toString() {
        return "RespuestaAccesoReenviado[claveTemporal=" + (claveTemporal == null ? "null" : "***")
                + ", correoEnviado=" + correoEnviado + "]";
    }
}
