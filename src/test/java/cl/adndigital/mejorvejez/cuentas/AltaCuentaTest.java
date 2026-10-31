package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AltaCuentaTest {

    @Test
    void toStringNuncaMuestraLaClaveElegida() {
        AltaCuenta alta = new AltaCuenta("Ana", "ana@correo.cl", null, OrigenCuenta.REGISTRO, null,
                "Clave-Secreta-1", false, usuario -> { });

        assertThat(alta.toString())
                .doesNotContain("Clave-Secreta-1")
                .contains("claveElegida=***");
    }

    @Test
    void toStringSinClaveElegidaMuestraNull() {
        AltaCuenta alta = new AltaCuenta("Ana", "ana@correo.cl", null, OrigenCuenta.ADMINISTRADOR, 1L, null, true,
                usuario -> { });

        assertThat(alta.toString()).contains("claveElegida=null");
    }
}
