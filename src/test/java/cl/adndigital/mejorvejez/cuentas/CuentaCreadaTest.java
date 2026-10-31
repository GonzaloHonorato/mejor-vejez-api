package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import cl.adndigital.mejorvejez.usuarios.Usuario;

class CuentaCreadaTest {

    @Test
    void toStringNuncaMuestraLaClave() {
        Usuario usuario = new Usuario("uid-1", "ana@correo.cl", "Ana", 1L, OffsetDateTime.now());
        CuentaCreada creada = new CuentaCreada(usuario, "Clave-Secreta-1", true);

        assertThat(creada.toString())
                .doesNotContain("Clave-Secreta-1")
                .contains("claveTemporal=***");
    }
}
