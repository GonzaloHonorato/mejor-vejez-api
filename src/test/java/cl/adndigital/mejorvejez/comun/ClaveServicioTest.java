package cl.adndigital.mejorvejez.comun;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria de {@link ClaveServicio}, sin Spring: es la guarda
 * fail-closed de {@code /api/interno/**} y no depende de ningún otro
 * componente para comparar en tiempo constante. El caso peligroso es
 * vacía/vacía: si alguien quitara las guardas de {@link
 * ClaveServicio#coincideCon}, {@code MessageDigest.isEqual("", "")}
 * devolvería {@code true} y abriría las rutas internas a cualquiera.
 */
class ClaveServicioTest {

    @Test
    void claveConfiguradaVaciaYRecibidaVaciaNoCoincide() {
        assertThat(new ClaveServicio("").coincideCon("")).isFalse();
    }

    @Test
    void claveConfiguradaVaciaYRecibidaConValorNoCoincide() {
        assertThat(new ClaveServicio("").coincideCon("x")).isFalse();
    }

    @Test
    void claveConfiguradaConValorYRecibidaNulaNoCoincide() {
        assertThat(new ClaveServicio("x").coincideCon(null)).isFalse();
    }

    @Test
    void claveConfiguradaConValorYRecibidaVaciaNoCoincide() {
        assertThat(new ClaveServicio("x").coincideCon("")).isFalse();
    }

    @Test
    void clavesDistintasNoCoinciden() {
        assertThat(new ClaveServicio("x").coincideCon("y")).isFalse();
    }

    @Test
    void clavesIgualesCoinciden() {
        assertThat(new ClaveServicio("x").coincideCon("x")).isTrue();
    }

    @Test
    void claveConfiguradaEnBlancoNoCoincideConUnEspacio() {
        // Con isBlank() (no isEmpty()) en ClaveServicio#coincideCon: una CLAVE_SERVICIO=" " (que el
        // ${CLAVE_SERVICIO:?} del compose acepta porque no está vacía) no abre las rutas a quien
        // manda un espacio como clave.
        assertThat(new ClaveServicio(" ").coincideCon(" ")).isFalse();
    }
}
