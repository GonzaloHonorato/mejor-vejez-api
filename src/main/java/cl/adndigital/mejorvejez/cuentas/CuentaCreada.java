package cl.adndigital.mejorvejez.cuentas;

import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Resultado de dar de alta una cuenta o de reenviar su acceso: el usuario
 * guardado, la contraseña temporal (se devuelve una sola vez, nunca se
 * guarda; nula si la persona eligió su propia contraseña) y si el correo a
 * Alertas se envió.
 */
public record CuentaCreada(Usuario usuario, String claveTemporal, boolean correoEnviado) {

    /** Nunca muestra {@code claveTemporal}. */
    @Override
    public String toString() {
        return "CuentaCreada[usuario=" + usuario + ", claveTemporal=" + enmascarar(claveTemporal)
                + ", correoEnviado=" + correoEnviado + "]";
    }

    private static String enmascarar(String clave) {
        return clave == null ? "null" : "***";
    }
}
