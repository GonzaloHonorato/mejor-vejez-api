package cl.adndigital.mejorvejez.cuentas;

import java.util.Optional;

import cl.adndigital.mejorvejez.comun.ExcepcionCuentasNoConfiguradas;

/**
 * Implementación de {@link ProveedorIdentidad} para cuando no hay una
 * credencial de Firebase configurada: toda operación lanza {@link
 * ExcepcionCuentasNoConfiguradas}.
 */
public class ProveedorIdentidadNoConfigurado implements ProveedorIdentidad {

    @Override
    public Optional<CuentaIdentidad> buscarPorCorreo(String email) {
        throw new ExcepcionCuentasNoConfiguradas();
    }

    @Override
    public CuentaIdentidad crear(String email, String nombre, String clave) {
        throw new ExcepcionCuentasNoConfiguradas();
    }

    @Override
    public void fijarClave(String uid, String clave) {
        throw new ExcepcionCuentasNoConfiguradas();
    }

    @Override
    public void eliminar(String uid) {
        throw new ExcepcionCuentasNoConfiguradas();
    }

    @Override
    public String enlaceRestablecerClave(String email) {
        throw new ExcepcionCuentasNoConfiguradas();
    }
}
