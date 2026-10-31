package cl.adndigital.mejorvejez.cuentas;

/**
 * Cuerpo de {@code POST /api/publico/recuperar-clave}: el correo de la
 * cuenta a recuperar. No se valida con anotaciones de formato porque la
 * ruta responde 202 siempre, exista o no una cuenta con ese correo.
 */
public record SolicitudRecuperarClave(String email) {
}
