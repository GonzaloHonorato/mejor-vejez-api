package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import cl.adndigital.mejorvejez.comun.ExcepcionCuentasNoConfiguradas;

/**
 * Prueba unitaria de {@link ProveedorIdentidadNoConfigurado}, sin Spring: es
 * la implementación que se usa cuando falta la credencial de Firebase.
 */
class ProveedorIdentidadNoConfiguradoTest {

    private final ProveedorIdentidadNoConfigurado proveedor = new ProveedorIdentidadNoConfigurado();

    @Test
    void todasLasOperacionesDan503() {
        assertThatThrownBy(() -> proveedor.buscarPorCorreo("ana@correo.cl"))
                .isInstanceOf(ExcepcionCuentasNoConfiguradas.class);
        assertThatThrownBy(() -> proveedor.crear("ana@correo.cl", "Ana", "Clave2345678"))
                .isInstanceOf(ExcepcionCuentasNoConfiguradas.class);
        assertThatThrownBy(() -> proveedor.fijarClave("uid-1", "Clave2345678"))
                .isInstanceOf(ExcepcionCuentasNoConfiguradas.class);
        assertThatThrownBy(() -> proveedor.eliminar("uid-1"))
                .isInstanceOf(ExcepcionCuentasNoConfiguradas.class);
        assertThatThrownBy(() -> proveedor.enlaceRestablecerClave("ana@correo.cl"))
                .isInstanceOf(ExcepcionCuentasNoConfiguradas.class);
    }
}
