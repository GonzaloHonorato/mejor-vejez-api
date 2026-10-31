package cl.adndigital.mejorvejez.usuarios;

import cl.adndigital.mejorvejez.cuentas.CuentaCreada;

/**
 * Respuesta de {@code POST /api/usuarios} y {@code POST
 * /api/grupos/{idGrupo}/integrantes/nuevo}: la contraseña temporal se
 * devuelve una sola vez, en esta respuesta; nunca se vuelve a poder
 * recuperar (fuera de reenviar el acceso con una nueva).
 */
public record RespuestaCuentaCreada(RespuestaUsuario usuario, String claveTemporal, boolean correoEnviado) {

    public static RespuestaCuentaCreada desde(CuentaCreada cuentaCreada) {
        return new RespuestaCuentaCreada(RespuestaUsuario.desde(cuentaCreada.usuario()), cuentaCreada.claveTemporal(),
                cuentaCreada.correoEnviado());
    }

    /** Nunca muestra {@code claveTemporal}. */
    @Override
    public String toString() {
        return "RespuestaCuentaCreada[usuario=" + usuario + ", claveTemporal="
                + (claveTemporal == null ? "null" : "***") + ", correoEnviado=" + correoEnviado + "]";
    }
}
