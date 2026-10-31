package cl.adndigital.mejorvejez.cuentas;

import java.util.Optional;

import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;

/**
 * Puerto hacia el proveedor de identidad (Firebase) que guarda las cuentas
 * de acceso: quién puede iniciar sesión, con qué contraseña y cómo
 * restablecerla. El core nunca guarda la contraseña; solo la pasa a
 * {@link #crear} o {@link #fijarClave}.
 */
public interface ProveedorIdentidad {

    /** Busca una cuenta por su correo; vacío si no existe. */
    Optional<CuentaIdentidad> buscarPorCorreo(String email);

    /**
     * Crea una cuenta nueva con la contraseña dada.
     *
     * @throws ExcepcionConflicto si ya existe una cuenta con ese correo.
     */
    CuentaIdentidad crear(String email, String nombre, String clave);

    /** Reemplaza la contraseña de la cuenta identificada por {@code uid}. */
    void fijarClave(String uid, String clave);

    /** Elimina la cuenta identificada por {@code uid}. */
    void eliminar(String uid);

    /** Enlace completo de restablecimiento de contraseña para ese correo. */
    String enlaceRestablecerClave(String email);
}
