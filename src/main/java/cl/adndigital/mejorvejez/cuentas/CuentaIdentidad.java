package cl.adndigital.mejorvejez.cuentas;

/**
 * Datos de una cuenta en el proveedor de identidad: el identificador único
 * ({@code uid}) y el correo con el que quedó registrada.
 */
public record CuentaIdentidad(String uid, String email) {
}
